package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证多世界延迟加载场景：宝石所在世界在加载时尚未就绪(例如 Multiverse 后加载)时，
 * 记录被暂存而非丢弃，既不会被 ensureConfiguredGemsPresent 复制，也会在世界加载后重新绑定。
 */
@ExtendWith(MockitoExtension.class)
class GemStateManagerWorldLoadTest {

    @Mock private RuleGems plugin;
    @Mock private GemDefinitionParser gemParser;
    @Mock private LanguageManager languageManager;

    private MockedStatic<Bukkit> mockedBukkit;
    private GemStateManager manager;

    private static final UUID GEM = UUID.fromString("10000000-0000-0000-0000-0000000000aa");

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemStateManagerWorldLoadTest"));
        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(gemParser.getGemDefinitions()).thenReturn(Collections.emptyList());
        mockedBukkit = mockStatic(Bukkit.class);
        manager = new GemStateManager(plugin, gemParser, languageManager);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    private YamlConfiguration placedGemInWorld(String worldName) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("placed-gems." + GEM + ".world", worldName);
        data.set("placed-gems." + GEM + ".x", 10.0);
        data.set("placed-gems." + GEM + ".y", 64.0);
        data.set("placed-gems." + GEM + ".z", 20.0);
        data.set("placed-gems." + GEM + ".gem_key", "fire");
        return data;
    }

    @Test
    void deferredGemRegistersKeyButNoLocationWhenWorldMissing() {
        mockedBukkit.when(() -> Bukkit.getWorld("void_world")).thenReturn(null);

        manager.loadData(placedGemInWorld("void_world"), id -> {});

        // Key registered -> ensureConfiguredGemsPresent counts it and never duplicates the instance.
        assertEquals("fire", manager.getGemKey(GEM));
        assertTrue(manager.getAllGemUuids().contains(GEM));
        // Not bound to a location while the world is unavailable.
        assertNull(manager.getGemLocation(GEM));
    }

    @Test
    void deferredGemIsReEmittedInSaveSnapshotSoAutosaveDoesNotEraseIt() {
        mockedBukkit.when(() -> Bukkit.getWorld("void_world")).thenReturn(null);
        manager.loadData(placedGemInWorld("void_world"), id -> {});

        Map<String, Object> snapshot = new HashMap<>();
        manager.populateSaveSnapshot(snapshot);

        assertEquals("void_world", snapshot.get("placed-gems." + GEM + ".world"));
        assertEquals("fire", snapshot.get("placed-gems." + GEM + ".gem_key"));
        assertEquals(10.0, snapshot.get("placed-gems." + GEM + ".x"));
    }

    @Test
    void bindPendingWorldGemsBindsRecordWhenWorldFinallyLoads() {
        mockedBukkit.when(() -> Bukkit.getWorld("void_world")).thenReturn(null);
        manager.loadData(placedGemInWorld("void_world"), id -> {});
        assertNull(manager.getGemLocation(GEM));

        World world = mock(World.class);
        when(world.getName()).thenReturn("void_world");

        Map<UUID, Location> rebound = manager.bindPendingWorldGems(world);

        assertTrue(rebound.containsKey(GEM));
        Location loc = manager.getGemLocation(GEM);
        assertNotNull(loc);
        assertEquals(world, loc.getWorld());
        assertEquals(10.0, loc.getX());
        // Draining is idempotent: a second world-load fires nothing.
        assertTrue(manager.bindPendingWorldGems(world).isEmpty());
    }

    @Test
    void loadedWorldBindsImmediatelyWithoutDeferral() {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        mockedBukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

        manager.loadData(placedGemInWorld("world"), id -> {});

        Location loc = manager.getGemLocation(GEM);
        assertNotNull(loc);
        assertEquals("fire", manager.getGemKey(GEM));
        // Nothing was deferred, so a later world-load is a no-op.
        assertTrue(manager.bindPendingWorldGems(world).isEmpty());
    }
}
