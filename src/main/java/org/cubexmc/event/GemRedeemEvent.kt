package org.cubexmc.event

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when a player redeems a RuleGem.
 */
class GemRedeemEvent(
    val player: Player,
    val gemId: UUID,
    val gemKey: String,
    val context: RedeemContext,
) : Event(), Cancellable {
    enum class RedeemContext {
        HAND,
        ALTAR,
        FULL_SET,
    }

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
