package org.cubexmc.features.appoint

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.features.Feature
import org.cubexmc.manager.GemAllowanceManager
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.PowerStructureManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PowerCondition
import org.cubexmc.model.PowerStructure
import org.cubexmc.core.CubexText
import org.cubexmc.utils.SchedulerUtil
import java.io.IOException
import org.bukkit.configuration.InvalidConfigurationException
import java.io.File
import org.cubexmc.config.AtomicYamlFiles
import org.cubexmc.storage.AppointmentStore
import java.util.logging.Level
import java.util.Locale
import java.util.UUID

/**
 * 委任功能
 * 允许拥有特定权限的玩家任命其他玩家获得权限集
 */
class AppointFeature(plugin: RuleGems) : Feature(plugin, PERMISSION_PREFIX + "*") {
    @JvmField
    val appointDefinitions: MutableMap<String, AppointDefinition> = HashMap()

    // 任命数据: permSetKey -> appointeeUuid -> Appointment
    private val store = AppointmentStore(
        File(plugin.dataFolder, "data/appoints.yml"),
        File(plugin.dataFolder, "features/appoint_data.yml"),
    )
    private val appointments get() = store.appointments
    private val toggledOffAppointments get() = store.toggles
    var storageFailure = false
        private set

    init {
        plugin.bind(store)
    }

    // 级联撤销（连坐制）
    var isCascadeRevoke: Boolean = true
        private set

    // 配置文件
    private var config: YamlConfiguration = YamlConfiguration()

    // 定时任务句柄（Folia 返回 ScheduledTask，Bukkit 返回 BukkitTask）
    private var refreshTaskHandle: Any? = null

    // 条件刷新间隔（秒）
    private var conditionRefreshInterval = 30

    override fun initialize() = reload()

    override fun shutdown() {
        stopConditionRefreshTask()
        store.close()
        psmOrNull()?.clearAllInNamespace("appoint")
    }

    override fun reload() {
        prepare()
        stopConditionRefreshTask()
        restoreOnlinePlayersPermissions()
        startConditionRefreshTask()
    }

    /** Load gate inputs without restoring grants or starting tasks; used before gem restoration. */
    fun prepare() {
        val file = File(plugin.dataFolder, "features/appoint.yml")
        if (!file.exists()) plugin.saveResource("features/appoint.yml", false)
        val candidate = AtomicYamlFiles.read(file)
        for (path in listOf("enabled", "cascade_revoke")) {
            require(!candidate.contains(path) || candidate.isBoolean(path)) { "Invalid appoint.$path" }
        }
        store.reload()
        config = candidate
        loadConfig()
    }

    /**
     * 加载配置
     */
    private fun loadConfig() {
        appointDefinitions.clear()

        enabled = config.getBoolean("enabled", true)
        isCascadeRevoke = config.getBoolean("cascade_revoke", true)
        conditionRefreshInterval = config.getInt("condition_refresh_interval", 30)

        registerAppointDefinitionsFromGems()

        if (enabled && appointDefinitions.isEmpty()) {
            plugin.logger.warning(
                "Appoint feature is enabled but no appoint definitions were loaded. Define appoints in gems/*.yml or powers/*.yml.",
            )
        }
        plugin.logger.info("Loaded " + appointDefinitions.size + " appoint definitions.")
    }

    /**
     * 从 Gems 中注册 AppointDefinition
     */
    private fun registerAppointDefinitionsFromGems() {
        val gems: List<GemDefinition>? = try {
            plugin.gemParser.gemDefinitions
        } catch (_: UninitializedPropertyAccessException) {
            null
        } catch (_: NullPointerException) {
            null
        }
        if (gems == null) return

        for (gem in gems) {
            registerAppointsRecursively(gem.powerStructure)
        }

        // appoint 职位定义目前以已加载的 Gem / PowerStructure 为来源，
        // 这样运行时看到的职位集合与实际玩法内容保持一致。
    }

    private fun registerAppointsRecursively(power: PowerStructure?) {
        if (power == null) return

        for ((key, def) in power.appoints) {
            if (!appointDefinitions.containsKey(key)) {
                appointDefinitions[key] = def
            }

            registerAppointsRecursively(def.powerStructure)
        }
    }

