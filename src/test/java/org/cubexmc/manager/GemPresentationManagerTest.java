package org.cubexmc.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.cubexmc.RuleGems;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemPresentationManagerTest {

    @Mock private RuleGems plugin;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private GemStateManager stateManager;
    @Mock private World world;
    @Mock private Block block;
    @Mock private Logger logger;

    private UUID gemId;
    private Location location;

    @BeforeEach
    void setUp() {
        gemId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        location = new Location(world, 10.0, 64.0, 20.0);
        when(plugin.getName()).thenReturn("RuleGems");
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
    }

    @Test
    void synchronizeSwitchesExistingLocationBetweenDisplayAndBlockWithoutMovingIt() {
        when(plugin.getLogger()).thenReturn(logger);
        when(stateManager.getGemMaterial(gemId)).thenReturn(Material.DIAMOND_BLOCK);
        GemPresentationManager manager = new GemPresentationManager(plugin, gameplayConfig, stateManager);

        try (MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.regionRun(
                    eq(plugin), any(Location.class), any(Runnable.class), eq(0L), eq(-1L)))
                    .thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(2).run();
                        return new Object();
                    });

            when(gameplayConfig.getGemPresentationMode()).thenReturn(GemPresentationMode.PROXIMITY_DISPLAY);
            manager.synchronizePlacedGems(Collections.singletonMap(gemId, location));

            when(gameplayConfig.getGemPresentationMode()).thenReturn(GemPresentationMode.BLOCK);
            manager.synchronizePlacedGems(Collections.singletonMap(gemId, location));

            InOrder order = inOrder(block);
            order.verify(block).setType(Material.AIR);
            order.verify(block).setType(Material.DIAMOND_BLOCK);
            verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
        }
    }

    @Test
    void delayedCleanupDoesNotEraseReplacementAtReusedLocation() {
        Map<Location, UUID> occupiedLocations = new HashMap<>();
        occupiedLocations.put(location, gemId);
        when(stateManager.getLocationToGemUuid()).thenReturn(occupiedLocations);
        when(gameplayConfig.getGemPresentationMode()).thenReturn(GemPresentationMode.PROXIMITY_DISPLAY);
        GemPresentationManager manager = new GemPresentationManager(plugin, gameplayConfig, stateManager);

        try (MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.regionRun(
                    eq(plugin), any(Location.class), any(Runnable.class), eq(0L), eq(-1L)))
                    .thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(2).run();
                        return new Object();
                    });

            manager.renderPlacedGem(gemId, location, Material.DIAMOND_BLOCK);
            manager.detachPlacedGem(gemId, location);
            manager.renderPlacedGem(gemId, location, Material.DIAMOND_BLOCK);
            manager.clearLocationIfUnoccupied(location);

            verify(block, times(2)).setType(Material.AIR);
        }
    }
}
