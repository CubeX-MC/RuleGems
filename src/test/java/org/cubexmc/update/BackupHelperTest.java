package org.cubexmc.update;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupHelperTest {
    @TempDir Path directory;
    private JavaPlugin plugin;

    @BeforeEach
    void setup() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BackupHelperTest"));
    }

    @Test
    void repeatedSingleFileBackupsDoNotReplaceEarlierContent() throws Exception {
        Path source = directory.resolve("config.yml");
        Files.writeString(source, "old");
        var first = BackupHelper.createBackup(plugin, source.toFile());
        Files.writeString(source, "new");
        var second = BackupHelper.createBackup(plugin, source.toFile());
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals("old", Files.readString(first.toPath()));
        assertEquals("new", Files.readString(second.toPath()));
    }

    @Test
    void completeUpgradeBackupsUseIndependentDirectories() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "config");
        Files.createDirectories(directory.resolve("data"));
        Files.writeString(directory.resolve("data/appoints.yml"), "appointments");
        var first = BackupHelper.createConfigOptimizationBackup(plugin);
        var second = BackupHelper.createConfigOptimizationBackup(plugin);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals("appointments", Files.readString(first.toPath().resolve("data/appoints.yml")));
    }

    @Test
    void partialCopyFailureIsNotReportedAsCompleteBackup() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "config");
        Path powers = directory.resolve("powers");
        Files.createDirectories(powers);
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.walk(powers)).thenThrow(new IOException("simulated directory read failure"));
            assertNull(BackupHelper.createConfigOptimizationBackup(plugin));
        }
        try (var backups = Files.list(directory.resolve("backups"))) {
            Path retained = backups.findFirst().orElseThrow();
            assertEquals("config", Files.readString(retained.resolve("config.yml")));
        }
    }
}
