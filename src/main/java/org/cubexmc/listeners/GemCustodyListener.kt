package org.cubexmc.listeners

import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager

/**
 * 维持"宝石只能是 已放置 或 在线玩家持有"这条不变量的即时拦截层。
 *
 * 覆盖两类脱管路径：
 *  1. **地面物品实体**——容器被破坏吐出宝石、其它插件直接 dropItem 等。
 *     这条路径原先完全没有覆盖：只有 PlayerDropItemEvent 被处理，方块掉落不走那个事件，
 *     于是"宝石放进置物架 → 打掉置物架 → 漏斗吸走 → 进箱子"整条链路畅通无阻。
 *  2. **可储物实体**——展示框、盔甲架、悦灵、箱子矿车/船、驮箱的马。
 *
 * 方块侧的对应规则在 [org.cubexmc.manager.GemManager.handleGemBlockInteract]（手持宝石右键一律
 * 拒绝方块消费该物品），两边合起来才是完整的"宝石不得进入任何存储"。
 */
class GemCustodyListener(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : Listener {

    /** 宝石掉落物一律不允许在世界上存在：直接取消生成并把宝石收回托管。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        val stack = event.entity.itemStack
        if (!gemManager.containsGem(stack)) return
        event.isCancelled = true
        if (gemManager.claimItemCustody(event.entity.uniqueId)) {
            recoverAll(stack, event.entity)
        }
    }

    /** 漏斗/漏斗矿车吸取宝石掉落物。ItemSpawn 之后的第二道保险。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryPickupItem(event: InventoryPickupItemEvent) {
        val stack = event.item.itemStack
        if (!gemManager.containsGem(stack)) return
        event.isCancelled = true
        consumeStrayItem(event.item)
    }

    /** 悦灵、僵尸、铜傀儡等实体捡起宝石掉落物。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val stack = event.item.itemStack
        if (!gemManager.containsGem(stack)) return
        event.isCancelled = true
        consumeStrayItem(event.item)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        denyStorageEntity(event, event.player, event.rightClicked, event.hand)
    }

    // PlayerInteractAtEntityEvent 有自己的 HandlerList，不会被上面的父类处理器接到，必须单独注册。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        denyStorageEntity(event, event.player, event.rightClicked, event.hand)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (!gemManager.containsGem(event.playerItem)) return
        event.isCancelled = true
        languageManager.sendMessage(event.player, "inventory.container_denied")
    }

    /**
     * 玩家关闭任何非玩家背包时顺手扫一遍。
     * 这是商店箱（QuickShop 之类通过自身 API 搬运物品，不产生 InventoryClickEvent）的兜底：
     * 宝石一旦被扫到就地销毁物品并把宝石收回世界。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        gemManager.sweepForeignInventory(event.inventory, event.player.name)
    }

    private fun denyStorageEntity(
        event: Cancellable,
        player: Player,
        target: Entity,
        hand: EquipmentSlot?,
    ) {
        // 展示实体自己就是宝石，交给 GemDisplayListener 处理，别重复提示。
        if (gemManager.isDisplayedGem(target)) return
        if (!canStoreItems(target)) return
        if (!gemManager.containsGem(itemInHand(player, hand))) return
        event.isCancelled = true
        languageManager.sendMessage(player, "inventory.container_denied")
    }

    /**
     * 判定"这个实体能不能把物品收进去"。用接口而不是实体类型清单，
     * 因此箱子矿车、箱子船、驮箱的马、悦灵这些都自动覆盖，新版本新增的也一样。
     */
    private fun canStoreItems(entity: Entity): Boolean {
        if (entity is Player) return false
        return entity is InventoryHolder || entity is ItemFrame || entity is ArmorStand
    }

    private fun itemInHand(player: Player, hand: EquipmentSlot?): ItemStack? {
        val inventory = player.inventory ?: return null
        return if (hand == EquipmentSlot.OFF_HAND) inventory.itemInOffHand else inventory.itemInMainHand
    }

    private fun consumeStrayItem(item: Item) {
        val stack = item.itemStack
        item.remove()
        if (gemManager.claimItemCustody(item.uniqueId)) {
            recoverAll(stack, item)
        }
    }

    private fun recoverAll(stack: ItemStack?, at: Item) {
        val location = at.location
        for (gemId in LinkedHashSet(gemManager.collectGemIds(stack))) {
            gemManager.recoverStrayGem(gemId, location)
        }
    }
}
