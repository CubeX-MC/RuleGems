package org.cubexmc.features

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.cubexmc.RuleGems
import org.cubexmc.manager.GemManager
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * 宝石导航功能
 * 允许拥有 rulegems.navigate 权限的玩家使用指南针找到最近的宝石
 */
class GemNavigator(
    plugin: RuleGems,
    private val gemManager: GemManager,
) : Feature(plugin, PERMISSION), Listener {
    private var config: YamlConfiguration? = null

    // 配置选项
    private var maxRange = -1.0
    private var cooldownSeconds = 3
    private var activeSeconds = 10
    private var showDistance = true
    private var distancePrecision = "approximate"
    private var thresholdVeryClose = 50
    private var thresholdClose = 150
    private var thresholdFar = 500

    // 冷却追踪
    private val cooldowns: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val navigationSessions: MutableMap<UUID, CompassSession> = ConcurrentHashMap()
    private val nextSessionId = AtomicLong()

    override fun initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        reload()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        cooldowns.clear()
        clearNavigationSessions(true)
    }

    override fun reload() {
        clearNavigationSessions(true)

        val featuresFolder = File(plugin.dataFolder, "features")
        if (!featuresFolder.exists()) {
            featuresFolder.mkdirs()
        }

        val configFile = File(featuresFolder, "navigate.yml")
        if (!configFile.exists()) {
            plugin.saveResource("features/navigate.yml", false)
        }
        val loaded = YamlConfiguration.loadConfiguration(configFile)
        config = loaded

        enabled = loaded.getBoolean("enabled", true)
        maxRange = loaded.getDouble("max_range", -1.0)
        cooldownSeconds = loaded.getInt("cooldown", 3)
        activeSeconds = loaded.getInt("active_seconds", 10)
        showDistance = loaded.getBoolean("show_distance", true)
        distancePrecision = loaded.getString("distance_precision", "approximate") ?: "approximate"
        thresholdVeryClose = loaded.getInt("distance_thresholds.very_close", 50)
        thresholdClose = loaded.getInt("distance_thresholds.close", 150)
        thresholdFar = loaded.getInt("distance_thresholds.far", 500)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        val player = event.player
        val item = player.inventory?.itemInMainHand ?: return

        if (item.type != Material.COMPASS) return
        if (!enabled) return
        if (!hasPermission(player)) return
        if (!checkCooldown(player)) return

        navigateToNearestGem(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        cancelNavigationSession(event.player, false)
    }

    /**
     * 检查冷却时间
     */
    private fun checkCooldown(player: Player): Boolean {
        if (cooldownSeconds <= 0) return true

        val playerId = player.uniqueId
        val now = System.currentTimeMillis()
        val lastUse = cooldowns[playerId]

        if (lastUse != null) {
            val elapsed = (now - lastUse) / 1000
            if (elapsed < cooldownSeconds) {
                val remaining = (cooldownSeconds - elapsed).toInt()
                val placeholders = HashMap<String, String>()
                placeholders["seconds"] = remaining.toString()
                val msg = plugin.languageManager.formatMessage("feature.navigate.cooldown", placeholders) ?: ""
                player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
                return false
            }
        }

        cooldowns[playerId] = now
        return true
    }

    /**
     * 导航到最近的宝石
     */
    private fun navigateToNearestGem(player: Player) {
        val playerLoc = player.location
        val result = findNearestGem(playerLoc)

        if (result == null) {
            cancelNavigationSession(player, true)
            val msg = plugin.languageManager.formatMessage("feature.navigate.no_gem_found", null) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
            return
        }

        if (maxRange > 0 && result.distance > maxRange) {
            cancelNavigationSession(player, true)
            val msg = plugin.languageManager.formatMessage("feature.navigate.out_of_range", null) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
            return
        }

        applyCompassTarget(player, result)

        val direction = getDirection(playerLoc, result.location)
        val placeholders = HashMap<String, String>()
        placeholders["direction"] = direction

        if (showDistance) {
            val distanceStr = formatDistance(result.distance)
            placeholders["distance"] = distanceStr
            val msg = plugin.languageManager.formatMessage("feature.navigate.found_with_distance", placeholders) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
        } else {
            val msg = plugin.languageManager.formatMessage("feature.navigate.found", placeholders) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
        }
    }

    private fun applyCompassTarget(player: Player, result: NearestGemResult) {
        val playerId = player.uniqueId
        val previousSession = navigationSessions.remove(playerId)
        if (previousSession != null) {
            SchedulerUtil.cancelTask(previousSession.task)
        }

        val originalTarget = previousSession?.originalTarget ?: player.compassTarget
        if (activeSeconds == 0) {
            if (previousSession != null) {
                player.compassTarget = originalTarget
            }
            return
        }

        val target = relativeCompassTarget(player.location, result.location)
        if (target == null) {
            if (previousSession != null) {
                player.compassTarget = originalTarget
            }
            return
        }
        player.compassTarget = target

        val sessionId = nextSessionId.incrementAndGet()
        val expiresAt = if (activeSeconds > 0) {
            System.currentTimeMillis() + activeSeconds.toLong() * 1000L
        } else {
            null
        }
        val task = SchedulerUtil.entityRun(
            plugin,
            player,
            {
                refreshNavigationSession(player, sessionId)
            },
            REFRESH_INTERVAL_TICKS,
            REFRESH_INTERVAL_TICKS,
        )
        navigationSessions[playerId] = CompassSession(
            sessionId,
            originalTarget,
            result.gemId,
            expiresAt,
            task,
        )
    }

    private fun refreshNavigationSession(player: Player, sessionId: Long) {
        val playerId = player.uniqueId
        val session = navigationSessions[playerId] ?: return
        if (session.id != sessionId) {
            return
        }

        if (!player.isOnline) {
            if (navigationSessions.remove(playerId, session)) {
                SchedulerUtil.cancelTask(session.task)
            }
            return
        }

        if (session.expiresAt != null && System.currentTimeMillis() >= session.expiresAt) {
            finishNavigationSession(player, session, true)
            return
        }

        val target = gemManager.getGemLocation(session.gemId)
        if (target == null || gemManager.getGemHolder(session.gemId) != null) {
            finishNavigationSession(player, session, false)
            return
        }

        val playerLocation = player.location
        if (target.world != playerLocation.world) {
            finishNavigationSession(player, session, false)
            return
        }
        if (maxRange > 0 && playerLocation.distance(target) > maxRange) {
            finishNavigationSession(player, session, false)
            return
        }

        val compassTarget = relativeCompassTarget(playerLocation, target)
        if (compassTarget == null) {
            finishNavigationSession(player, session, false)
            return
        }
        player.compassTarget = compassTarget
    }

    private fun finishNavigationSession(player: Player, session: CompassSession, notifyExpired: Boolean) {
        if (!navigationSessions.remove(player.uniqueId, session)) {
            return
        }
        SchedulerUtil.cancelTask(session.task)
        player.compassTarget = session.originalTarget
        if (notifyExpired) {
            val msg = plugin.languageManager.formatMessage("feature.navigate.expired", null) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
        }
    }

    private fun cancelNavigationSession(player: Player, restore: Boolean) {
        val session = navigationSessions.remove(player.uniqueId) ?: return
        SchedulerUtil.cancelTask(session.task)
        if (restore && player.isOnline) {
            player.compassTarget = session.originalTarget
        }
    }

    private fun clearNavigationSessions(restore: Boolean) {
        val snapshot = HashMap(navigationSessions)
        navigationSessions.clear()
        for ((playerId, session) in snapshot) {
            SchedulerUtil.cancelTask(session.task)
            if (restore) {
                val player = Bukkit.getPlayer(playerId)
                if (player != null) {
                    SchedulerUtil.entityRun(
                        plugin,
                        player,
                        {
                            if (player.isOnline && !navigationSessions.containsKey(playerId)) {
                                player.compassTarget = session.originalTarget
                            }
                        },
                        0L,
                        -1L,
                    )
                }
            }
        }
    }

    /**
     * 格式化距离显示
     */
    private fun formatDistance(distance: Double): String {
        if ("exact".equals(distancePrecision, ignoreCase = true)) {
            val ph = HashMap<String, String>()
            ph["distance"] = distance.toInt().toString()
            return plugin.languageManager.formatMessage("feature.navigate.distance.blocks", ph) ?: ""
        }

        val key = if (distance <= thresholdVeryClose) {
            "feature.navigate.distance.very_close"
        } else if (distance <= thresholdClose) {
            "feature.navigate.distance.close"
        } else if (distance <= thresholdFar) {
            "feature.navigate.distance.far"
        } else {
            "feature.navigate.distance.very_far"
        }
        return plugin.languageManager.getMessage(key)
    }

    /**
     * 找到最近的宝石位置
     */
    private fun findNearestGem(playerLoc: Location): NearestGemResult? {
        var nearest: Location? = null
        var nearestGemId: UUID? = null
        var nearestDist = Double.MAX_VALUE
        val playerWorld: World? = playerLoc.world

        val gemLocations = gemManager.getAllGemLocations()

        for ((gemId, gemLoc) in gemLocations) {
            if (gemManager.getGemHolder(gemId) != null) continue

            if (gemLoc.world != playerWorld) {
                continue
            }

            val dist = playerLoc.distance(gemLoc)
            if (dist < nearestDist) {
                nearestDist = dist
                nearest = gemLoc
                nearestGemId = gemId
            }
        }

        val location = nearest ?: return null
        val gemId = nearestGemId ?: return null
        return NearestGemResult(gemId, location, nearestDist)
    }

    /**
     * 获取方向描述
     */
    private fun getDirection(from: Location, to: Location): String {
        val dx = to.x - from.x
        val dz = to.z - from.z

        var angle = Math.toDegrees(Math.atan2(-dx, dz))
        if (angle < 0) angle += 360.0

        return if (angle >= 337.5 || angle < 22.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.south")
        } else if (angle >= 22.5 && angle < 67.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.southwest")
        } else if (angle >= 67.5 && angle < 112.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.west")
        } else if (angle >= 112.5 && angle < 157.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.northwest")
        } else if (angle >= 157.5 && angle < 202.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.north")
        } else if (angle >= 202.5 && angle < 247.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.northeast")
        } else if (angle >= 247.5 && angle < 292.5) {
            plugin.languageManager.getMessage("feature.navigate.direction.east")
        } else {
            plugin.languageManager.getMessage("feature.navigate.direction.southeast")
        }
    }

    /**
     * 最近宝石结果
     */
    private class NearestGemResult(
        val gemId: UUID,
        val location: Location,
        val distance: Double,
    )

    private class CompassSession(
        val id: Long,
        val originalTarget: Location,
        val gemId: UUID,
        val expiresAt: Long?,
        val task: Any?,
    )

    companion object {
        private const val PERMISSION = "rulegems.navigate"
        private const val REFRESH_INTERVAL_TICKS = 10L
    }
}

/**
 * Projects the real gem bearing onto a short-lived waypoint relative to the player.
 * Only this synthetic waypoint is sent through Bukkit's compass target packet.
 */
internal fun relativeCompassTarget(from: Location, to: Location): Location? {
    if (from.world == null || from.world != to.world) return null
    val dx = to.x - from.x
    val dz = to.z - from.z
    val horizontalLength = sqrt(dx * dx + dz * dz)
    if (horizontalLength < MIN_HORIZONTAL_DIRECTION) {
        return from.clone()
    }
    val scale = RELATIVE_TARGET_DISTANCE / horizontalLength
    return Location(
        from.world,
        from.x + dx * scale,
        from.y,
        from.z + dz * scale,
    )
}

private const val RELATIVE_TARGET_DISTANCE = 32.0
private const val MIN_HORIZONTAL_DIRECTION = 1.0e-6
