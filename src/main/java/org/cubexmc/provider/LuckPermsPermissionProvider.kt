package org.cubexmc.provider

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import java.lang.reflect.Method
import java.util.UUID
import java.util.function.Consumer

/**
 * Optional LuckPerms bridge implemented through reflection so RuleGems can run
 * without a hard LuckPerms API dependency.
 */
class LuckPermsPermissionProvider(private val plugin: RuleGems) : PermissionProvider {
    private val luckPerms: Any?
    private val userManager: Any?
    private val nodeClass: Class<*>?
    private val inheritanceNodeClass: Class<*>?
    private val nodeMapAdd: Method?
    private val nodeMapRemove: Method?

    init {
        var loadedLuckPerms: Any? = null
        var loadedUserManager: Any? = null
        var loadedNodeClass: Class<*>? = null
        var loadedInheritanceNodeClass: Class<*>? = null
        var loadedAdd: Method? = null
        var loadedRemove: Method? = null
        try {
            val providerClass = Class.forName("net.luckperms.api.LuckPermsProvider")
            loadedLuckPerms = providerClass.getMethod("get").invoke(null)
            loadedUserManager = loadedLuckPerms.javaClass.getMethod("getUserManager").invoke(loadedLuckPerms)
            loadedNodeClass = Class.forName("net.luckperms.api.node.Node")
            loadedInheritanceNodeClass = Class.forName("net.luckperms.api.node.types.InheritanceNode")
            // 必须从公开接口 NodeMap 上取方法，不能从 user.data() 返回的那个对象的 javaClass 取:
            // 实现类 ApiPermissionHolder$NodeMapImpl 是包私有的,getMethod 找得到 add/remove
            // (方法本身是 public),invoke 却会抛 IllegalAccessException——声明它的类不可访问。
            // 这条路径断掉时玩法看不出异常(权限靠 PermissionAttachment 生效),只是兑换的权限和
            // 权限组一个都写不进 LuckPerms。
            val nodeMapClass = Class.forName("net.luckperms.api.model.data.NodeMap")
            loadedAdd = nodeMapClass.getMethod("add", loadedNodeClass)
            loadedRemove = nodeMapClass.getMethod("remove", loadedNodeClass)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.fine("LuckPerms API is not available: " + e.message)
        } catch (e: LinkageError) {
            plugin.logger.fine("LuckPerms API is not available: " + e.message)
        }
        luckPerms = loadedLuckPerms
        userManager = loadedUserManager
        nodeClass = loadedNodeClass
        inheritanceNodeClass = loadedInheritanceNodeClass
        nodeMapAdd = loadedAdd
        nodeMapRemove = loadedRemove
    }

    /**
     * 写入用到的每一个反射目标都在这里解析完毕才算可用。
     *
     * 这一点是有意的：RuleGems 只选一个 provider,LuckPerms 说可用就轮不到 Vault/Bukkit 后备。
     * 所以"能加载 API 类"不够格——真正要用的方法解析不出来时必须报不可用,让上层降级，
     * 而不是启动成功、然后每次写入都失败。
     */
    override fun isAvailable(): Boolean =
        luckPerms != null && userManager != null && nodeClass != null && inheritanceNodeClass != null &&
            nodeMapAdd != null && nodeMapRemove != null

    override fun supportsContext(): Boolean = isAvailable()

    override fun addPermission(player: Player, permission: String) {
        setPermission(player, permission, null, true)
    }

    override fun removePermission(player: Player, permission: String) {
        setPermission(player, permission, null, false)
    }

    override fun addGroup(player: Player, group: String) {
        modifyUser(player) { data -> addNode(data, buildInheritanceNode(group)) }
    }

    override fun removeGroup(player: Player, group: String) {
        modifyUser(player) { data -> removeNode(data, buildInheritanceNode(group)) }
    }

    override fun setPermission(
        player: Player,
        permission: String,
        context: Map<String, String>?,
        value: Boolean,
    ): Boolean {
        if (!isAvailable() || isBlank(permission)) {
            return false
        }
        val node = buildPermissionNode(permission.trim(), context)
        if (node == null) {
            return false
        }
        modifyUser(player) { data ->
            if (value) {
                addNode(data, node)
            } else {
                removeNode(data, node)
            }
        }
        return true
    }

    override fun getName(): String = "LuckPerms"

    private fun modifyUser(player: Player?, dataOperation: ((Any?) -> Unit)?) {
        if (!isAvailable() || player == null || dataOperation == null) {
            return
        }
        val playerId: UUID = player.uniqueId
        try {
            val manager = userManager ?: return
            val modifyUser = manager.javaClass.getMethod("modifyUser", UUID::class.java, Consumer::class.java)
            val consumer = Consumer<Any?> { user -> dataOperation(userData(user)) }
            modifyUser.invoke(manager, playerId, consumer)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to modify LuckPerms user '${player.name}': ${e.message}")
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to modify LuckPerms user '${player.name}': ${e.message}")
        }
    }

    private fun userData(user: Any?): Any? {
        try {
            val loadedUser = user ?: throw IllegalStateException("Failed to access LuckPerms user data")
            return loadedUser.javaClass.getMethod("data").invoke(loadedUser)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Failed to access LuckPerms user data", e)
        } catch (e: LinkageError) {
            throw IllegalStateException("Failed to access LuckPerms user data", e)
        }
    }

    private fun buildPermissionNode(permission: String, context: Map<String, String>?): Any? {
        try {
            val permissionNodeClass = nodeClass ?: return null
            var builder = permissionNodeClass.getMethod("builder", String::class.java).invoke(null, permission)
            builder = applyContext(builder, context)
            val currentBuilder = builder ?: return null
            return currentBuilder.javaClass.getMethod("build").invoke(currentBuilder)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to build LuckPerms permission node '$permission': ${e.message}")
            return null
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to build LuckPerms permission node '$permission': ${e.message}")
            return null
        }
    }

    private fun buildInheritanceNode(group: String?): Any? {
        if (isBlank(group)) {
            return null
        }
        try {
            val inheritanceClass = inheritanceNodeClass ?: return null
            val groupName = group ?: return null
            val builder = inheritanceClass.getMethod("builder", String::class.java).invoke(null, groupName.trim())
            return builder.javaClass.getMethod("build").invoke(builder)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to build LuckPerms group node '$group': ${e.message}")
            return null
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to build LuckPerms group node '$group': ${e.message}")
            return null
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun applyContext(builder: Any?, context: Map<String, String>?): Any? {
        if (builder == null || context == null || context.isEmpty()) {
            return builder
        }
        val withContext: Method = builder.javaClass.getMethod("withContext", String::class.java, String::class.java)
        var current = builder
        for ((key, value) in context) {
            if (isBlank(key) || isBlank(value)) {
                continue
            }
            val next = withContext.invoke(current, key.trim(), value.trim())
            if (next != null) {
                current = next
            }
        }
        return current
    }

    private fun addNode(data: Any?, node: Any?) {
        invokeDataMutation(data, nodeMapAdd, node)
    }

    private fun removeNode(data: Any?, node: Any?) {
        invokeDataMutation(data, nodeMapRemove, node)
    }

    private fun invokeDataMutation(data: Any?, method: Method?, node: Any?) {
        if (data == null || method == null || node == null) {
            return
        }
        try {
            method.invoke(data, node)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to mutate LuckPerms node: " + e.message)
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to mutate LuckPerms node: " + e.message)
        }
    }

    private fun isBlank(value: String?): Boolean = value == null || value.trim().isEmpty()
}
