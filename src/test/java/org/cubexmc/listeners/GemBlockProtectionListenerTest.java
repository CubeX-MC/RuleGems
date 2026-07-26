package org.cubexmc.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;

class GemBlockProtectionListenerTest {

    private final GemManager gemManager = mock(GemManager.class);
    private final GemBlockProtectionListener listener = new GemBlockProtectionListener(gemManager);

    @Test
    void pistonIsCancelledWhenItWouldPushAGemBlock() {
        Block gem = mock(Block.class);
        Block piston = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);
        when(gemManager.isGemBlock(gem)).thenReturn(true);

        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        when(event.getBlocks()).thenReturn(Collections.singletonList(gem));
        when(event.getDirection()).thenReturn(BlockFace.EAST);
        when(event.getBlock()).thenReturn(piston);

        listener.onPistonExtend(event);

        verify(event).setCancelled(true);
    }

    @Test
    void pistonIsCancelledWhenAPushedBlockWouldLandOnAGemLocation() {
        // proximity_display 模式下宝石所在方块是空气，只有检查落点才能挡住"把宝石埋掉"。
        Block pushed = mock(Block.class);
        Block gemLocation = mock(Block.class);
        Block piston = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);
        when(gemManager.isGemBlock(pushed)).thenReturn(false);
        when(pushed.getRelative(BlockFace.UP)).thenReturn(gemLocation);
        when(gemManager.isGemBlock(gemLocation)).thenReturn(true);

        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        when(event.getBlocks()).thenReturn(Collections.singletonList(pushed));
        when(event.getDirection()).thenReturn(BlockFace.UP);
        when(event.getBlock()).thenReturn(piston);

        listener.onPistonExtend(event);

        verify(event).setCancelled(true);
    }

    @Test
    void pistonIsUntouchedWhenNoGemIsInvolved() {
        Block ordinary = mock(Block.class);
        Block piston = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);

        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        when(event.getBlocks()).thenReturn(Collections.singletonList(ordinary));
        when(event.getDirection()).thenReturn(BlockFace.WEST);
        when(event.getBlock()).thenReturn(piston);

        listener.onPistonExtend(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void explosionDropsGemBlocksFromTheAffectedList() {
        Block gem = mock(Block.class);
        Block rubble = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);
        when(gemManager.isGemBlock(gem)).thenReturn(true);

        List<Block> affected = new ArrayList<>(Arrays.asList(gem, rubble));
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.blockList()).thenReturn(affected);

        listener.onEntityExplode(event);

        // 从列表里摘掉即可：方块既不被破坏，也不会掉落成普通宝石材质。
        assertEquals(Collections.singletonList(rubble), affected);
    }

    @Test
    void fireCannotBurnAGemBlock() {
        Block gem = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);
        when(gemManager.isGemBlock(gem)).thenReturn(true);

        BlockBurnEvent event = mock(BlockBurnEvent.class);
        when(event.getBlock()).thenReturn(gem);

        listener.onBlockBurn(event);

        verify(event).setCancelled(true);
    }

    @Test
    void flowingLiquidCannotWashAwayAGemBlock() {
        Block gem = mock(Block.class);
        when(gemManager.hasPlacedGems()).thenReturn(true);
        when(gemManager.isGemBlock(gem)).thenReturn(true);

        BlockFromToEvent event = mock(BlockFromToEvent.class);
        when(event.getToBlock()).thenReturn(gem);

        listener.onBlockFromTo(event);

        verify(event).setCancelled(true);
    }
}
