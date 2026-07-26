package org.cubexmc.manager

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.cubexmc.RuleGems
import org.cubexmc.gui.GUIHolder
import org.cubexmc.utils.SchedulerUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 托管审计：保证"每颗宝石要么在世界某一格，要么在某个在线玩家背包里"这条不变量。
 *
 * 这是所有脱管路径的兜底层。事件拦截（容器、活塞、爆炸、掉落物）负责给玩家即时反馈，
 * 但事件总有枚举不到的路径——新版本的方块、其它插件用 API 直接搬运物品、服务端崩溃留下的半截状态。
 * 审计不关心宝石"是怎么跑掉的"，只关心"现在状态对不对"，因此对未知路径同样有效。
 *
 * 纠正前要求同一异常**连续观察到两轮**：拾取、逃逸这些操作存在极短的中间态
 * （持有者已设置但坐标尚未解绑），一次性判定会误伤正常流程。
 */
class GemCustodyAuditor(
    private val plugin: RuleGems,
    private val stateManager: GemStateManager,
    private val placementManager: GemPlacementManager,
    private val recovery: GemRecovery,
    private val saveAction: Runnable,
) {
    fun interface GemRecovery {
        /** 把宝石收回世界；返回 true 表示这颗宝石归本插件管理。 */
        fun recover(gemId: UUID?, location: Location?): Boolean
    }

    private enum class Anomaly {
        /** 既不在世界也没有持有者。 */
        MISSING,

        /** 持有者已离线，退出流程没有把宝石放回世界。 */
        OFFLINE_HOLDER,

        /** 持有者在线，但背包里已经没有这颗宝石（被商店/插件搬走了）。 */
        HOLDER_WITHOUT_ITEM,

        /** 同时记为"已放置"和"被持有"。 */
        DOUBLE_BOUND,
    }

    private val suspects: MutableMap<UUID, Anomaly> = ConcurrentHashMap()

    @Volatile
    private var task: Any? = null

    fun start() {
        stop()
        task = SchedulerUtil.globalRun(plugin, { runAudit() }, INITIAL_DELAY_TICKS, INTERVAL_TICKS)
    }

    fun stop() {
        SchedulerUtil.cancelTask(task)
        task = null
        suspects.clear()
    }

    fun runAudit() {
        for (gemId in stateManager.getAllGemUuids()) {
            inspect(gemId)
        }
    }

    private fun inspect(gemId: UUID) {
        val holder = stateManager.getGemHolder(gemId)
        if (holder == null) {
            inspectUnheld(gemId)
            return
        }
        if (!holder.isOnline) {
            flag(gemId, Anomaly.OFFLINE_HOLDER)
            return
        }
        // 背包读取必须在该玩家所属线程上进行（Folia）。
        SchedulerUtil.entityRun(
            plugin,
            holder,
            {
                if (stateManager.getGemHolder(gemId) !== holder) return@entityRun
                when {
                    !stateManager.playerHoldsGem(holder, gemId) -> flag(gemId, Anomaly.HOLDER_WITHOUT_ITEM)
                    stateManager.getGemLocation(gemId) != null -> flag(gemId, Anomaly.DOUBLE_BOUND)
                    else -> suspects.remove(gemId)
                }
            },
            0L,
            -1L,
        )
    }

    private fun inspectUnheld(gemId: UUID) {
        val location = stateManager.getGemLocation(gemId)
        if (location == null) {
            flag(gemId, Anomaly.MISSING)
            return
        }
        // 这里刻意不做任何"坐标是否在允许区域内"的判定。
        // 玩家可以把宝石放在世界上任何地方，审计不是用来把它搬回出生点的。
        // 曾经按 random_place_range 判定越界并搬移，导致玩家放好的宝石几分钟后静默消失。
        suspects.remove(gemId)
        // 渲染修复是幂等的，不需要二次确认：宝石方块被炸掉/被覆盖后重新画回来即可。
        placementManager.restoreRenderingIfMissing(gemId, location)
    }

    private fun flag(gemId: UUID, anomaly: Anomaly) {
        if (suspects.put(gemId, anomaly) != anomaly) return
        suspects.remove(gemId)
        correct(gemId, anomaly)
    }

    private fun correct(gemId: UUID, anomaly: Anomaly) {
        plugin.logger.warning("Custody audit: gem $gemId is $anomaly; correcting.")
        when (anomaly) {
            Anomaly.MISSING -> placementManager.randomPlaceGem(gemId)

            Anomaly.OFFLINE_HOLDER,
            Anomaly.HOLDER_WITHOUT_ITEM,
            -> {
                stateManager.clearGemHolder(gemId)
                placementManager.randomPlaceGem(gemId)
            }

            // 世界里的那一颗是本体，玩家手上的是残留副本。
            Anomaly.DOUBLE_BOUND -> {
                val holder = stateManager.getGemHolder(gemId)
                stateManager.clearGemHolder(gemId)
                if (holder != null) {
                    stateManager.removeGemItemFromInventory(holder, gemId)
                }
            }
        }
        saveAction.run()
    }

    /**
     * 扫描一个非玩家背包：销毁其中的宝石物品，并把宝石收回世界。
     * 商店箱这类通过插件 API 搬运物品、不产生 InventoryClickEvent 的路径靠这一层兜住。
     */
    fun sweepForeignInventory(inventory: Inventory?, contextName: String?): Int {
        if (inventory == null) return 0
        if (inventory.type == InventoryType.PLAYER || inventory.type == InventoryType.CRAFTING) return 0
        if (inventory.holder is Player) return 0
        // 本插件自己的 GUI 只放图标，不该被当成藏匿点清扫。
        if (inventory.holder is GUIHolder) return 0

        val contents = inventory.contents ?: return 0
        val gemIds: MutableSet<UUID> = LinkedHashSet()
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (!stateManager.containsGem(item)) continue
            val removal = stateManager.stripAllGems(item)
            inventory.setItem(slot, removal.item)
            gemIds.addAll(removal.gemIds)
            plugin.logger.warning(
                "Custody audit: removed a Rule Gem stashed in a ${inventory.type} inventory" +
                    (contextName?.let { " (last opened by $it)" } ?: "") + ".",
            )
        }
        var recovered = 0
        for (gemId in gemIds) {
            if (recovery.recover(gemId, inventory.location)) recovered++
        }
        if (recovered > 0) saveAction.run()
        return recovered
    }

    /**
     * 扫描玩家背包，删除"不属于该玩家"的宝石物品。
     * 这些是宝石本体已经被收回世界之后残留的副本（商店买走、崩服回档等）。
     */
    fun sweepPlayerInventory(player: Player?): Int {
        if (player == null) return 0
        val inventory = player.inventory ?: return 0
        val contents = inventory.contents ?: return 0
        var removed = 0
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (!stateManager.containsGem(item)) continue
            val removal = stateManager.stripUnownedGems(item, player.uniqueId)
            if (removal.removedCount == 0) continue
            inventory.setItem(slot, removal.item)
            removed += removal.removedCount
            for (gemId in removal.gemIds) {
                plugin.logger.warning(
                    "Custody audit: removed a duplicate Rule Gem $gemId from ${player.name}'s inventory.",
                )
            }
        }
        if (removed > 0) saveAction.run()
        return removed
    }

    companion object {
        private const val INITIAL_DELAY_TICKS = 20L * 30
        private const val INTERVAL_TICKS = 20L * 60 * 5
    }
}
