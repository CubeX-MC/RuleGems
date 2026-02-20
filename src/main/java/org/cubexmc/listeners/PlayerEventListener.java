package org.cubexmc.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.GemManager;

public class PlayerEventListener implements Listener {

    private final RuleGems plugin;
    private final GemManager gemManager;

    public PlayerEventListener(RuleGems plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        gemManager.onPlayerQuit(event);
        if (gemManager.isInventoryGrantsEnabled()) {
            // 退出时无须重算；可在下次加入时重放
        }
        // 通知功能管理器
        if (plugin.getFeatureManager() != null) {
            plugin.getFeatureManager().onPlayerQuit(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        gemManager.onPlayerDropItem(event);
        if (gemManager.isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        gemManager.onPlayerDeath(event);
        if (gemManager.isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getEntity());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        gemManager.onPlayerJoin(event);
        if (gemManager.isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }
        // 通知功能管理器
        if (plugin.getFeatureManager() != null) {
            plugin.getFeatureManager().onPlayerJoin(event.getPlayer());
        }
    }
    
    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        // 通知委任功能刷新权限（用于条件检查）
        if (plugin.getFeatureManager() != null) {
            AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
            if (appointFeature != null && appointFeature.isEnabled()) {
                appointFeature.onPlayerChangeWorld(event.getPlayer());
            }
        }
    }
}
