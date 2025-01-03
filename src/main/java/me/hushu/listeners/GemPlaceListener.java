package me.hushu.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import me.hushu.PowerGem;
import me.hushu.manager.GemManager;

public class GemPlaceListener implements Listener {
    private final PowerGem plugin;
    private final GemManager gemManager;

    public GemPlaceListener(PowerGem plugin, GemManager gemManager) {
        this.plugin = plugin;
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
}