    fun saveData() = store.flush()

    private fun persistChange(change: () -> Unit): Boolean = try {
        change()
        storageFailure = false
        true
    } catch (failure: IOException) {
        reportStorageFailure(failure)
    } catch (failure: InvalidConfigurationException) {
        reportStorageFailure(failure)
    }

    private fun reportStorageFailure(failure: Exception): Boolean {
        storageFailure = true
        plugin.logger.log(Level.SEVERE, "Cannot persist appointment change; previous state retained.", failure)
        return false
    }

    /**
     * 为在线玩家恢复权限
     */
    private fun restoreOnlinePlayersPermissions() {
        for (player in Bukkit.getOnlinePlayers()) {
            applyPermissions(player)
        }
    }

    /**
     * 任命玩家
     */
    fun appoint(appointer: Player?, appointee: Player?, permSetKey: String?): Boolean {
        storageFailure = false
        if (!enabled || appointer == null || appointee == null || permSetKey == null) return false

        val def = appointDefinitions[permSetKey] ?: return false

        if (!appointer.hasPermission(PERMISSION_PREFIX + permSetKey) && !appointer.hasPermission("rulegems.admin")) {
            return false
        }

        if (isAppointed(appointee.uniqueId, permSetKey)) {
            return false
        }

        if (wouldCreateCycle(appointer.uniqueId, appointee.uniqueId, permSetKey)) {
            plugin.logger.warning(
                "Prevented appointment cycle: " + appointer.name +
                    " tried to appoint " + appointee.name + " for " + permSetKey,
            )
            return false
        }

        if (def.maxAppointments > 0) {
            val currentCount = getAppointmentCountBy(appointer.uniqueId, permSetKey)
            if (currentCount >= def.maxAppointments) {
                return false
            }
        }

        val appointment = Appointment(
            appointee.uniqueId,
            permSetKey,
            appointer.uniqueId,
            System.currentTimeMillis(),
        )

        if (!persistChange { store.add(appointment) }) return false

        applyPermissionsOnEntity(appointee)
        executeCommands(def.onAppoint, appointer, appointee, permSetKey)
        playSound(appointee, def.appointSound)

        return true
    }

    /**
     * 撤销任命
     */
    fun dismiss(dismisser: Player?, appointeeUuid: UUID?, permSetKey: String?): Boolean {
        storageFailure = false
        if (!enabled || dismisser == null || appointeeUuid == null || permSetKey == null) return false

        val def = appointDefinitions[permSetKey] ?: return false
        val setAppointments = appointments[permSetKey] ?: return false
        val appointment = setAppointments[appointeeUuid] ?: return false

        val canDismiss = dismisser.hasPermission("rulegems.admin") ||
            (appointment.appointerUuid != null && appointment.appointerUuid == dismisser.uniqueId)

        if (!canDismiss) return false

        if (!persistChange { store.remove(permSetKey, listOf(appointeeUuid)) }) return false
        clearAppointmentAllowance(appointeeUuid, permSetKey)

        val appointee = Bukkit.getPlayer(appointeeUuid)
        if (appointee != null) {
            runEntityTask(appointee) {
                applyPermissions(appointee)
                executeCommands(def.onRevoke, dismisser, appointee, permSetKey)
                playSoundNow(appointee, def.revokeSound)
            }
        }


        return true
    }

    /**
     * 检查玩家是否被任命了某个权限集
     */
    fun isAppointed(playerUuid: UUID?, permSetKey: String?): Boolean {
        val setAppointments = appointments[permSetKey]
        return playerUuid != null && setAppointments != null && setAppointments.containsKey(playerUuid)
    }

    /**
     * 检查任命是否会形成环
     */
    private fun wouldCreateCycle(appointerUuid: UUID, appointeeUuid: UUID, permSetKey: String?): Boolean {
        val visited: MutableSet<UUID> = HashSet()
        return isAncestorOf(appointeeUuid, appointerUuid, permSetKey, visited)
    }

