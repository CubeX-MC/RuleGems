package org.cubexmc.listeners;

import org.bukkit.event.EventHandler;
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
