package org.cubexmc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReadsGemData() {
        RuleGems plugin = plugin();
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", "data/test.db");
        SqliteStorageProvider provider = new SqliteStorageProvider(plugin, config);

        YamlConfiguration data = new YamlConfiguration();
        data.set("placed-gems.example.world", "world");
        data.set("allowed_uses.player.labels", List.of("fly", "heal"));

        provider.saveGemData(data);

        YamlConfiguration read = (YamlConfiguration) provider.readGemData();
        assertEquals("world", read.getString("placed-gems.example.world"));
        assertEquals(List.of("fly", "heal"), read.getStringList("allowed_uses.player.labels"));
        assertTrue(new File(tempDir.toFile(), "data/test.db").exists());
    }

    @Test
    void importsExistingYamlWhenDatabaseIsEmpty() throws Exception {
        File dataFolder = tempDir.toFile();
        File dataDir = new File(dataFolder, "data");
        dataDir.mkdirs();
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("held-gems.player.gem", "flight");
        legacy.save(new File(dataDir, "gems.yml"));

        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", "data/import.db");
        SqliteStorageProvider provider = new SqliteStorageProvider(plugin(), config);

        YamlConfiguration read = (YamlConfiguration) provider.readGemData();
        assertEquals("flight", read.getString("held-gems.player.gem"));
    }

    @Test
    void doesNotReimportLegacyYamlAfterDatabaseHasData() throws Exception {
        File dataFolder = tempDir.toFile();
        File dataDir = new File(dataFolder, "data");
        dataDir.mkdirs();
        File legacyFile = new File(dataDir, "gems.yml");
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("held-gems.player.gem", "flight");
        legacy.save(legacyFile);

        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", "data/stable.db");
        SqliteStorageProvider provider = new SqliteStorageProvider(plugin(), config);
        assertEquals("flight", provider.readGemData().getString("held-gems.player.gem"));

        YamlConfiguration persisted = new YamlConfiguration();
        persisted.set("held-gems.player.gem", "justice");
        provider.saveGemData(persisted);

        legacy.set("held-gems.player.gem", "stale");
        legacy.save(legacyFile);

        assertEquals("justice", provider.readGemData().getString("held-gems.player.gem"));
    }

    private RuleGems plugin() {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqliteStorageProviderTest"));
        return plugin;
    }
}
