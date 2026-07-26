package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 覆盖"宝石藏在物品形态的容器里"这条绕过路径：
 * 宝石 -> 潜影盒/收纳袋 -> 整摞丢进箱子，顶层 isRuleGem 判定不出来。
 */
@ExtendWith(MockitoExtension.class)
class GemStateManagerNestedItemTest {

    private static final UUID GEM_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Mock private RuleGems plugin;
    @Mock private GemDefinitionParser gemParser;
    @Mock private LanguageManager languageManager;

    private GemStateManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemStateManagerNestedItemTest"));
        manager = new GemStateManager(plugin, gemParser, languageManager);
    }

    @Test
    void aGemHiddenInsideAShulkerBoxIsStillDetected() {
        ItemStack shulker = shulkerContaining(gemItem());

        assertFalse(manager.isRuleGem(shulker), "顶层判定看不见嵌套的宝石");
        assertTrue(manager.containsGem(shulker), "深检查必须看得见");
        assertEquals(Collections.singletonList(GEM_ID), manager.collectGemIds(shulker));
    }

    @Test
    void anEmptyShulkerBoxIsNotFlaggedButIsStillRecognisedAsAContainerItem() {
        ItemStack shulker = shulkerContaining(null);

        assertFalse(manager.containsGem(shulker));
        // 认成容器物品，才能拦住"把宝石往里塞"的那一次点击。
        assertTrue(manager.isContainerItem(shulker));
    }

    @Test
    void ordinaryItemsAreNeverTreatedAsContainers() {
        ItemStack stone = mock(ItemStack.class);
        lenient().when(stone.getType()).thenReturn(Material.STONE);

        assertFalse(manager.isContainerItem(stone));
        assertFalse(manager.containsGem(stone));
    }

    @Test
    void strippingANestedGemPreservesTheShulkerAndItsOrdinaryContents() {
        ItemStack gem = gemItem();
        ItemStack diamond = mock(ItemStack.class);
        ItemStack shulker = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory inventory = mock(Inventory.class);

        when(shulker.getType()).thenReturn(Material.SHULKER_BOX);
        when(diamond.getType()).thenReturn(Material.DIAMOND);
        when(shulker.hasItemMeta()).thenReturn(true);
        when(shulker.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
        when(meta.hasBlockState()).thenReturn(true);
        when(meta.getBlockState()).thenReturn(state);
        when(state.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[] { gem, diamond });

        GemStateManager.GemRemoval result = manager.stripAllGems(shulker);

        assertSame(shulker, result.getItem(), "容器外壳和普通内容必须保留");
        assertEquals(Collections.singletonList(GEM_ID), result.getGemIds());
        assertEquals(1, result.getRemovedCount());
        verify(inventory).setItem(0, null);
        verify(inventory, never()).setItem(1, null);
        verify(meta).setBlockState(state);
        verify(shulker).setItemMeta(meta);
    }

    private ItemStack gemItem() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        lenient().when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenReturn(GEM_ID.toString());
        return item;
    }

    private ItemStack shulkerContaining(ItemStack nested) {
        ItemStack shulker = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory inventory = mock(Inventory.class);

        when(shulker.getType()).thenReturn(Material.SHULKER_BOX);
        lenient().when(shulker.hasItemMeta()).thenReturn(true);
        lenient().when(shulker.getItemMeta()).thenReturn(meta);
        lenient().when(meta.getPersistentDataContainer()).thenReturn(pdc);
        lenient().when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
        lenient().when(meta.hasBlockState()).thenReturn(true);
        lenient().when(meta.getBlockState()).thenReturn(state);
        lenient().when(state.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getContents()).thenReturn(new ItemStack[] { nested });
        return shulker;
    }
}
