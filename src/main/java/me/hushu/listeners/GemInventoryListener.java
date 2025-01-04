package me.hushu.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import me.hushu.manager.GemManager;
import me.hushu.manager.LanguageManager;

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
            if (gemManager.isPowerGem(item)) {
                // 取消拖拽事件以防止将 Gem 放入容器
                event.setCancelled(true);
                languageManager.sendMessage(event.getWhoClicked(), "inventory.drag_denied");
                break;
            }
        }
    }
}
