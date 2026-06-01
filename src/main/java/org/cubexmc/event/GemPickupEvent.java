package org.cubexmc.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a player picks up a RuleGem from the world (e.g., by breaking the
 * gem block).
 * Cancelling this event prevents the gem from being added to the player's
 * inventory.
 */
public class GemPickupEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID gemId;
    private final String gemKey;
    private final Location fromLocation;
    private boolean cancelled;

    public GemPickupEvent(Player player, UUID gemId, String gemKey, Location fromLocation) {
        this.player = player;
        this.gemId = gemId;
        this.gemKey = gemKey;
        this.fromLocation = fromLocation;
    }

    /** The player picking up the gem. */
    public Player getPlayer() {
        return player;
    }

    /** The UUID of the gem instance. */
    public UUID getGemId() {
        return gemId;
    }

    /** The gem's definition key. */
    public String getGemKey() {
        return gemKey;
    }

    /** The location the gem was picked up from. */
    public Location getFromLocation() {
        return fromLocation;
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
