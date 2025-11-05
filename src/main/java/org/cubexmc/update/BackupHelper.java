package org.cubexmc.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Utility for creating timestamped backups of user configuration files before they are mutated.
 */
public final class BackupHelper {

    private static final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private BackupHelper() {
    }

    /**
     * Creates a backup copy of the given file inside <plugin>/backups.
     *
     * @param plugin owning plugin instance
     * @param source the file to copy
     * @return the backup file if the backup succeeded, otherwise {@code null}
     */
    public static File createBackup(JavaPlugin plugin, File source) {
        if (plugin == null || source == null || !source.exists()) {
            return null;
        }
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create backup directory: " + backupDir.getAbsolutePath());
            return null;
        }

        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot == -1 ? name : name.substring(0, dot);
        String ext = dot == -1 ? "" : name.substring(dot);
        String timestamp = TIMESTAMP.format(new Date());
        File backupFile = new File(backupDir, base + "-" + timestamp + ext);

        try {
            Path from = source.toPath();
            Path to = backupFile.toPath();
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            return backupFile;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to backup file " + source.getName() + ": " + ex.getMessage());
            return null;
        }
    }
}
