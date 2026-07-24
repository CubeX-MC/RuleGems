package org.cubexmc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.cubexmc.manager.GemManager

/** Handles the protected hitbox used by proximity_display mode. */
class GemDisplayListener(private val gemManager: GemManager) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityDamage(event: EntityDamageEvent) {
        if (!gemManager.isDisplayedGem(event.entity)) return
        event.isCancelled = true
        val player = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
        gemManager.handleDisplayedGemHit(player, event.entity)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (gemManager.isDisplayedGem(event.rightClicked)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        if (gemManager.isDisplayedGem(event.rightClicked)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (gemManager.isDisplayedGem(event.rightClicked)) {
            event.isCancelled = true
        }
    }
}
