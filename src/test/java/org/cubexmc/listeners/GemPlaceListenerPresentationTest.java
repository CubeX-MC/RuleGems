package org.cubexmc.listeners;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemPlaceListenerPresentationTest {

    @Mock private GemManager gemManager;
    @Mock private Player player;
    @Mock private Block block;
    @Mock private Location location;
    @Mock private BlockPlaceEvent event;

    @Test
    void occupiedDisplayLocationRejectsBlockPlacementBeforeNormalGemHandling() {
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(gemManager.blockPlacementConflictsWithDisplayedGem(player, location)).thenReturn(true);

        new GemPlaceListener(gemManager).onBlockPlace(event);

        verify(event).setCancelled(true);
        verify(event, never()).getItemInHand();
    }
}
