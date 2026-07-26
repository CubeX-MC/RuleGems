package org.cubexmc.listeners

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.StructureGrowEvent
import org.cubexmc.manager.GemManager

/**
 * 保护"已放置的宝石方块"不被玩家之外的世界机制改变位置或摧毁。
 *
 * 玩家的挖掘/放置由 [GemPlaceListener] 处理，那条路径是设计内的宝石流转；
 * 这里处理的是活塞、爆炸、火、流体、实体改方块等**不经过玩家意图**的改动。
 * 它们会让 `locationToGemUuid` 记录的坐标与世界实际状态脱节：
 * 旧坐标变成空气却仍被记为有宝石（导航指向空气、玩家挖不到），
 * 新坐标则是一块普通方块（挖了就是白得一块宝石材质）。
 *
 * 统一采取"拒绝改动"而不是"跟随更新"：宝石不该是能被红石批量搬运的资源，
 * 且跟随更新要处理事件在移动**之前**触发带来的一系列竞态（落点越界、撞上另一颗宝石、
 * 展示实体重绑），代价远高于收益。
 *
 * 注意 proximity_display 模式下宝石所在方块是空气，因此活塞检查必须同时看**落点**，
 * 否则活塞可以把普通方块推进宝石坐标把它埋掉。
 */
class GemBlockProtectionListener(private val gemManager: GemManager) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (!gemManager.hasPlacedGems()) return
        if (movesOrCrushesGem(event.blocks, event.direction) ||
            gemManager.isGemBlock(event.block.getRelative(event.direction))
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!gemManager.hasPlacedGems()) return
        if (movesOrCrushesGem(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        stripGemBlocks(event.blockList())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        stripGemBlocks(event.blockList())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        cancelIfGem(event, event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        cancelIfGem(event, event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        cancelIfGem(event, event.block)
    }

    /** 末影人搬走、下落方块覆盖、凋灵/劫掠兽破坏，都是这个事件。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        cancelIfGem(event, event.block)
    }

    /** 水/岩浆冲走宝石——宝石材质允许是火把、地毯这类非实体方块，很容易被冲掉。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFromTo(event: BlockFromToEvent) {
        cancelIfGem(event, event.toBlock)
    }

    /** 树木/菌类生长覆盖宝石坐标。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        if (!gemManager.hasPlacedGems()) return
        event.blocks.removeIf { gemManager.isGemBlock(it.block) }
    }

    private fun movesOrCrushesGem(blocks: List<Block>, direction: BlockFace): Boolean {
        for (block in blocks) {
            if (gemManager.isGemBlock(block)) return true
            if (gemManager.isGemBlock(block.getRelative(direction))) return true
        }
        return false
    }

    private fun stripGemBlocks(blocks: MutableList<Block>) {
        if (!gemManager.hasPlacedGems()) return
        // 从爆炸方块列表里摘掉即可：既不会被破坏，也不会掉落成普通材质。
        blocks.removeIf { gemManager.isGemBlock(it) }
    }

    private fun cancelIfGem(event: Cancellable, block: Block?) {
        if (!gemManager.hasPlacedGems()) return
        if (gemManager.isGemBlock(block)) {
            event.isCancelled = true
        }
    }
}
