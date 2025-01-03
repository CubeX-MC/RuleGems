package me.hushu.listeners;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import me.hushu.manager.GemManager;

public class GemInventoryListener implements Listener {
    private final GemManager gemManager;

    public GemInventoryListener(GemManager gemManager) {
        this.gemManager = gemManager;
    }
    @EventHandler
    // 禁止玩家将 Gem 放入容器
    public void onInventoryDrag(InventoryDragEvent event) {
        for (ItemStack item : event.getNewItems().values()) {
            if (gemManager.isPowerGem(item)) {
                // 取消拖拽事件以防止将 Gem 放入容器
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(ChatColor.RED + "不能将 Power Gem 拖拽到此处！");
                break;
            }
        }
    }
}
