package org.cubexmc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.RuleGems
import org.cubexmc.manager.GemManager

class PlayerEventListener(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
) : Listener {
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        gemManager.handlePlayerQuit(event.player)
        if (gemManager.isInventoryGrantsEnabled) {
            // 退出时无须重算；可在下次加入时重放
        }
        // 通知功能管理器
        plugin.featureManager?.onPlayerQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (event.isCancelled) return
        gemManager.handleGemDrop(event.player, event.itemDrop.location, event.itemDrop, event.itemDrop.itemStack)
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(event.player)
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        gemManager.handlePlayerDeathDrops(event.entity, event.entity.location, event.drops)
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(event.entity)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        gemManager.handlePlayerJoin(event.player)
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(event.player)
        }
        // 通知功能管理器
        plugin.featureManager?.onPlayerJoin(event.player)
    }

    @EventHandler
    fun onPlayerChangeWorld(event: PlayerChangedWorldEvent) {
        // 通知委任功能刷新权限（用于条件检查）
        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature != null && appointFeature.isEnabled) {
            appointFeature.onPlayerChangeWorld(event.player)
        }
    }
}
