package org.cubexmc.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

public class GemInventoryListener implements Listener {
    private static final long HINT_COOLDOWN_MS = 8000L;

    private final GemManager gemManager;
    private final LanguageManager languageManager;
    private final Map<UUID, Long> lastGemHintAt = new HashMap<>();

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
        if (gemManager.isInventoryGrantsEnabled() && event.getWhoClicked() instanceof org.bukkit.entity.Player) {
            gemManager.recalculateGrants((org.bukkit.entity.Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player)) return;
        org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getWhoClicked();
        
        // 检查是否尝试将宝石放入非玩家背包的容器
        Inventory topInv = event.getView().getTopInventory();
        InventoryType topType = topInv.getType();
        
        // 如果顶部容器不是玩家背包/合成台，则需要检查
        boolean isExternalContainer = topType != InventoryType.CRAFTING 
                && topType != InventoryType.PLAYER;
        
        if (isExternalContainer) {
            ItemStack currentItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();
            
            // 情况1: Shift+点击宝石（从玩家背包移到容器）
            if (event.isShiftClick() && gemManager.isRuleGem(currentItem)) {
                // 检查点击的是玩家背包区域（底部）
                if (event.getClickedInventory() == event.getView().getBottomInventory()) {
                    event.setCancelled(true);
                    languageManager.sendMessage(player, "inventory.container_denied");
                    return;
                }
            }
            
            // 情况2: 手持宝石点击容器格子（直接放入）
            if (gemManager.isRuleGem(cursorItem) && event.getClickedInventory() == topInv) {
                event.setCancelled(true);
                languageManager.sendMessage(player, "inventory.container_denied");
                return;
            }
            
            // 情况3: 数字键快捷移动宝石到容器
            if (event.getClick() == ClickType.NUMBER_KEY) {
                ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                if (gemManager.isRuleGem(hotbarItem) && event.getClickedInventory() == topInv) {
                    event.setCancelled(true);
                    languageManager.sendMessage(player, "inventory.container_denied");
                    return;
                }
            }
        }
        
        // 背包即生效：实时重算
        if (gemManager.isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(player);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (gemManager.isInventoryGrantsEnabled()) {
            gemManager.recalculateGrants(event.getPlayer());
        }

        org.bukkit.entity.Player player = event.getPlayer();
        ItemStack nextItem = player.getInventory().getItem(event.getNewSlot());
        if (!gemManager.isRuleGem(nextItem)) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastHint = lastGemHintAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastHint < HINT_COOLDOWN_MS) {
            return;
        }
        lastGemHintAt.put(player.getUniqueId(), now);

        if (gemManager.getConfigManager().getGameplayConfig().isHoldToRedeemEnabled()
                && gemManager.getConfigManager().getGameplayConfig().isRedeemEnabled()
                && player.hasPermission("rulegems.redeem")) {
            languageManager.sendMessage(player,
                    gemManager.getConfigManager().getGameplayConfig().isSneakToRedeem()
                            ? "hold_redeem.hint_sneak"
                            : "hold_redeem.hint_normal");
            return;
        }

        if (gemManager.getConfigManager().getGameplayConfig().isRedeemEnabled()
                && player.hasPermission("rulegems.redeem")) {
            languageManager.sendMessage(player, "command.redeem.usage");
        }
    }

    @EventHandler
    // 阻止漏斗等自动移动宝石
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (gemManager.isRuleGem(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
