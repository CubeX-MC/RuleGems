package org.cubexmc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.ItemStack
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
            if (gemManager.containsGem(item)) {
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

        val currentItem = event.currentItem
        val cursorItem = event.cursor

        // 情况0: 把宝石塞进收纳袋/潜影盒这类"物品形态的容器"。
        // 这一步完全发生在玩家背包内部，不涉及任何容器界面，所以必须先于外部容器判定。
        // 不堵的话：宝石 -> 收纳袋 -> 收纳袋丢进箱子，下面所有容器保护都会被绕过。
        if (stashesGemIntoContainerItem(currentItem, cursorItem)) {
            event.isCancelled = true
            languageManager.sendMessage(player, "inventory.container_denied")
            return
        }

        // 检查是否尝试将宝石放入非玩家背包的容器
        val topInventory = event.view.topInventory
        val topType = topInventory.type

        // 如果顶部容器不是玩家背包/合成台，则需要检查
        val isExternalContainer = topType != InventoryType.CRAFTING &&
            topType != InventoryType.PLAYER

        if (isExternalContainer) {
            // 情况1: Shift+点击宝石（从玩家背包移到容器）
            if (event.isShiftClick && gemManager.containsGem(currentItem)) {
                // 检查点击的是玩家背包区域（底部）
                if (event.clickedInventory == event.view.bottomInventory) {
                    event.isCancelled = true
                    languageManager.sendMessage(player, "inventory.container_denied")
                    return
                }
            }

            // 情况2: 手持宝石点击容器格子（直接放入）
            if (gemManager.containsGem(cursorItem) && event.clickedInventory == topInventory) {
                event.isCancelled = true
                languageManager.sendMessage(player, "inventory.container_denied")
                return
            }

            // 情况3: 数字键快捷移动宝石到容器
            if (event.click == ClickType.NUMBER_KEY) {
                val hotbarItem = player.inventory.getItem(event.hotbarButton)
                if (gemManager.containsGem(hotbarItem) && event.clickedInventory == topInventory) {
                    event.isCancelled = true
                    languageManager.sendMessage(player, "inventory.container_denied")
                    return
                }
            }

            // 情况4: 按 F 用副手物品交换当前容器格。这个点击类型不属于 NUMBER_KEY，
            // hotbarButton 也不保证可用，必须直接检查副手，否则能绕过上面三条规则。
            if (event.click == ClickType.SWAP_OFFHAND &&
                gemManager.containsGem(player.inventory.itemInOffHand) &&
                event.clickedInventory == topInventory
            ) {
                event.isCancelled = true
                languageManager.sendMessage(player, "inventory.container_denied")
                return
            }
        }

        // 背包即生效：实时重算
        if (gemManager.isInventoryGrantsEnabled) {
            gemManager.recalculateGrants(player)
        }
    }

    /**
     * 宝石本体是一块普通方块，默认配置里就有 DIAMOND_BLOCK、REDSTONE_BLOCK 这种可逆合成的材质；
     * 原版配方只认材质、不看自定义数据，所以带标记的宝石照样能被拆成 9 个原料，宝石随之凭空消失。
     *
     * 工作台、砂轮这类外部界面已经被 [onInventoryClick] 的容器规则挡住了，但玩家背包自带的 2x2 合成格
     * 属于 [InventoryType.CRAFTING] 视图、是那条规则有意放行的（否则在自己背包里整理宝石都会被拦），
     * 所以必须在合成这一层单独再堵一次。这里先让结果格空掉，玩家一眼就能看出这条路不通。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (containsGemInMatrix(event.inventory.matrix)) {
            event.inventory.result = null
        }
    }

    /** 兜底 [onPrepareCraft]，覆盖 shift 批量合成和任何跳过结果预览直接提交的路径。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (!containsGemInMatrix(event.inventory.matrix)) return
        event.isCancelled = true
        languageManager.sendMessage(event.whoClicked, "inventory.craft_denied")
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
        if (gemManager.containsGem(event.item)) {
            event.isCancelled = true
        }
    }

    private fun containsGemInMatrix(matrix: Array<ItemStack?>): Boolean =
        matrix.any { gemManager.containsGem(it) }

    private fun stashesGemIntoContainerItem(currentItem: ItemStack?, cursorItem: ItemStack?): Boolean =
        (gemManager.isContainerItem(currentItem) && gemManager.containsGem(cursorItem)) ||
            (gemManager.isContainerItem(cursorItem) && gemManager.containsGem(currentItem))

    companion object {
        private const val HINT_COOLDOWN_MS = 8000L
    }
}
