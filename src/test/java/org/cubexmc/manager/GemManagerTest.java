package org.cubexmc.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.cubexmc.RuleGems;
import org.cubexmc.event.GemRedeemEvent;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.model.RedeemIngredient;
import org.cubexmc.model.RedeemRecipe;
import org.cubexmc.model.RedeemRequirements;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GemManagerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ICE_GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID OATH_GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID FIRE_GEM_ID_2 = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID FIRE_GEM_ID_3 = UUID.fromString("10000000-0000-0000-0000-000000000005");

    @Mock
    private RuleGems plugin;
    @Mock
    private ConfigManager configManager;
    @Mock
    private GemDefinitionParser gemParser;
    @Mock
    private GameplayConfig gameplayConfig;
    @Mock
    private EffectUtils effectUtils;
    @Mock
    private LanguageManager languageManager;
    @Mock
    private org.bukkit.entity.Player player;
    @Mock
    private Block block;
    @Mock
    private BlockPlaceEvent blockPlaceEvent;
    @Mock
    private PluginManager pluginManager;

    private MockedStatic<Bukkit> mockedBukkit;
    private MockedStatic<SchedulerUtil> mockedSchedulerUtil;
    private World world;
    private Location altarLocation;
    private GemDefinition fireGem;
    private GemDefinition iceGem;

    @BeforeEach
    void setUp() {
        mockedBukkit = mockStatic(Bukkit.class);
        mockedSchedulerUtil = mockStatic(SchedulerUtil.class);

        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGemsTest"));
        lenient().when(plugin.getPowerStructureManager()).thenReturn(null);

        world = mock(World.class);
        altarLocation = new Location(world, 10, 64, 10);
        fireGem = new GemDefinition.Builder("fire")
                .material(Material.DIAMOND_BLOCK)
                .displayName("Fire Gem")
                .powerStructure(new PowerStructure())
                .mutualExclusive(Collections.singletonList("ice"))
                .altarLocation(altarLocation)
                .randomPlaceCorner1(new Location(world, 20, 64, 20))
                .randomPlaceCorner2(new Location(world, 22, 64, 22))
                .build();
        iceGem = new GemDefinition.Builder("ice")
                .material(Material.EMERALD_BLOCK)
                .displayName("Ice Gem")
                .powerStructure(new PowerStructure())
                .randomPlaceCorner1(new Location(world, 30, 64, 30))
                .randomPlaceCorner2(new Location(world, 32, 64, 32))
                .build();

        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        lenient().when(gameplayConfig.isRedeemEnabled()).thenReturn(true);
        lenient().when(gameplayConfig.isPlaceRedeemEnabled()).thenReturn(true);
        lenient().when(gameplayConfig.getPlaceRedeemRadius()).thenReturn(3);

        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(player.getName()).thenReturn("Tester");
        lenient().when(player.getLocation()).thenReturn(altarLocation);
        lenient().when(block.getLocation()).thenReturn(altarLocation);

        mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        mockedBukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
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
    void altarRedeemConflictCancelsOriginalBlockPlaceAndKeepsStateUnchanged() {
        GemManager manager = createManagerWithHeldFireGem();
        manager.getPermissionManager().getPlayerUuidToRedeemedKeys().put(PLAYER_ID, Set.of("ice"));

        manager.handleGemBlockPlace(player, ruleGemItem(), block, blockPlaceEvent);

        verify(blockPlaceEvent).setCancelled(true);
        verify(pluginManager, never()).callEvent(any(Event.class));
        assertFalse(manager.getPermissionManager().getGemIdToRedeemer().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
    }

    @Test
    void cancelledAltarRedeemEventCancelsOriginalBlockPlaceAndKeepsStateUnchanged() {
        GemManager manager = createManagerWithHeldFireGem();
        doAnswer(invocation -> {
            GemRedeemEvent event = invocation.getArgument(0);
            event.setCancelled(true);
            return null;
        }).when(pluginManager).callEvent(any(GemRedeemEvent.class));

        manager.handleGemBlockPlace(player, ruleGemItem(), block, blockPlaceEvent);

        verify(blockPlaceEvent).setCancelled(true);
        assertFalse(manager.getPermissionManager().getGemIdToRedeemer().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
    }

    @Test
    void successfulAltarRedeemCancelsOriginalBlockPlaceBeforeManualStateMigration() {
        GemManager manager = createManagerWithHeldFireGem();

        manager.handleGemBlockPlace(player, ruleGemItem(), block, blockPlaceEvent);

        verify(blockPlaceEvent).setCancelled(true);
        assertEquals(PLAYER_ID, manager.getPermissionManager().getGemIdToRedeemer().get(GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
    }

    @Test
    void redeemAllCancelledEventLeavesFullSetStateUnchanged() {
        GemManager manager = createManagerWithHeldFullSet();
        UUID previousOwner = UUID.fromString("00000000-0000-0000-0000-000000000099");
        manager.getPermissionManager().setFullSetOwner(previousOwner);
        doAnswer(invocation -> {
            GemRedeemEvent event = invocation.getArgument(0);
            if (event.getContext() == GemRedeemEvent.RedeemContext.FULL_SET && "ice".equals(event.getGemKey())) {
                event.setCancelled(true);
            }
            return null;
        }).when(pluginManager).callEvent(any(GemRedeemEvent.class));

        assertTrue(manager.redeemAll(player));

        assertEquals(previousOwner, manager.getPermissionManager().getFullSetOwner());
        assertTrue(manager.getPermissionManager().getGemIdToRedeemer().isEmpty());
        assertFalse(manager.getPermissionManager().getPlayerUuidToRedeemedKeys().containsKey(PLAYER_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(ICE_GEM_ID));
    }

    @Test
    void redeemAllCommitsOnlyAfterAllEventsPass() {
        GemManager manager = createManagerWithHeldFullSet();

        assertTrue(manager.redeemAll(player));

        assertEquals(PLAYER_ID, manager.getPermissionManager().getFullSetOwner());
        assertEquals(PLAYER_ID, manager.getPermissionManager().getGemIdToRedeemer().get(GEM_ID));
        assertEquals(PLAYER_ID, manager.getPermissionManager().getGemIdToRedeemer().get(ICE_GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(ICE_GEM_ID));
        assertTrue(manager.getPermissionManager().getPlayerUuidToRedeemedKeys().get(PLAYER_ID).contains("fire"));
        assertTrue(manager.getPermissionManager().getPlayerUuidToRedeemedKeys().get(PLAYER_ID).contains("ice"));
    }

    @Test
    void handRedeemMissingHeldRequirementRejectsBeforeEvent() {
        fireGem = fireGemWithRequirements(new RedeemRequirements(
                List.of("oath"), List.of(), List.of(), List.of(), 0, List.of(), false, null));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID));
        GemManager manager = createManagerWithHeldFireGem();

        assertFalse(manager.redeemGemInHand(player));

        verify(languageManager).sendMessage(eq(player), eq("command.redeem.requirements_missing_held"), anyMap());
        verify(pluginManager, never()).callEvent(any(GemRedeemEvent.class));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
    }

    @Test
    void handRedeemConsumesRequirementGemOnlyAfterEventPasses() {
        fireGem = fireGemWithRequirements(new RedeemRequirements(
                List.of(), List.of(), List.of("oath"), List.of(), 0, List.of(), false, null));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(OATH_GEM_ID));
        GemManager manager = createManagerWithHeldFireGem();
        manager.getStateManager().getGemUuidToKey().put(OATH_GEM_ID, "oath");
        manager.getStateManager().getGemUuidToHolder().put(OATH_GEM_ID, player);

        assertTrue(manager.redeemGemInHand(player));

        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(OATH_GEM_ID));
    }

    @Test
    void cancelledRedeemEventDoesNotConsumeRequirementGem() {
        fireGem = fireGemWithRequirements(new RedeemRequirements(
                List.of(), List.of(), List.of("oath"), List.of(), 0, List.of(), false, null));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(OATH_GEM_ID));
        GemManager manager = createManagerWithHeldFireGem();
        manager.getStateManager().getGemUuidToKey().put(OATH_GEM_ID, "oath");
        manager.getStateManager().getGemUuidToHolder().put(OATH_GEM_ID, player);
        doAnswer(invocation -> {
            GemRedeemEvent event = invocation.getArgument(0);
            event.setCancelled(true);
            return null;
        }).when(pluginManager).callEvent(any(GemRedeemEvent.class));

        assertTrue(manager.redeemGemInHand(player));

        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(OATH_GEM_ID));
    }

    @Test
    void selfConsumeAmountCountsTargetGemAndConsumesOnlyExtraInstances() {
        fireGem = fireGemWithRequirements(requirementsWithRecipes(List.of(new RedeemRecipe(
                List.of(), List.of(new RedeemIngredient("fire", 3)), List.of(), List.of(), 0, List.of()))));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(FIRE_GEM_ID_2), ruleGemItem(FIRE_GEM_ID_3));
        GemManager manager = createManagerWithHeldFireGem();
        addHeldGem(manager, FIRE_GEM_ID_2, "fire");
        addHeldGem(manager, FIRE_GEM_ID_3, "fire");

        assertTrue(manager.redeemGemInHand(player));

        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(FIRE_GEM_ID_2));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(FIRE_GEM_ID_3));
    }

    @Test
    void selfConsumeAmountRejectsWhenTargetPlusInventoryIsTooSmall() {
        fireGem = fireGemWithRequirements(requirementsWithRecipes(List.of(new RedeemRecipe(
                List.of(), List.of(new RedeemIngredient("fire", 3)), List.of(), List.of(), 0, List.of()))));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(FIRE_GEM_ID_2));
        GemManager manager = createManagerWithHeldFireGem();
        addHeldGem(manager, FIRE_GEM_ID_2, "fire");

        assertFalse(manager.redeemGemInHand(player));

        verify(languageManager).sendMessage(eq(player), eq("command.redeem.requirements_missing_consumed"), anyMap());
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(FIRE_GEM_ID_2));
    }

    @Test
    void anyOfUsesFirstSatisfiableRecipe() {
        fireGem = fireGemWithRequirements(requirementsWithRecipes(List.of(
                new RedeemRecipe(List.of(), List.of(new RedeemIngredient("fire", 3)), List.of(), List.of(), 0,
                        List.of()),
                new RedeemRecipe(List.of(), List.of(new RedeemIngredient("fire", 1),
                        new RedeemIngredient("oath", 1)), List.of(), List.of(), 0, List.of()))));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(OATH_GEM_ID));
        GemManager manager = createManagerWithHeldFireGem();
        addHeldGem(manager, OATH_GEM_ID, "oath");

        assertTrue(manager.redeemGemInHand(player));

        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(OATH_GEM_ID));
    }

    @Test
    void requiresRedeemedAmountUsesOwnerKeyCounts() {
        fireGem = fireGemWithRequirements(requirementsWithRecipes(List.of(new RedeemRecipe(
                List.of(), List.of(), List.of(new RedeemIngredient("ice", 2)), List.of(), 0, List.of()))));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        setInventory(ruleGemItem(GEM_ID));
        GemManager manager = createManagerWithHeldFireGem();
        manager.getPermissionManager().getOwnerKeyCount().put(PLAYER_ID, new java.util.HashMap<>(Map.of("ice", 2)));

        assertTrue(manager.redeemGemInHand(player));

        assertFalse(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
    }

    @Test
    void redeemAllDoesNotBypassGemRequirementsByDefault() {
        fireGem = fireGemWithRequirements(new RedeemRequirements(
                List.of("oath"), List.of(), List.of(), List.of(), 0, List.of(), false, null));
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fireGem, iceGem));
        GemManager manager = createManagerWithHeldFullSet();

        assertTrue(manager.redeemAll(player));

        verify(languageManager).sendMessage(eq(player), eq("command.redeem.requirements_redeemall_blocked"), anyMap());
        assertFalse(manager.getPermissionManager().getPlayerUuidToRedeemedKeys().containsKey(PLAYER_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(GEM_ID));
        assertTrue(manager.getStateManager().getGemUuidToHolder().containsKey(ICE_GEM_ID));
    }

    private GemManager createManagerWithHeldFireGem() {
        GemManager manager = new GemManager(plugin, configManager, gemParser, gameplayConfig, effectUtils,
                languageManager);
        manager.getStateManager().getGemUuidToKey().put(GEM_ID, "fire");
        manager.getStateManager().getGemUuidToHolder().put(GEM_ID, player);
        return manager;
    }

    private GemManager createManagerWithHeldFullSet() {
        setInventory(ruleGemItem(GEM_ID), ruleGemItem(ICE_GEM_ID));
        when(gameplayConfig.isFullSetGrantsAllEnabled()).thenReturn(true);

        GemManager manager = new GemManager(plugin, configManager, gemParser, gameplayConfig, effectUtils,
                languageManager);
        manager.getStateManager().getGemUuidToKey().put(GEM_ID, "fire");
        manager.getStateManager().getGemUuidToKey().put(ICE_GEM_ID, "ice");
        manager.getStateManager().getGemUuidToHolder().put(GEM_ID, player);
        manager.getStateManager().getGemUuidToHolder().put(ICE_GEM_ID, player);
        return manager;
    }

    private void setInventory(ItemStack... contents) {
        ItemStack[] mutableContents = contents.clone();
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getItemInOffHand()).thenReturn(null);
        when(inventory.getContents()).thenAnswer(invocation -> mutableContents);
        lenient().when(inventory.getItemInMainHand()).thenReturn(mutableContents.length == 0 ? null : mutableContents[0]);
        lenient().doAnswer(invocation -> {
            int slot = invocation.getArgument(0);
            mutableContents[slot] = null;
            return null;
        }).when(inventory).setItem(anyInt(), any(ItemStack.class));
    }

    private GemDefinition fireGemWithRequirements(RedeemRequirements requirements) {
        return new GemDefinition.Builder("fire")
                .material(Material.DIAMOND_BLOCK)
                .displayName("Fire Gem")
                .powerStructure(new PowerStructure())
                .mutualExclusive(Collections.singletonList("ice"))
                .altarLocation(altarLocation)
                .randomPlaceCorner1(new Location(world, 20, 64, 20))
                .randomPlaceCorner2(new Location(world, 22, 64, 22))
                .redeemRequirements(requirements)
                .build();
    }

    private RedeemRequirements requirementsWithRecipes(List<RedeemRecipe> recipes) {
        return new RedeemRequirements(recipes, false, null);
    }

    private void addHeldGem(GemManager manager, UUID gemId, String gemKey) {
        manager.getStateManager().getGemUuidToKey().put(gemId, gemKey);
        manager.getStateManager().getGemUuidToHolder().put(gemId, player);
    }

    private ItemStack ruleGemItem() {
        return ruleGemItem(GEM_ID);
    }

    private ItemStack ruleGemItem(UUID gemId) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);

        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(gemId.toString());
        return item;
    }
}
