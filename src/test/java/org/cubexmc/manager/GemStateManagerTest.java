package org.cubexmc.manager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.RuleGems;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GemStateManager 单元测试
 * 测试 UUID↔Location↔Holder 映射一致性、宝石识别、
 * GemDefinition 缓存、标识符解析等核心功能。
 */
@ExtendWith(MockitoExtension.class)
class GemStateManagerTest {

    @Mock private RuleGems plugin;
    @Mock private GemDefinitionParser gemParser;
    @Mock private LanguageManager languageManager;

    private GemStateManager manager;

    private static final UUID GEM_1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GEM_2 = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID GEM_3 = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));
        // NamespacedKey requires a plugin; mock it
        // Since NamespacedKey constructor calls plugin.getName(), we mock that
        lenient().when(plugin.getName()).thenReturn("RuleGems");

        manager = new GemStateManager(plugin, gemParser, languageManager);
    }

    // ==================== Helper methods ====================

    private Location mockLocation(String worldName, double x, double y, double z) {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn(worldName);
        Location loc = mock(Location.class);
        lenient().when(loc.getWorld()).thenReturn(world);
        lenient().when(loc.getX()).thenReturn(x);
        lenient().when(loc.getY()).thenReturn(y);
        lenient().when(loc.getZ()).thenReturn(z);
        lenient().when(loc.getBlockX()).thenReturn((int) x);
        lenient().when(loc.getBlockY()).thenReturn((int) y);
        lenient().when(loc.getBlockZ()).thenReturn((int) z);
        return loc;
    }

    private GemDefinition createSimpleDef(String key, String displayName) {
        PowerStructure ps = new PowerStructure();
        return new GemDefinition(key, Material.DIAMOND_BLOCK, displayName, null, null,
                null, null, null, ps, null, null, false, null, 1, null, null, null);
    }

    // ==================== Mapping Consistency ====================

    @Nested
    class MappingConsistency {

        @Test
        void locationToGemAndGemToLocationAreConsistent() {
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getLocationToGemUuid().put(loc, GEM_1);
            manager.getGemUuidToLocation().put(GEM_1, loc);

            assertEquals(GEM_1, manager.getGemUuidByLocation(loc));
            assertEquals(loc, manager.getGemLocation(GEM_1));
            assertEquals(loc, manager.findLocationByGemId(GEM_1));
        }

        @Test
        void holderMappingWorks() {
            Player mockPlayer = mock(Player.class);
            manager.getGemUuidToHolder().put(GEM_1, mockPlayer);

            assertEquals(mockPlayer, manager.getGemHolder(GEM_1));
            assertNull(manager.getGemHolder(GEM_2));
        }

        @Test
        void gemKeyMappingWorks() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            manager.getGemUuidToKey().put(GEM_2, "ice_gem");

            assertEquals("fire_gem", manager.getGemKey(GEM_1));
            assertEquals("ice_gem", manager.getGemKey(GEM_2));
            assertNull(manager.getGemKey(GEM_3));
        }
    }

    // ==================== Counts ====================

    @Nested
    class Counts {

        @Test
        void placedCount() {
            Location loc1 = mockLocation("world", 10, 64, 20);
            Location loc2 = mockLocation("world", 30, 70, 40);
            manager.getLocationToGemUuid().put(loc1, GEM_1);
            manager.getLocationToGemUuid().put(loc2, GEM_2);

            assertEquals(2, manager.getPlacedCount());
        }

        @Test
        void heldCount() {
            Player p = mock(Player.class);
            manager.getGemUuidToHolder().put(GEM_1, p);

            assertEquals(1, manager.getHeldCount());
        }

        @Test
        void totalCount() {
            Location loc1 = mockLocation("world", 10, 64, 20);
            manager.getLocationToGemUuid().put(loc1, GEM_1);
            Player p = mock(Player.class);
            manager.getGemUuidToHolder().put(GEM_2, p);

            assertEquals(2, manager.getTotalGemCount());
        }

        @Test
        void allGemUuids() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            manager.getGemUuidToKey().put(GEM_2, "ice_gem");

            Set<UUID> all = manager.getAllGemUuids();
            assertEquals(2, all.size());
            assertTrue(all.contains(GEM_1));
            assertTrue(all.contains(GEM_2));
        }

        @Test
        void allGemUuidsReturnsDefensiveCopy() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            Set<UUID> all = manager.getAllGemUuids();
            all.add(GEM_2); // modify the copy
            assertEquals(1, manager.getAllGemUuids().size()); // original unchanged
        }
    }

    // ==================== isRuleGem (ItemStack) ====================

    @Nested
    class IsRuleGemItem {

        @Test
        void returnsFalseForNull() {
            assertFalse(manager.isRuleGem((ItemStack) null));
        }

        @Test
        void returnsFalseForItemWithoutMeta() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(false);

            assertFalse(manager.isRuleGem(item));
        }

        @Test
        void returnsTrueWhenPDCHasRuleGemKey() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);

            assertTrue(manager.isRuleGem(item));
        }

        @Test
        void returnsFalseWhenPDCMissesKey() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);

            assertFalse(manager.isRuleGem(item));
        }
    }

    // ==================== isRuleGem (Block) ====================

    @Nested
    class IsRuleGemBlock {

        @Test
        void returnsFalseForNull() {
            assertFalse(manager.isRuleGem((Block) null));
        }

        @Test
        void returnsTrueWhenBlockLocationInMap() {
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getLocationToGemUuid().put(loc, GEM_1);

            Block block = mock(Block.class);
            when(block.getLocation()).thenReturn(loc);

            assertTrue(manager.isRuleGem(block));
        }

        @Test
        void returnsFalseWhenBlockNotInMap() {
            Location loc = mockLocation("world", 10, 64, 20);
            Block block = mock(Block.class);
            when(block.getLocation()).thenReturn(loc);

            assertFalse(manager.isRuleGem(block));
        }
    }

    // ==================== getGemUUID (ItemStack) ====================

    @Nested
    class GetGemUUIDItem {

        @Test
        void returnsNullForNull() {
            assertNull(manager.getGemUUID((ItemStack) null));
        }

        @Test
        void returnsNullForItemWithoutMeta() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(false);

            assertNull(manager.getGemUUID(item));
        }

        @Test
        void returnsUUIDFromPDC() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(GEM_1.toString());

            assertEquals(GEM_1, manager.getGemUUID(item));
        }

        @Test
        void returnsNullForInvalidUUID() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn("not-a-uuid");

            assertNull(manager.getGemUUID(item));
        }

        @Test
        void returnsNullWhenPDCMissesKey() {
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(null);

            assertNull(manager.getGemUUID(item));
        }
    }

    // ==================== getGemUUID (Block) ====================

    @Nested
    class GetGemUUIDBlock {

        @Test
        void returnsNullForNull() {
            assertNull(manager.getGemUUID((Block) null));
        }

        @Test
        void returnsUUIDFromLocationMap() {
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getLocationToGemUuid().put(loc, GEM_1);

            Block block = mock(Block.class);
            when(block.getLocation()).thenReturn(loc);

            assertEquals(GEM_1, manager.getGemUUID(block));
        }

        @Test
        void returnsNullWhenNotInMap() {
            Location loc = mockLocation("world", 10, 64, 20);
            Block block = mock(Block.class);
            when(block.getLocation()).thenReturn(loc);

            assertNull(manager.getGemUUID(block));
        }
    }

    // ==================== findGemDefinition ====================

    @Nested
    class FindGemDefinition {

        @Test
        void returnsNullForNull() {
            assertNull(manager.findGemDefinition(null));
        }

        @Test
        void returnsFromParser() {
            GemDefinition def = createSimpleDef("fire_gem", "Fire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            GemDefinition result = manager.findGemDefinition("fire_gem");
            assertNotNull(result);
            assertEquals("fire_gem", result.getGemKey());
        }

        @Test
        void isCaseInsensitive() {
            GemDefinition def = createSimpleDef("fire_gem", "Fire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            assertNotNull(manager.findGemDefinition("FIRE_GEM"));
            assertNotNull(manager.findGemDefinition("Fire_Gem"));
        }

        @Test
        void cachesResult() {
            GemDefinition def = createSimpleDef("fire_gem", "Fire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            // First call should populate cache
            manager.findGemDefinition("fire_gem");
            // Second call should use cache (parser not called again)
            GemDefinition cached = manager.findGemDefinition("fire_gem");
            assertNotNull(cached);
            // Verify parser was only called once
            verify(gemParser, times(1)).getGemDefinitions();
        }

        @Test
        void cacheRebuild() {
            GemDefinition def1 = createSimpleDef("fire_gem", "Fire");
            GemDefinition def2 = createSimpleDef("ice_gem", "Ice");
            when(gemParser.getGemDefinitions()).thenReturn(Arrays.asList(def1, def2));

            manager.rebuildGemDefinitionCache();

            assertEquals(2, manager.getGemDefinitionCache().size());
            assertNotNull(manager.getGemDefinitionCache().get("fire_gem"));
            assertNotNull(manager.getGemDefinitionCache().get("ice_gem"));
        }

        @Test
        void returnsNullForNonexistentKey() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

            assertNull(manager.findGemDefinition("nonexistent"));
        }
    }

    // ==================== resolveGemIdentifier ====================

    @Nested
    class ResolveGemIdentifier {

        @Test
        void returnsNullForNullOrEmpty() {
            assertNull(manager.resolveGemIdentifier(null));
            assertNull(manager.resolveGemIdentifier(""));
            assertNull(manager.resolveGemIdentifier("   "));
        }

        @Test
        void resolvesByFullUUID() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");

            UUID result = manager.resolveGemIdentifier(GEM_1.toString());
            assertEquals(GEM_1, result);
        }

        @Test
        void resolvesByShortUUIDPrefix() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");

            // GEM_1 = "10000000-0000-0000-0000-000000000001"
            UUID result = manager.resolveGemIdentifier("10000000");
            assertEquals(GEM_1, result);
        }

        @Test
        void resolvesByGemKey() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getGemUuidToLocation().put(GEM_1, loc);

            GemDefinition def = createSimpleDef("fire_gem", "Fire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            UUID result = manager.resolveGemIdentifier("fire_gem");
            assertEquals(GEM_1, result);
        }

        @Test
        void prefersPlacedOverHeldWhenResolvingByKey() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            manager.getGemUuidToKey().put(GEM_2, "fire_gem");

            Location loc = mockLocation("world", 10, 64, 20);
            manager.getGemUuidToLocation().put(GEM_1, loc);
            Player holder = mock(Player.class);
            manager.getGemUuidToHolder().put(GEM_2, holder);

            GemDefinition def = createSimpleDef("fire_gem", "Fire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            UUID result = manager.resolveGemIdentifier("fire_gem");
            assertEquals(GEM_1, result); // placed is preferred
        }

        @Test
        void returnsNullForUnknownInput() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
            assertNull(manager.resolveGemIdentifier("unknown_key"));
        }
    }

    // ==================== resolveGemKeyByNameOrKey ====================

    @Nested
    class ResolveGemKeyByNameOrKey {

        @Test
        void returnsNullForNullOrEmpty() {
            assertNull(manager.resolveGemKeyByNameOrKey(null));
            assertNull(manager.resolveGemKeyByNameOrKey(""));
        }

        @Test
        void resolvesByExactKey() {
            GemDefinition def = createSimpleDef("fire_gem", "&cFire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            assertEquals("fire_gem", manager.resolveGemKeyByNameOrKey("fire_gem"));
        }

        @Test
        void resolvesByDisplayNameSubstring() {
            GemDefinition def = createSimpleDef("fire_gem", "&cFire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            assertEquals("fire_gem", manager.resolveGemKeyByNameOrKey("Fire"));
        }

        @Test
        void returnsNullWhenNotFound() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
            assertNull(manager.resolveGemKeyByNameOrKey("nonexistent"));
        }
    }

    // ==================== Player Name Cache ====================

    @Nested
    class PlayerNameCacheTest {

        @Test
        void cachePlayerName() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getUniqueId()).thenReturn(PLAYER_A);
            when(mockPlayer.getName()).thenReturn("TestPlayer");

            manager.cachePlayerName(mockPlayer);

            assertEquals("TestPlayer", manager.getPlayerNameCache().get(PLAYER_A));
        }

        @Test
        void cacheHandlesNull() {
            manager.cachePlayerName(null);
            assertTrue(manager.getPlayerNameCache().isEmpty());
        }
    }

    // ==================== clearAll ====================

    @Nested
    class ClearAllTest {

        @Test
        void clearsAllMaps() {
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getLocationToGemUuid().put(loc, GEM_1);
            manager.getGemUuidToLocation().put(GEM_1, loc);
            manager.getGemUuidToHolder().put(GEM_2, mock(Player.class));
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            manager.getPlayerNameCache().put(PLAYER_A, "TestPlayer");

            manager.clearAll();

            assertTrue(manager.getLocationToGemUuid().isEmpty());
            assertTrue(manager.getGemUuidToLocation().isEmpty());
            assertTrue(manager.getGemUuidToHolder().isEmpty());
            assertTrue(manager.getGemUuidToKey().isEmpty());
            assertTrue(manager.getGemDefinitionCache().isEmpty());
            assertTrue(manager.getPlayerNameCache().isEmpty());
        }
    }

    // ==================== getGemDisplayName ====================

    @Nested
    class GetGemDisplayName {

        @Test
        void returnsNullForUnknownGem() {
            assertNull(manager.getGemDisplayName(GEM_1));
        }

        @Test
        void returnsKeyWhenNoDisplayName() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

            assertEquals("fire_gem", manager.getGemDisplayName(GEM_1));
        }

        @Test
        void returnsTranslatedDisplayName() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            GemDefinition def = createSimpleDef("fire_gem", "&cFire Gem");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            String name = manager.getGemDisplayName(GEM_1);
            assertNotNull(name);
            // ChatColor.translateAlternateColorCodes will convert &c
            assertTrue(name.contains("Fire Gem"));
        }
    }

    // ==================== isSupportRequired ====================

    @Nested
    class IsSupportRequired {

        @Test
        void returnsFalseForNull() {
            assertFalse(manager.isSupportRequired(null));
        }

        @Test
        void returnsTrueForTorch() {
            assertTrue(manager.isSupportRequired(Material.TORCH));
            assertTrue(manager.isSupportRequired(Material.WALL_TORCH));
        }

        @Test
        void returnsTrueForCarpet() {
            assertTrue(manager.isSupportRequired(Material.RED_CARPET));
        }

        @Test
        void returnsFalseForSolidBlock() {
            // DIAMOND_BLOCK is solid, no suffixes match
            assertFalse(manager.isSupportRequired(Material.DIAMOND_BLOCK));
        }
    }

    // ==================== ensureGemKeyAssigned ====================

    @Nested
    class EnsureGemKeyAssigned {

        @Test
        void doesNothingIfAlreadyAssigned() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");

            manager.ensureGemKeyAssigned(GEM_1);

            assertEquals("fire_gem", manager.getGemUuidToKey().get(GEM_1));
        }

        @Test
        void assignsRandomKeyFromDefinitions() {
            GemDefinition def = createSimpleDef("fire_gem", "Fire");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            manager.ensureGemKeyAssigned(GEM_1);

            assertEquals("fire_gem", manager.getGemUuidToKey().get(GEM_1));
        }

        @Test
        void doesNothingWhenNoDefinitions() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

            manager.ensureGemKeyAssigned(GEM_1);

            assertNull(manager.getGemUuidToKey().get(GEM_1));
        }
    }

    // ==================== getAllGemLocations ====================

    @Nested
    class GetAllGemLocations {

        @Test
        void returnsDefensiveCopy() {
            Location loc = mockLocation("world", 10, 64, 20);
            manager.getGemUuidToLocation().put(GEM_1, loc);

            Map<UUID, Location> copy = manager.getAllGemLocations();
            copy.put(GEM_2, mockLocation("world", 0, 0, 0));

            // Original should be unchanged
            assertEquals(1, manager.getGemUuidToLocation().size());
        }

        @Test
        void returnsAllLocations() {
            Location loc1 = mockLocation("world", 10, 64, 20);
            Location loc2 = mockLocation("world", 30, 70, 40);
            manager.getGemUuidToLocation().put(GEM_1, loc1);
            manager.getGemUuidToLocation().put(GEM_2, loc2);

            Map<UUID, Location> all = manager.getAllGemLocations();
            assertEquals(2, all.size());
            assertEquals(loc1, all.get(GEM_1));
            assertEquals(loc2, all.get(GEM_2));
        }
    }

    // ==================== getGemMaterial ====================

    @Nested
    class GetGemMaterial {

        @Test
        void returnsDefaultForUnknownGem() {
            assertEquals(Material.RED_STAINED_GLASS, manager.getGemMaterial(GEM_1));
        }

        @Test
        void returnsDefinedMaterial() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            GemDefinition def = createSimpleDef("fire_gem", "Fire");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            assertEquals(Material.DIAMOND_BLOCK, manager.getGemMaterial(GEM_1));
        }
    }

    // ==================== ensureConfiguredGemsPresent ====================

    @Nested
    class EnsureConfiguredGemsPresent {

        @Test
        void createsNewGemsWhenMissing() {
            GemDefinition def = createSimpleDef("fire_gem", "Fire");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            List<UUID> created = new ArrayList<>();
            manager.ensureConfiguredGemsPresent(created::add);

            assertEquals(1, created.size());
            assertNotNull(manager.getGemUuidToKey().get(created.get(0)));
            assertEquals("fire_gem", manager.getGemUuidToKey().get(created.get(0)));
        }

        @Test
        void doesNotCreateWhenAlreadyPresent() {
            manager.getGemUuidToKey().put(GEM_1, "fire_gem");
            GemDefinition def = createSimpleDef("fire_gem", "Fire");
            when(gemParser.getGemDefinitions()).thenReturn(Collections.singletonList(def));

            List<UUID> created = new ArrayList<>();
            manager.ensureConfiguredGemsPresent(created::add);

            assertTrue(created.isEmpty());
        }

        @Test
        void handlesEmptyDefinitions() {
            when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());

            List<UUID> created = new ArrayList<>();
            manager.ensureConfiguredGemsPresent(created::add);
            assertTrue(created.isEmpty());
        }
    }
}
