package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.PlayerInventory;
import org.cubexmc.RuleGems;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemManagerEscapeIntegrationTest {

    private static final UUID GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock private RuleGems plugin;
    @Mock private ConfigManager configManager;
    @Mock private GemDefinitionParser gemParser;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private EffectUtils effectUtils;
    @Mock private LanguageManager languageManager;
    @Mock private org.bukkit.entity.Player player;

    private MockedStatic<Bukkit> mockedBukkit;
    private MockedStatic<SchedulerUtil> mockedSchedulerUtil;

    @BeforeEach
    void setUp() {
        mockedBukkit = mockStatic(Bukkit.class);
        mockedSchedulerUtil = mockStatic(SchedulerUtil.class);

        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGemsEscapeIntegrationTest"));
        lenient().when(plugin.getPowerStructureManager()).thenReturn(null);
        lenient().when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
        mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        if (mockedSchedulerUtil != null) {
            mockedSchedulerUtil.close();
        }
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void saveReplacesEscapeStateRootWithCoordinatorSnapshot() {
        YamlConfiguration gemsData = new YamlConfiguration();
        gemsData.set("escape-state.legacy", true);
        when(configManager.getGemsData()).thenReturn(gemsData);

        try (MockedConstruction<GemPlacementManager> construction = mockPlacementManager()) {
            GemManager manager = createManager();
            GemPlacementManager placementManager = construction.constructed().get(0);
            doAnswer(invocation -> {
                Map<String, Object> snapshot = invocation.getArgument(0);
                snapshot.put("escape-state.next_cycle_at", 42_000L);
                return null;
            }).when(placementManager).populateEscapeSaveSnapshot(anyMap());

            manager.saveGemsSync();

            verify(placementManager).populateEscapeSaveSnapshot(anyMap());
            verify(configManager).saveGemData(gemsData);
            assertTrue(gemsData.contains("escape-state"));
            assertEquals(42_000L, gemsData.getLong("escape-state.next_cycle_at"));
            assertFalse(gemsData.contains("escape-state.legacy"));
        }
    }

    @Test
    void loadPreparesThenLoadsAndInitializesEscapeLifecycle() {
        YamlConfiguration gemsData = new YamlConfiguration();
        when(configManager.readGemsData()).thenReturn(gemsData);

        try (MockedConstruction<GemPlacementManager> construction = mockPlacementManager()) {
            GemManager manager = createManager();
            GemPlacementManager placementManager = construction.constructed().get(0);

            manager.loadGems();

            InOrder lifecycle = inOrder(placementManager);
            lifecycle.verify(placementManager).prepareEscapeReload();
            lifecycle.verify(placementManager).shutdownPresentation();
            lifecycle.verify(placementManager).loadEscapeState(gemsData);
            lifecycle.verify(placementManager).initializeEscapeTasks();
        }
    }

    @Test
    void inventoryFullPickupStillReleasesEscapeTransition() {
        try (MockedConstruction<GemPlacementManager> construction = mockPlacementManager()) {
            GemManager manager = createManager();
            GemPlacementManager placementManager = construction.constructed().get(0);
            when(placementManager.tryBeginPickup(GEM_ID)).thenReturn(true);

            World world = mock(World.class);
            Location location = new Location(world, 8, 64, 8);
            manager.getStateManager().bindPlacedGem(location, GEM_ID);

            Block block = mock(Block.class);
            when(block.getLocation()).thenReturn(location);
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.firstEmpty()).thenReturn(-1);
            BlockBreakEvent event = mock(BlockBreakEvent.class);

            manager.handleGemBlockBreak(player, block, event);

            InOrder pickupTransition = inOrder(placementManager);
            pickupTransition.verify(placementManager).tryBeginPickup(GEM_ID);
            pickupTransition.verify(placementManager).endPickup(GEM_ID);
            verify(event).setCancelled(true);
        }
    }

    private MockedConstruction<GemPlacementManager> mockPlacementManager() {
        return mockConstruction(GemPlacementManager.class);
    }

    private GemManager createManager() {
        return new GemManager(plugin, configManager, gemParser, gameplayConfig, effectUtils, languageManager);
    }
}
