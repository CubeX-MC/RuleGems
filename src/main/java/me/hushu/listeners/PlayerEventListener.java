package me.hushu.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.hushu.RulerGem;
import me.hushu.manager.GemManager;

public class PlayerEventListener implements Listener {

    private final RulerGem plugin;
    private final GemManager gemManager;

    public PlayerEventListener(RulerGem plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        gemManager.onPlayerQuit(event);
        if (gemManager.getConfigManager().isInventoryGrantsEnabled()) {
            // 退出时无须重算；可在下次加入时重放
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        gemManager.onPlayerDropItem(event);
        if (gemManager.getConfigManager().isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        gemManager.onPlayerDeath(event);
        if (gemManager.getConfigManager().isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getEntity());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        gemManager.onPlayerJoin(event);
        if (gemManager.getConfigManager().isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }
    }
}
