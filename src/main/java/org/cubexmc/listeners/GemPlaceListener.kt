package org.cubexmc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.cubexmc.manager.GemManager

class GemPlaceListener(private val gemManager: GemManager) : Listener {
    // 宝石被设计为"无视领地保护"：任何玩家都能在受保护区域内拾取/放置宝石，以保证权力能够流转。
    //
    // 关键点：必须 ignoreCancelled = false。
    //   领地/保护插件（Residence、Lands 等，通常在 NORMAL/HIGH 优先级）会在玩家无权限时取消
    //   BlockPlace/BlockBreak 事件。我们在 HIGHEST（即在它们之后）仍然接收该事件，并在
    //   handleGemBlockPlace / handleGemBlockBreak 中针对"宝石交互"调用 setCancelled(false) 强制放行。
    //   若改用 ignoreCancelled = true，被保护插件取消的事件将不再传到这里，宝石就无法在领地内拾取/放置。
    // 不使用 MONITOR：因为我们需要修改事件的取消状态，违反 MONITOR 的只读语义。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (gemManager.blockPlacementConflictsWithDisplayedGem(event.player, event.blockPlaced.location)) {
            event.isCancelled = true
            return
        }
        gemManager.handleGemBlockPlace(event.player, event.itemInHand, event.blockPlaced, event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockBreak(event: BlockBreakEvent) {
        gemManager.handleGemBlockBreak(event.player, event.block, event)
    }

    @EventHandler
    fun onBlockDamage(event: BlockDamageEvent) {
        gemManager.handleBlockDamage(event)
    }

    // 只在 HIGHEST 拦一次。曾经额外在 LOWEST 拦过一道（想抢在商店类插件之前），但
    // PlayerInteractEvent.isCancelled() 的定义就是 useInteractedBlock() == DENY，
    // 在 LOWEST 设 DENY 等于把每一次"手持宝石右键方块"都标记成已取消，会污染后续所有插件的判断。
    // 商店插件要靠专门的 API 钩子处理，不能靠污染事件状态。
    //
    // 与 onBlockPlace/onBlockBreak 对称：BlockPlace/BlockBreak 的绕过覆盖不了"音符盒、按钮、拉杆、
    // 唱片机、容器"等可交互方块——保护插件（Residence/Lands）对这类材质是在 PlayerInteract 层取消的。
    // 同样必须 ignoreCancelled = false 才能接到被保护插件取消的事件，并在 HIGHEST(即它们之后)放行。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        gemManager.handleGemBlockInteract(event)
    }
}
