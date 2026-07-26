package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.cubexmc.RuleGems;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 回归防护：玩家造成的宝石位置一律不校验、不搬动。
 *
 * 曾经把 random_place_range 当成"宝石唯一合法区域"接进了 placeRuleGemInternal，
 * 于是玩家在范围外丢弃或下线时，宝石会被静默传送回出生点附近——玩家侧表现为"宝石消失"。
 * random_place_range 只用于随机散落取点，不该约束玩家行为。
 */
@ExtendWith(MockitoExtension.class)
class GemPlayerPositionRegressionTest {

    private static final UUID GEM_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID WORLD_ID = UUID.fromString("70000000-0000-0000-0000-000000000007");

    @Mock private RuleGems plugin;
    @Mock private ConfigManager configManager;
    @Mock private GemDefinitionParser gemParser;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private EffectUtils effectUtils;
    @Mock private LanguageManager languageManager;
    @Mock private PluginManager pluginManager;
    @Mock private World world;
    @Mock private WorldBorder border;
    @Mock private Block block;
    @Mock private Player player;
    @Mock private PlayerInventory inventory;

    private MockedStatic<Bukkit> mockedBukkit;
    private MockedStatic<SchedulerUtil> mockedSchedulerUtil;

    /** 远离 random_place_range 的位置：玩家实际活动的地方。 */
    private final Location farAwayBlock = new Location(null, 5000, 70, -8000);

    @BeforeEach
    void setUp() {
        mockedBukkit = mockStatic(Bukkit.class);
        mockedSchedulerUtil = mockStatic(SchedulerUtil.class);

        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemPlayerPositionRegressionTest"));
        mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        mockedBukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        lenient().when(world.getUID()).thenReturn(WORLD_ID);
        lenient().when(world.getName()).thenReturn("world");
        mockedBukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
        mockedBukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

        // regionRun 在真实的 Paper 环境下（delay=0 且在主线程）是内联执行的，这里照样内联，
        // 否则放置逻辑根本跑不到。
        mockedSchedulerUtil
                .when(() -> SchedulerUtil.regionRun(any(), any(Location.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return null;
                });

        farAwayBlock.setWorld(world);
        lenient().when(world.getWorldBorder()).thenReturn(border);
        lenient().when(border.isInside(any(Location.class))).thenReturn(true);
        lenient().when(world.getMinHeight()).thenReturn(-64);
        lenient().when(world.getMaxHeight()).thenReturn(320);
        lenient().when(world.getBlockAt(any(Location.class))).thenReturn(block);
        lenient().when(block.getType()).thenReturn(Material.AIR);
        // 每次返回副本：垂直搜索会就地 add(0,1,0)，共用同一个实例会把它改坏。
        lenient().when(block.getLocation()).thenAnswer(invocation -> farAwayBlock.clone());

        lenient().when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
        // requiredCount 必须 > 0，否则 placeRuleGemInternal 的数量上限检查会直接跳过放置。
        lenient().when(gemParser.getRequiredCount()).thenReturn(1);
        lenient().when(gameplayConfig.getGemPresentationMode()).thenReturn(GemPresentationMode.BLOCK);
        lenient().when(gameplayConfig.isGemEscapeEnabled()).thenReturn(false);
        // 一个远在天边的散落范围：如果放置逻辑还在拿它当硬约束，宝石就会被搬到这里面去。
        lenient().when(gameplayConfig.getRandomPlaceCorner1()).thenReturn(new Location(world, -100, 64, -100));
        lenient().when(gameplayConfig.getRandomPlaceCorner2()).thenReturn(new Location(world, 100, 64, 100));

        // 放置成功会触发存盘；给它一份可写的配置，否则同步存盘路径会 NPE。
        lenient().when(configManager.getGemsData()).thenReturn(new YamlConfiguration());

        lenient().when(player.getName()).thenReturn("Tester");
        lenient().when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000123"));
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(player.getLocation()).thenReturn(farAwayBlock.clone().add(0.5, 0.0, 0.5));
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
    void droppingAGemFarOutsideTheScatterRangeLeavesItWhereItLanded() {
        GemManager manager = createManagerHoldingGem();
        Item itemEntity = mock(Item.class);
        ItemStack gemItem = ruleGemItem();
        Location dropLocation = farAwayBlock.clone().add(0.5, 0.0, 0.5);

        manager.handleGemDrop(player, dropLocation, itemEntity, gemItem);

        Location bound = manager.getStateManager().getGemLocation(GEM_ID);
        assertNotNull(bound, "宝石必须留在世界里");
        assertEquals(5000, bound.getBlockX());
        assertEquals(-8000, bound.getBlockZ());
    }

    @Test
    void quittingWithAGemFarOutsideTheScatterRangeLeavesItAtTheLogoutSpot() {
        GemManager manager = createManagerHoldingGem();
        ItemStack gemItem = ruleGemItem();
        when(inventory.getContents()).thenReturn(new ItemStack[] { gemItem });

        manager.handlePlayerQuit(player);

        Location bound = manager.getStateManager().getGemLocation(GEM_ID);
        assertNotNull(bound, "宝石必须留在下线位置");
        assertEquals(5000, bound.getBlockX());
        assertEquals(-8000, bound.getBlockZ());
    }

    @Test
    void quittingWithAGemHiddenInAShulkerRemovesTheNestedOriginalBeforePlacement() {
        GemManager manager = createManagerHoldingGem();
        ItemStack gemItem = ruleGemItem();
        ItemStack shulker = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        PersistentDataContainer carrierPdc = mock(PersistentDataContainer.class);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory nestedInventory = mock(Inventory.class);

        when(shulker.getType()).thenReturn(Material.SHULKER_BOX);
        when(shulker.hasItemMeta()).thenReturn(true);
        when(shulker.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(carrierPdc);
        when(carrierPdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
        when(meta.hasBlockState()).thenReturn(true);
        when(meta.getBlockState()).thenReturn(state);
        when(state.getInventory()).thenReturn(nestedInventory);
        when(nestedInventory.getContents()).thenReturn(new ItemStack[] { gemItem });
        when(inventory.getContents()).thenReturn(new ItemStack[] { shulker });

        manager.handlePlayerQuit(player);

        assertNotNull(manager.getStateManager().getGemLocation(GEM_ID));
        verify(nestedInventory).setItem(0, null);
        verify(meta).setBlockState(state);
        verify(inventory).setItem(0, shulker);
    }

    private GemManager createManagerHoldingGem() {
        GemManager manager = new GemManager(
                plugin, configManager, gemParser, gameplayConfig, effectUtils, languageManager);
        manager.getStateManager().getGemUuidToKey().put(GEM_ID, "fire");
        manager.getStateManager().setGemHolder(GEM_ID, player);
        return manager;
    }

    private ItemStack ruleGemItem() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(GEM_ID.toString());
        return item;
    }
}
