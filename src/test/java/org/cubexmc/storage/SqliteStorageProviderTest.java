package org.cubexmc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.sql.DriverManager;
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

        assertTrue(provider.saveGemData(data).getSuccessful());

        StorageLoadResult result = provider.readGemData();
        assertEquals(StorageLoadStatus.SUCCESS, result.getStatus());
        YamlConfiguration read = (YamlConfiguration) result.getData();
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

        StorageLoadResult result = provider.readGemData();
        assertEquals(StorageLoadStatus.SUCCESS, result.getStatus());
        YamlConfiguration read = (YamlConfiguration) result.getData();
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
        assertEquals("flight", provider.readGemData().getData().getString("held-gems.player.gem"));

        YamlConfiguration persisted = new YamlConfiguration();
        persisted.set("held-gems.player.gem", "justice");
        assertTrue(provider.saveGemData(persisted).getSuccessful());

        legacy.set("held-gems.player.gem", "stale");
        legacy.save(legacyFile);

        assertEquals("justice", provider.readGemData().getData().getString("held-gems.player.gem"));
    }

    @Test
    void missingDatabaseRowIsAnExplicitNewInstallation() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", "data/empty.db");

        StorageLoadResult result = new SqliteStorageProvider(plugin(), config).readGemData();

        assertEquals(StorageLoadStatus.NOT_FOUND, result.getStatus());
        assertNotNull(result.getData());
    }

    @Test
    void corruptYamlPayloadFailsClosed() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", "data/corrupt.db");
        SqliteStorageProvider provider = new SqliteStorageProvider(plugin(), config);
        provider.initialize();

        File database = new File(tempDir.toFile(), "data/corrupt.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             var statement = connection.prepareStatement(
                     "INSERT INTO rulegems_storage (storage_key, yaml_payload, updated_at) VALUES (?, ?, ?)")) {
            statement.setString(1, "gems");
            statement.setString(2, "placed-gems: [unterminated");
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }

        StorageLoadResult result = provider.readGemData();

        assertEquals(StorageLoadStatus.FAILURE, result.getStatus());
        assertNotNull(result.getError());
    }

    private RuleGems plugin() {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqliteStorageProviderTest"));
        return plugin;
    }
}
