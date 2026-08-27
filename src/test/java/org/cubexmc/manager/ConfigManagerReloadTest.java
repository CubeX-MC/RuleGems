package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class ConfigManagerReloadTest {
    @TempDir Path directory;

    private static final String CONFIG = """
            language: zh_CN
            random_place_range:
              world: world
              corner1: {x: 0, y: 64, z: 0}
              corner2: {x: 10, y: 70, z: 10}
            economy:
              transfer_directives_enabled: false
            """;

    @Test
    void invalidInputsLeaveThePublishedRuntimeAndProviderUntouched() throws Exception {
        RuleGems plugin = fixture();
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            ConfigManager manager = new ConfigManager(plugin, mock(LanguageManager.class));
            manager.loadConfigs();
            FileConfiguration original = manager.getConfig();
            StorageProvider provider = manager.getStorageProvider();
            manager.readGemsData();

            Files.writeString(directory.resolve("config.yml"), CONFIG.replace("world: world", "world: missing"));
            assertThrows(IllegalArgumentException.class, manager::reload);
            assertSame(original, manager.getConfig());
            assertSame(provider, manager.getStorageProvider());

            Files.writeString(directory.resolve("config.yml"),
                    CONFIG.replace("transfer_directives_enabled: false", "transfer_directives_enabled: true"));
            Files.createDirectories(directory.resolve("gems/nested"));
            Path broken = directory.resolve("gems/nested/custom.YML");
            Files.writeString(broken, "gem: [broken\n");
            assertThrows(Exception.class, manager::reload);
            assertSame(original, manager.getConfig());
            assertFalse(manager.getGameplayConfig().isTransferDirectivesEnabled());
            assertEquals("gem: [broken\n", Files.readString(broken));

            Files.writeString(broken, "{}\n");
            manager.reload();
            assertTrue(manager.getGameplayConfig().isTransferDirectivesEnabled());
            verify(plugin, times(2)).reloadConfig();
        }
    }

    @Test
    void unreadableStorageAndAppointmentsAreNotAcceptedAsAnEmptyUpgrade() throws Exception {
        RuleGems plugin = fixture();
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            ConfigManager manager = new ConfigManager(plugin, mock(LanguageManager.class));
            manager.loadConfigs();
            FileConfiguration original = manager.getConfig();

            Files.writeString(directory.resolve("config.yml"), CONFIG + "effects: {duration: 80, refresh_interval: 8}\n");
            Files.writeString(directory.resolve("data/gems.yml"), "held-gems: [broken\n");
            assertThrows(IllegalStateException.class, manager::reload);
            assertSame(original, manager.getConfig());
            assertEquals(org.cubexmc.model.EffectConfig.DEFAULT_DURATION_TICKS,
                    org.cubexmc.model.EffectConfig.Companion.getDurationTicks());

            Files.writeString(directory.resolve("data/gems.yml"), "{}\n");
            Files.writeString(directory.resolve("data/appoints.yml"), "appointments: [broken\n");
            assertThrows(Exception.class, manager::reload);
            assertSame(original, manager.getConfig());
            verify(plugin).reloadConfig();
        }
    }

    private RuleGems fixture() throws Exception {
        Files.createDirectories(directory.resolve("gems"));
        Files.createDirectories(directory.resolve("powers"));
        Files.createDirectories(directory.resolve("features"));
        Files.writeString(directory.resolve("config.yml"), CONFIG);
        for (String feature : java.util.List.of("appoint", "intel", "navigate")) {
            Files.writeString(directory.resolve("features/" + feature + ".yml"), "enabled: false\n");
        }
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigManagerReloadTest"));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        return plugin;
    }
}
