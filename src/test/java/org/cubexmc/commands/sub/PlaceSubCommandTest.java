package org.cubexmc.commands.sub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class PlaceSubCommandTest {

    private static final UUID GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock private GemManager gemManager;
    @Mock private LanguageManager languageManager;
    @Mock private Player player;
    @Mock private World world;
    @Mock private Chunk chunk;
    @Mock private Block targetBlock;

    private PlaceSubCommand command;
    private Location playerLocation;

    @BeforeEach
    void setUp() {
        command = new PlaceSubCommand(gemManager, languageManager);
        playerLocation = new Location(world, 12.5, 65.0, -3.25);

        lenient().when(player.getWorld()).thenReturn(world);
        lenient().when(player.getLocation()).thenReturn(playerLocation);
        lenient().when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        lenient().when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        lenient().when(chunk.isLoaded()).thenReturn(true);
        lenient().when(world.getBlockAt(any(Location.class))).thenReturn(targetBlock);
        lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(targetBlock);
        lenient().when(targetBlock.getType()).thenReturn(Material.AIR);

        lenient().when(gemManager.resolveGemIdentifier("fire")).thenReturn(GEM_ID);
        lenient().when(gemManager.getGemMaterial(GEM_ID)).thenReturn(Material.DIAMOND_BLOCK);
        lenient().when(gemManager.isSupportRequired(Material.DIAMOND_BLOCK)).thenReturn(false);
    }

    @Test
    void omittedCoordinatesUsePlayerLocation() {
        command.execute(player, new String[] { "fire" });

        Location placed = capturedPlacement();
        assertEquals(playerLocation.getX(), placed.getX());
        assertEquals(playerLocation.getY(), placed.getY());
        assertEquals(playerLocation.getZ(), placed.getZ());
    }

    @Test
    void tildeCoordinatesUsePlayerLocation() {
        command.execute(player, new String[] { "fire", "~", "~", "~" });

        Location placed = capturedPlacement();
        assertEquals(playerLocation.getX(), placed.getX());
        assertEquals(playerLocation.getY(), placed.getY());
        assertEquals(playerLocation.getZ(), placed.getZ());
    }

    @Test
    void partialCoordinatesShowUsage() {
        command.execute(player, new String[] { "fire", "~" });

        verify(languageManager).sendMessage(player, "command.place.usage");
        verify(gemManager, never()).forcePlaceGem(any(), any());
    }

    @Test
    void occupiedTargetDoesNotReplaceExistingBlock() {
        when(targetBlock.getType()).thenReturn(Material.STONE);

        command.execute(player, new String[] { "fire", "10", "64", "10" });

        verify(languageManager).sendMessage(player, "command.place.failed_occupied");
        verify(gemManager, never()).forcePlaceGem(any(), any());
    }

    private Location capturedPlacement() {
        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(gemManager).forcePlaceGem(eq(GEM_ID), captor.capture());
        return captor.getValue();
    }
}
