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

    init {
        var loadedLuckPerms: Any? = null
        var loadedUserManager: Any? = null
        var loadedNodeClass: Class<*>? = null
        var loadedInheritanceNodeClass: Class<*>? = null
        try {
            val providerClass = Class.forName("net.luckperms.api.LuckPermsProvider")
            loadedLuckPerms = providerClass.getMethod("get").invoke(null)
            loadedUserManager = loadedLuckPerms.javaClass.getMethod("getUserManager").invoke(loadedLuckPerms)
            loadedNodeClass = Class.forName("net.luckperms.api.node.Node")
            loadedInheritanceNodeClass = Class.forName("net.luckperms.api.node.types.InheritanceNode")
        } catch (e: ReflectiveOperationException) {
            plugin.logger.fine("LuckPerms API is not available: " + e.message)
        } catch (e: LinkageError) {
            plugin.logger.fine("LuckPerms API is not available: " + e.message)
        }
        luckPerms = loadedLuckPerms
        userManager = loadedUserManager
        nodeClass = loadedNodeClass
        inheritanceNodeClass = loadedInheritanceNodeClass
    }

    override fun isAvailable(): Boolean =
        luckPerms != null && userManager != null && nodeClass != null && inheritanceNodeClass != null

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
        invokeDataMutation(data, "add", node)
    }

    private fun removeNode(data: Any?, node: Any?) {
        invokeDataMutation(data, "remove", node)
    }

    private fun invokeDataMutation(data: Any?, methodName: String, node: Any?) {
        if (data == null || node == null) {
            return
        }
        try {
            val method = data.javaClass.getMethod(methodName, nodeClass)
            method.invoke(data, node)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to mutate LuckPerms node: " + e.message)
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to mutate LuckPerms node: " + e.message)
        }
    }

    private fun isBlank(value: String?): Boolean = value == null || value.trim().isEmpty()
}
