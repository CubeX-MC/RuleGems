package me.hushu.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.hushu.PowerGem;
import me.hushu.manager.GemManager;

public class PlayerEventListener implements Listener {

    private final PowerGem plugin;
    private final GemManager gemManager;

    public PlayerEventListener(PowerGem plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        gemManager.onPlayerQuit(event);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        gemManager.onPlayerDropItem(event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        gemManager.onPlayerDeath(event);
    }
}
