package org.cubexmc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerItemHeldEvent
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import java.util.UUID

class GemInventoryListener(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : Listener {
    private val lastGemHintAt: MutableMap<UUID, Long> = HashMap()

    @EventHandler
    // 禁止玩家将 Gem 放入容器
    fun onInventoryDrag(event: InventoryDragEvent) {
        for (item in event.newItems.values) {
            if (gemManager.isRuleGem(item)) {
                // 取消拖拽事件以防止将 Gem 放入容器
                event.isCancelled = true
                languageManager.sendMessage(event.whoClicked, "inventory.drag_denied")
                break
            }
        }
        // 背包即生效：实时重算
        val player = event.whoClicked
        if (gemManager.isInventoryGrantsEnabled && player is Player) {
            gemManager.recalculateGrants(player)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        // 检查是否尝试将宝石放入非玩家背包的容器
        val topInventory = event.view.topInventory
        val topType = topInventory.type

        // 如果顶部容器不是玩家背包/合成台，则需要检查
        val isExternalContainer = topType != InventoryType.CRAFTING &&
            topType != InventoryType.PLAYER

        if (isExternalContainer) {
            val currentItem = event.currentItem
            val cursorItem = event.cursor

            // 情况1: Shift+点击宝石（从玩家背包移到容器）
            if (event.isShiftClick && gemManager.isRuleGem(currentItem)) {
                // 检查点击的是玩家背包区域（底部）
                if (event.clickedInventory == event.view.bottomInventory) {
                    event.isCancelled = true
                    languageManager.sendMessage(player, "inventory.container_denied")
                    return
                }
            }

            // 情况2: 手持宝石点击容器格子（直接放入）
            if (gemManager.isRuleGem(cursorItem) && event.clickedInventory == topInventory) {
                event.isCancelled = true
                languageManager.sendMessage(player, "inventory.container_denied")
                return
            }

            // 情况3: 数字键快捷移动宝石到容器
            if (event.click == ClickType.NUMBER_KEY) {
                val hotbarItem = player.inventory.getItem(event.hotbarButton)
                if (gemManager.isRuleGem(hotbarItem) && event.clickedInventory == topInventory) {
                    event.isCancelled = true
                    languageManager.sendMessage(player, "inventory.container_denied")
                    return
                }
            }
        }

        // 背包即生效：实时重算
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(player)
        }
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(event.player)
        }

        val player = event.player
        val nextItem = player.inventory.getItem(event.newSlot)
        if (!gemManager.isRuleGem(nextItem)) {
            return
        }

        val now = System.currentTimeMillis()
        val lastHint = lastGemHintAt.getOrDefault(player.uniqueId, 0L)
        if (now - lastHint < HINT_COOLDOWN_MS) {
            return
        }
        lastGemHintAt[player.uniqueId] = now

        if (gemManager.configManager.gameplayConfig.isHoldToRedeemEnabled &&
            gemManager.configManager.gameplayConfig.isRedeemEnabled &&
            player.hasPermission("rulegems.redeem")
        ) {
            languageManager.sendMessage(
                player,
                if (gemManager.configManager.gameplayConfig.isSneakToRedeem) {
                    "hold_redeem.hint_sneak"
                } else {
                    "hold_redeem.hint_normal"
                },
            )
            return
        }

        if (gemManager.configManager.gameplayConfig.isRedeemEnabled &&
            player.hasPermission("rulegems.redeem")
        ) {
            languageManager.sendMessage(player, "command.redeem.usage")
        }
    }

    @EventHandler
    // 阻止漏斗等自动移动宝石
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        if (gemManager.isRuleGem(event.item)) {
            event.isCancelled = true
        }
    }

    companion object {
        private const val HINT_COOLDOWN_MS = 8000L
    }
}
