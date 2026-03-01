package org.cubexmc.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockDamageEvent;

import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;

public class GemPlaceListener implements Listener {
    private final GemManager gemManager;

    public GemPlaceListener(RuleGems plugin, GemManager gemManager) {
        this.gemManager = gemManager;
    }

    // 使用 HIGHEST 优先级确保在领地等保护插件处理后执行
    // （不使用 MONITOR，因为本方法可能调用 event.setCancelled()，违反 MONITOR 语义）
    // ignoreCancelled = true 确保被取消的事件不会触发宝石放置逻辑
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        gemManager.onGemPlaced(event);
    }

    // 破坏事件同样需要在保护插件之后处理
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        gemManager.onGemBroken(event);
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent event) {
        gemManager.onGemDamage(event);
    }
}
