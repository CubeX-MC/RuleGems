package org.cubexmc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStorageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingPrimaryAndBackupIsAnExplicitNewInstallation() {
        StorageLoadResult result = new YamlStorageProvider(plugin()).readGemData();

        assertEquals(StorageLoadStatus.NOT_FOUND, result.getStatus());
        assertNotNull(result.getData());
    }

    @Test
    void corruptPrimaryWithoutBackupFailsClosed() throws Exception {
        File dataDir = new File(tempDir.toFile(), "data");
        assertTrue(dataDir.mkdirs());
        Files.writeString(
                new File(dataDir, "gems.yml").toPath(),
                "placed-gems: [unterminated",
                StandardCharsets.UTF_8);

        StorageLoadResult result = new YamlStorageProvider(plugin()).readGemData();

        assertEquals(StorageLoadStatus.FAILURE, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void successfulSaveCreatesLastKnownGoodBackup() {
        YamlStorageProvider provider = new YamlStorageProvider(plugin());
        YamlConfiguration data = new YamlConfiguration();
        data.set("held-gems.player.gem", "flight");

        StorageSaveResult saved = provider.saveGemData(data);
        StorageLoadResult loaded = provider.readGemData();

        assertTrue(saved.getSuccessful());
        assertEquals(StorageLoadStatus.SUCCESS, loaded.getStatus());
        assertEquals("flight", loaded.getData().getString("held-gems.player.gem"));
        assertTrue(new File(tempDir.toFile(), "data/gems.yml.bak").isFile());
    }

    @Test
    void corruptPrimaryLoadsBackupWithoutReplacingDamagedFile() throws Exception {
        YamlStorageProvider provider = new YamlStorageProvider(plugin());
        YamlConfiguration data = new YamlConfiguration();
        data.set("held-gems.player.gem", "justice");
        assertTrue(provider.saveGemData(data).getSuccessful());

        File primary = new File(tempDir.toFile(), "data/gems.yml");
        Files.writeString(primary.toPath(), "held-gems: [broken", StandardCharsets.UTF_8);

        StorageLoadResult loaded = provider.readGemData();

        assertEquals(StorageLoadStatus.SUCCESS, loaded.getStatus());
        assertEquals("justice", loaded.getData().getString("held-gems.player.gem"));
        assertEquals("held-gems: [broken", Files.readString(primary.toPath(), StandardCharsets.UTF_8));
    }

    private RuleGems plugin() {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("YamlStorageProviderTest"));
        return plugin;
    }
}
