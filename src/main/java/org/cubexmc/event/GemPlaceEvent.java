package org.cubexmc.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a player places a RuleGem block in the world.
 * Cancelling this event prevents the gem from being registered as placed.
 */
public class GemPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID gemId;
    private final String gemKey;
    private final Location location;
    private boolean cancelled;

    public GemPlaceEvent(Player player, UUID gemId, String gemKey, Location location) {
        this.player = player;
        this.gemId = gemId;
        this.gemKey = gemKey;
        this.location = location;
    }

    /** The player who placed the gem. */
    public Player getPlayer() {
        return player;
    }

    /** The UUID of the gem instance. */
    public UUID getGemId() {
        return gemId;
    }

    /** The gem's definition key (e.g. "fire_gem"). */
    public String getGemKey() {
        return gemKey;
    }

    /** The block location where the gem was placed. */
    public Location getLocation() {
        return location;
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
