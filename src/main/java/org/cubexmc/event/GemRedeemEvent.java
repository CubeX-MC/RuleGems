package org.cubexmc.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a player redeems a RuleGem (hand-hold, altar, or full-set
 * redeemAll).
 * Cancelling this event prevents permissions and rewards from being granted.
 */
public class GemRedeemEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * The context in which the redeem is happening.
     */
    public enum RedeemContext {
        /** Player held the gem and triggered the redeem via command or hold mechanic */
        HAND,
        /** Player placed the gem on a matching altar */
        ALTAR,
        /** Player redeemed all gems at once via /redeemall */
        FULL_SET
    }

    private final Player player;
    private final UUID gemId;
    private final String gemKey;
    private final RedeemContext context;
    private boolean cancelled;

    public GemRedeemEvent(Player player, UUID gemId, String gemKey, RedeemContext context) {
        this.player = player;
        this.gemId = gemId;
        this.gemKey = gemKey;
        this.context = context;
    }

    /** The player who is redeeming the gem. */
    public Player getPlayer() {
        return player;
    }

    /** The UUID of the gem instance being redeemed. */
    public UUID getGemId() {
        return gemId;
    }

    /** The gem's definition key. */
    public String getGemKey() {
        return gemKey;
    }

    /** The context in which the redeem is happening. */
    public RedeemContext getContext() {
        return context;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
