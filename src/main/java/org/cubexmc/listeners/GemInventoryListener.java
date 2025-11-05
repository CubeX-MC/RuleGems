package org.cubexmc.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

public class GemInventoryListener implements Listener {
    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public GemInventoryListener(GemManager gemManager, LanguageManager languageManager) {
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @EventHandler
    // 禁止玩家将 Gem 放入容器
    public void onInventoryDrag(InventoryDragEvent event) {
        for (ItemStack item : event.getNewItems().values()) {
            if (gemManager.isRuleGem(item)) {
                // 取消拖拽事件以防止将 Gem 放入容器
                event.setCancelled(true);
                languageManager.sendMessage(event.getWhoClicked(), "inventory.drag_denied");
                break;
            }
        }
        // 背包即生效：实时重算
        if (gemManager.getConfigManager().isInventoryGrantsEnabled() && event.getWhoClicked() instanceof org.bukkit.entity.Player) {
            gemManager.recalculateGrants((org.bukkit.entity.Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (gemManager.getConfigManager().isInventoryGrantsEnabled() && event.getWhoClicked() instanceof org.bukkit.entity.Player) {
            gemManager.recalculateGrants((org.bukkit.entity.Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (gemManager.getConfigManager().isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }
    }
}
