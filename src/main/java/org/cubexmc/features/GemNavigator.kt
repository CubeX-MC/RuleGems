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
    private val cooldowns: MutableMap<UUID, Long> = HashMap()
    private val navigationSessions: MutableMap<UUID, CompassSession> = HashMap()
    private var nextSessionId = 0L

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
            val msg = plugin.languageManager.formatMessage("feature.navigate.no_gem_found", null) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
            return
        }

        if (maxRange > 0 && result.distance > maxRange) {
            val msg = plugin.languageManager.formatMessage("feature.navigate.out_of_range", null) ?: ""
            player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
            return
        }

        applyCompassTarget(player, result.location)

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

    private fun applyCompassTarget(player: Player, target: Location) {
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

        player.compassTarget = target
        if (activeSeconds < 0) {
            return
        }

        val sessionId = ++nextSessionId
        val task = SchedulerUtil.entityRun(
            plugin,
            player,
            {
                expireNavigationSession(player, sessionId)
            },
            activeSeconds * 20L,
            -1L,
        )
        navigationSessions[playerId] = CompassSession(sessionId, originalTarget, task)
    }

    private fun expireNavigationSession(player: Player, sessionId: Long) {
        val playerId = player.uniqueId
        val session = navigationSessions[playerId] ?: return
        if (session.id != sessionId) {
            return
        }
        navigationSessions.remove(playerId)
        if (!player.isOnline) {
            return
        }
        player.compassTarget = session.originalTarget
        val msg = plugin.languageManager.formatMessage("feature.navigate.expired", null) ?: ""
        player.sendMessage(ColorUtils.translateColorCodes(msg) ?: "")
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
                if (player != null && player.isOnline) {
                    player.compassTarget = session.originalTarget
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
        var nearestDist = Double.MAX_VALUE
        val playerWorld: World? = playerLoc.world

        val gemLocations = gemManager.getAllGemLocations()

        for (gemLoc in gemLocations.values) {
            if (gemLoc == null) continue

            if (gemLoc.world != playerWorld) {
                continue
            }

            val dist = playerLoc.distance(gemLoc)
            if (dist < nearestDist) {
                nearestDist = dist
                nearest = gemLoc
            }
        }

        val location = nearest ?: return null
        return NearestGemResult(location, nearestDist)
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
        val location: Location,
        val distance: Double,
    )

    private class CompassSession(
        val id: Long,
        val originalTarget: Location,
        val task: Any?,
    )

    companion object {
        private const val PERMISSION = "rulegems.navigate"
    }
}