    /**
     * 递归检查 potentialAncestor 是否是 descendant 在同一职位任命链上的"祖先"。
     * 不同职位代表不同权力来源，允许彼此交叉委任形成政治联盟。
     */
    private fun isAncestorOf(
        potentialAncestor: UUID,
        descendant: UUID,
        permSetKey: String?,
        visited: MutableSet<UUID>,
    ): Boolean {
        if (potentialAncestor == descendant) {
            return true
        }

        if (visited.contains(descendant)) {
            return false
        }
        visited.add(descendant)

        val setAppointments = appointments[permSetKey] ?: return false
        val appointment = setAppointments[descendant]
        val appointer = appointment?.appointerUuid
        if (appointer != null) {
            if (isAncestorOf(potentialAncestor, appointer, permSetKey, visited)) {
                return true
            }
        }

        return false
    }

    /**
     * 获取某人任命的数量
     */
    fun getAppointmentCountBy(appointerUuid: UUID?, permSetKey: String?): Int {
        val setAppointments = appointments[permSetKey] ?: return 0

        var count = 0
        for (appointment in setAppointments.values) {
            if (appointerUuid == appointment.appointerUuid) {
                count++
            }
        }
        return count
    }

    /**
     * 获取玩家的所有任命
     */
    fun getPlayerAppointments(playerUuid: UUID?): List<Appointment> {
        val result = ArrayList<Appointment>()
        if (playerUuid == null) return result
        for (setAppointments in appointments.values) {
            val appointment = setAppointments[playerUuid]
            if (appointment != null) {
                result.add(appointment)
            }
        }
        return result
    }

    fun isAppointmentToggledOff(playerUuid: UUID?, permSetKey: String?): Boolean {
        if (playerUuid == null || permSetKey == null) return false
        val toggledOff = toggledOffAppointments[playerUuid] ?: return false
        return toggledOff.contains(normalizeKey(permSetKey))
    }

    fun setAppointmentPowerEnabled(player: Player?, permSetKey: String?, enabled: Boolean): Boolean {
        storageFailure = false
        if (player == null || permSetKey.isNullOrBlank() || !isAppointed(player.uniqueId, permSetKey)) {
            return false
        }

        if (isAppointmentToggledOff(player.uniqueId, permSetKey) == !enabled) return true
        if (!persistChange { store.setEnabled(player.uniqueId, permSetKey, enabled) }) return false

        applyPermissions(player)
        return true
    }

    /**
     * 获取权限集的所有被任命者
     */
    fun getAppointees(permSetKey: String?): List<Appointment> {
        val setAppointments = appointments[permSetKey] ?: return ArrayList()
        return ArrayList(setAppointments.values)
    }

    /**
     * 应用权限和效果给玩家
     * 使用 PowerStructureManager 统一管理
     */
    fun applyPermissions(player: Player?) {
        if (player == null) return
        val psm = psmOrNull()

        if (psm != null) {
            psm.clearNamespace(player, "appoint")
        }

        val processedSets: MutableSet<String> = HashSet()
        val activeAllowanceSets: MutableSet<String> = HashSet()
        for (appointment in getPlayerAppointments(player.uniqueId)) {
            if (isAppointmentToggledOff(player.uniqueId, appointment.permSetKey)) {
                continue
            }
            applyAppointmentPowers(appointment.permSetKey, player, processedSets, activeAllowanceSets)
        }
        syncAppointmentAllowances(player, activeAllowanceSets)

        player.recalculatePermissions()
    }

    /**
     * 递归应用任命的 PowerStructure（处理继承）
     */
    private fun applyAppointmentPowers(
        permSetKey: String,
        player: Player,
        processedSets: MutableSet<String>,
        activeAllowanceSets: MutableSet<String>?,
    ) {
        if (processedSets.contains(permSetKey)) return
        processedSets.add(permSetKey)

        val def = appointDefinitions[permSetKey] ?: return
        val power = def.powerStructure

        val psm = psmOrNull()
        if (psm != null) {
            val extendedPower = PowerStructure()
            extendedPower.setPermissions(ArrayList(power.permissions))
            extendedPower.setVaultGroups(power.vaultGroups)
            extendedPower.setEffects(power.effects)
            extendedPower.setAllowedCommands(power.allowedCommands)
            extendedPower.setCondition(power.condition)

            val perms = extendedPower.permissions
            for (appointKey in power.appoints.keys) {
                val appointPerm = PERMISSION_PREFIX + appointKey
                if (!perms.contains(appointPerm)) {
                    perms.add(appointPerm)
                }
            }

            val applied = psm.applyStructure(player, extendedPower, "appoint", permSetKey, true)
            if (applied) {
                val allowanceManager = getAllowanceManager()
                if (allowanceManager != null) {
                    allowanceManager.applyAppointmentAllowedCommands(player, permSetKey, extendedPower, false)
                }
                activeAllowanceSets?.add(permSetKey)
            }
        }
    }

