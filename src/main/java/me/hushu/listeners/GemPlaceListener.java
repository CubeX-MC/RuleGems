package me.hushu.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockDamageEvent;

import me.hushu.PowerGem;
import me.hushu.manager.GemManager;

public class GemPlaceListener implements Listener {
    private final GemManager gemManager;

    public GemPlaceListener(PowerGem plugin, GemManager gemManager) {
        this.gemManager = gemManager;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        gemManager.onGemPlaced(event);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        gemManager.onGemBroken(event);
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent event) {
        gemManager.onGemDamage(event);
    }
}
