package org.cubexmc.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.cubexmc.model.GemDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemScatterServiceTest {

    @Mock private GemStateManager stateManager;
    @Mock private GemPlacementManager placementManager;
    @Mock private GemDefinitionParser gemParser;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private org.cubexmc.utils.EffectUtils effectUtils;
    @Mock private LanguageManager languageManager;
    @Mock private Runnable resetOwnershipStateAction;
    @Mock private Runnable saveAction;

    private MockedStatic<Bukkit> mockedBukkit;
    private GemScatterService service;

    @BeforeEach
    void setUp() {
        mockedBukkit = mockStatic(Bukkit.class);
        service = new GemScatterService(
                stateManager, placementManager, gemParser, gameplayConfig, effectUtils, languageManager,
                resetOwnershipStateAction, saveAction);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void scatterClearsOldStateAndRecreatesConfiguredGems() {
        UUID existingGem = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID existingIce = UUID.fromString("10000000-0000-0000-0000-000000000002");
        Location existingLoc = mock(Location.class);
        Map<Location, UUID> snapshot = new HashMap<>();
        snapshot.put(existingLoc, existingGem);
        when(stateManager.snapshotPlacedGems()).thenReturn(snapshot);
        Map<UUID, String> keySnapshot = new HashMap<>();
        keySnapshot.put(existingGem, "fire");
        keySnapshot.put(existingIce, "ice");
        when(stateManager.snapshotGemKeys()).thenReturn(keySnapshot);

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack gemItem = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[]{gemItem});
        when(stateManager.isRuleGem(gemItem)).thenReturn(true);
        mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.singleton(player));

        GemDefinition fire = mock(GemDefinition.class);
        when(fire.getCount()).thenReturn(2);
        when(fire.getGemKey()).thenReturn("fire");
        when(fire.getOnScatter()).thenReturn(null);
        GemDefinition ice = mock(GemDefinition.class);
        when(ice.getCount()).thenReturn(1);
        when(ice.getGemKey()).thenReturn("ice");
        when(ice.getOnScatter()).thenReturn(null);
        when(gemParser.getGemDefinitions()).thenReturn(Arrays.asList(fire, ice));

        service.scatterGems();

        InOrder escapeResetOrder = inOrder(placementManager, languageManager);
        escapeResetOrder.verify(placementManager).resetEscapeStateForScatter();
        escapeResetOrder.verify(languageManager).logMessage("scatter_start");
        verify(placementManager, times(1)).unplaceRuleGem(existingLoc, existingGem);
        verify(stateManager, times(1)).clearPlacedMappings();
        verify(stateManager, times(1)).clearHolderMappings();
        verify(stateManager, times(1)).clearGemKeys();
        verify(resetOwnershipStateAction, times(1)).run();
        verify(stateManager, times(3)).setGemKey(any(UUID.class), any(String.class));
        ArgumentCaptor<UUID> placedIds = ArgumentCaptor.forClass(UUID.class);
        verify(placementManager, times(3)).randomPlaceGem(placedIds.capture());
        assertTrue(placedIds.getAllValues().contains(existingGem));
        assertTrue(placedIds.getAllValues().contains(existingIce));
        assertEquals(3, placedIds.getAllValues().stream().distinct().count());
        verify(inventory, times(1)).remove(gemItem);
        verify(languageManager, times(1)).logMessage("scatter_start");
        verify(languageManager, times(1)).logMessage(eq("gems_scattered"), anyMap());
        verify(saveAction, times(1)).run();
    }

    @Test
    void scatterNeverCreatesUnkeyedGemsWithoutDefinitions() {
        when(stateManager.snapshotPlacedGems()).thenReturn(Collections.emptyMap());
        mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());
        when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

        service.scatterGems();

        verify(placementManager).resetEscapeStateForScatter();
        verify(stateManager, never()).setGemKey(any(UUID.class), any(String.class));
        verify(placementManager, never()).randomPlaceGem(any(UUID.class));
        verify(languageManager).logMessage(eq("gems_scattered"), anyMap());
        verify(saveAction).run();
    }
}