    /**
     * 执行命令
     */
    private fun executeCommands(commands: List<String>?, appointer: Player, target: Player, permSetKey: String) {
        if (commands.isNullOrEmpty()) return

        val def = appointDefinitions[permSetKey]
        val displayName = if (def != null) {
            CubexText.translateColorCodes(def.displayName) ?: permSetKey
        } else {
            permSetKey
        }

        for (cmd in commands) {
            val processed = cmd
                .replace("%player%", appointer.name)
                .replace("%target%", target.name)
                .replace("%perm_set%", displayName)

            if (processed.startsWith("console: ")) {
                val consoleCmd = processed.substring(9)
                SchedulerUtil.globalRun(plugin, Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd) }, 0L, -1L)
            } else if (processed.startsWith("player: ")) {
                val playerCmd = processed.substring(8)
                runEntityTask(appointer) { appointer.performCommand(playerCmd) }
            } else {
                val finalProcessed = processed
                SchedulerUtil.globalRun(plugin, Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalProcessed) }, 0L, -1L)
            }
        }
    }

    /**
     * 播放音效
     */
    private fun playSound(player: Player, soundName: String?) {
        runEntityTask(player) { playSoundNow(player, soundName) }
    }

    private fun playSoundNow(player: Player, soundName: String?) {
        if (soundName.isNullOrEmpty()) return
        try {
            val sound = Sound.valueOf(soundName.uppercase(Locale.getDefault()))
            player.playSound(player.location, sound, 1.0f, 1.0f)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * 玩家加入时恢复权限
     */
    fun onPlayerJoin(player: Player?) {
        if (player != null) {
            applyPermissionsOnEntity(player)
        }
    }

    /**
     * 玩家退出时清理
     */
    fun onPlayerQuit(player: Player?) {
        if (player == null) return
        psmOrNull()?.clearNamespace(player, "appoint")
    }

    /**
     * 当玩家失去某个 appoint 权限时调用（级联撤销检查）
     */
    fun onAppointerLostPermission(appointerUuid: UUID?, permSetKey: String?) {
        if (!enabled || !isCascadeRevoke || appointerUuid == null) return

        val visited: MutableSet<UUID> = HashSet()
        onAppointerLostPermissionInternal(appointerUuid, permSetKey, visited)
    }

    /**
     * 级联撤销的内部递归实现
     */
    private fun onAppointerLostPermissionInternal(appointerUuid: UUID, permSetKey: String?, visited: MutableSet<UUID>) {
        if (!enabled || !isCascadeRevoke) return

        if (visited.contains(appointerUuid)) {
            return
        }
        visited.add(appointerUuid)

        val appointer = Bukkit.getPlayer(appointerUuid)

        if (permSetKey != null) {
            cascadeRevokeForPermSet(appointerUuid, appointer, permSetKey, visited)
        } else {
            for (key in appointDefinitions.keys) {
                cascadeRevokeForPermSet(appointerUuid, appointer, key, visited)
            }
        }
    }

    /**
     * 对特定权限集执行级联撤销
     */
    private fun cascadeRevokeForPermSet(
        appointerUuid: UUID,
        appointer: Player?,
        permSetKey: String,
        visited: MutableSet<UUID>,
    ) {
        val stillHasPermission = if (appointer != null && appointer.isOnline) {
            appointer.hasPermission(PERMISSION_PREFIX + permSetKey) || appointer.hasPermission("rulegems.admin")
        } else {
            false
        }

        if (stillHasPermission) return

        val setAppointments = appointments[permSetKey] ?: return

        val toRevoke = ArrayList<UUID>()
        for (appointment in setAppointments.values) {
            if (appointerUuid == appointment.appointerUuid) {
                toRevoke.add(appointment.appointeeUuid)
            }
        }

        if (toRevoke.isEmpty()) return

        val def = appointDefinitions[permSetKey]

        if (!persistChange { store.remove(permSetKey, toRevoke) }) return
        for (appointeeUuid in toRevoke) {
            clearAppointmentAllowance(appointeeUuid, permSetKey)

            val appointee = Bukkit.getPlayer(appointeeUuid)
            if (appointee != null && appointee.isOnline) {
                revokeOnlineAppointee(appointee, appointer, def, permSetKey)
            } else {
                queueOfflineAppointeeRevoke(appointeeUuid, def)
            }

            onAppointerLostPermissionInternal(appointeeUuid, null, visited)
        }


        plugin.logger.info(
            "Cascade revoked " + toRevoke.size + " appointments for perm set '" + permSetKey +
                "' due to appointer losing permission.",
        )
    }

    /**
     * 撤销在线被任命者的权限、执行撤销命令、播放音效
     */
    private fun revokeOnlineAppointee(appointee: Player, appointer: Player?, def: AppointDefinition?, permSetKey: String) {
        runEntityTask(appointee) { revokeOnlineAppointeeNow(appointee, appointer, def, permSetKey) }
    }

    private fun revokeOnlineAppointeeNow(appointee: Player, appointer: Player?, def: AppointDefinition?, permSetKey: String) {
        applyPermissions(appointee)

        if (def != null && def.onRevoke != null) {
            for (cmd in def.onRevoke) {
                val displayName = if (def.displayName != null) {
                    CubexText.translateColorCodes(def.displayName) ?: permSetKey
                } else {
                    permSetKey
                }
                val processed = cmd
                    .replace("%player%", appointer?.name ?: "SYSTEM")
                    .replace("%target%", appointee.name)
                    .replace("%perm_set%", displayName)

                if (processed.startsWith("console: ")) {
                    val consoleCmd = processed.substring(9)
                    SchedulerUtil.globalRun(plugin, Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd) }, 0L, -1L)
                } else if (processed.startsWith("player: ")) {
                    // 跳过玩家命令，因为任命者可能不在线
                } else {
                    val finalProcessed = processed
                    SchedulerUtil.globalRun(plugin, Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalProcessed) }, 0L, -1L)
                }
            }
        }

        if (def != null) {
            playSoundNow(appointee, def.revokeSound)
        }
    }

    /**
     * 将离线被任命者的待撤销权限/Vault组/药水效果入队
     */
    private fun queueOfflineAppointeeRevoke(appointeeUuid: UUID, def: AppointDefinition?) {
        if (def == null) return
        val power = def.powerStructure
        val gm = gemManagerOrNull() ?: return

        val permsToRevoke = ArrayList(power.permissions)
        for (appointKey in power.appoints.keys) {
            permsToRevoke.add(PERMISSION_PREFIX + appointKey)
        }
        gm.queueOfflineRevokes(appointeeUuid, permsToRevoke, power.vaultGroups)
        gm.queueOfflineEffectRevokes(appointeeUuid, power.effects)
    }

    /**
     * 检查并执行所有必要的级联撤销
     * 用于定期检查或在特定事件后调用
     */
    fun checkAllCascadeRevocations() {
        if (!enabled || !isCascadeRevoke) return

        val allAppointers: MutableSet<UUID> = HashSet()
        for (setAppointments in appointments.values) {
            for (appointment in setAppointments.values) {
                val appointer = appointment.appointerUuid
                if (appointer != null) {
                    allAppointers.add(appointer)
                }
            }
        }

        for (appointerUuid in allAppointers) {
            onAppointerLostPermission(appointerUuid, null)
        }
    }

    // Getters
    fun getAppointDefinitions(): Map<String, AppointDefinition> {
        return HashMap(appointDefinitions)
    }

    fun getAppointDefinition(key: String?): AppointDefinition? {
        return appointDefinitions[key]
    }

    /**
     * 获取某个任命者任命的所有人
     */
    fun getAppointmentsByAppointer(appointerUuid: UUID?): List<Appointment> {
        val result = ArrayList<Appointment>()
        if (appointerUuid == null) return result
        for (setAppointments in appointments.values) {
            for (appointment in setAppointments.values) {
                if (appointerUuid == appointment.appointerUuid) {
                    result.add(appointment)
                }
            }
        }
        return result
    }

    /**
     * 获取某个任命者在特定权限集中任命的所有人
     */
    fun getAppointmentsByAppointer(appointerUuid: UUID?, permSetKey: String?): List<Appointment> {
        val result = ArrayList<Appointment>()
        if (appointerUuid == null) return result
        val setAppointments = appointments[permSetKey] ?: return result

        for (appointment in setAppointments.values) {
            if (appointerUuid == appointment.appointerUuid) {
                result.add(appointment)
            }
        }
        return result
    }

    /**
     * 启动条件刷新任务
     * 定期检查所有在线玩家的条件并刷新权限
     */
    private fun startConditionRefreshTask() {
        if (!enabled || conditionRefreshInterval <= 0) return

        val hasConditions = appointDefinitions.values.any { def ->
            def.powerStructure.condition.hasAnyCondition()
        }

        if (!hasConditions) {
            plugin.logger.info("No permission conditions configured, skipping refresh task.")
            return
        }

        val intervalTicks = conditionRefreshInterval * 20L
        refreshTaskHandle = SchedulerUtil.globalRun(
            plugin,
            Runnable {
                for (player in Bukkit.getOnlinePlayers()) {
                    runEntityTask(player) {
                        if (player.isOnline && getPlayerAppointments(player.uniqueId).isNotEmpty()) {
                            applyPermissions(player)
                        }
                    }
                }
            },
            intervalTicks,
            intervalTicks,
        )

        plugin.logger.info("Started condition refresh task (interval: " + conditionRefreshInterval + "s)")
    }

    /**
     * 停止条件刷新任务
     */
    private fun stopConditionRefreshTask() {
        val handle = refreshTaskHandle
        if (handle != null) {
            SchedulerUtil.cancelTask(handle)
            refreshTaskHandle = null
        }
    }

    private fun applyPermissionsOnEntity(player: Player) {
        runEntityTask(player) { applyPermissions(player) }
    }

    private fun getAllowanceManager(): GemAllowanceManager? {
        val gm = gemManagerOrNull()
        return gm?.allowanceManager
    }

    private fun syncAppointmentAllowances(player: Player?, activeAllowanceSets: Set<String>) {
        val allowanceManager = getAllowanceManager()
        if (allowanceManager != null && player != null) {
            allowanceManager.retainAppointmentAllowedCommands(player.uniqueId, activeAllowanceSets)
        }
    }

    private fun clearAppointmentAllowance(playerId: UUID?, permSetKey: String?) {
        val allowanceManager = getAllowanceManager()
        if (allowanceManager != null) {
            allowanceManager.removeAppointmentAllowedCommands(playerId, permSetKey)
        }
    }

    private fun normalizeKey(key: String): String = key.trim().lowercase(Locale.ROOT)

    private fun runEntityTask(player: Player?, task: Runnable?) {
        if (player == null || task == null) return
        if (SchedulerUtil.isFolia()) {
            SchedulerUtil.entityRun(plugin, player, task, 0L, -1L)
        } else {
            task.run()
        }
    }

    private fun runEntityTask(player: Player?, task: () -> Unit) {
        runEntityTask(player, Runnable { task() })
    }

    /**
     * 玩家切换世界时刷新权限
     * 应该由外部监听器调用
     */
    fun onPlayerChangeWorld(player: Player?) {
        if (!enabled || player == null) return

        if (getPlayerAppointments(player.uniqueId).isNotEmpty()) {
            applyPermissions(player)
        }
    }

    /**
     * 检查玩家是否满足某个权限集的条件
     */
    fun checkCondition(player: Player?, permSetKey: String?): Boolean {
        val def = appointDefinitions[permSetKey] ?: return false

        val power = def.powerStructure

        val condition: PowerCondition = power.condition

        return condition.checkConditions(player)
    }

    private fun psmOrNull(): PowerStructureManager? = try {
        plugin.powerStructureManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    private fun gemManagerOrNull(): GemManager? = try {
        plugin.gemManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    companion object {
        const val PERMISSION_PREFIX = "rulegems.appoint."
    }
}
