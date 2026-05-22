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

    /**
     * Copies the main mutable RuleGems configuration surfaces into one timestamped
     * upgrade backup directory without changing the source files.
     */
    public static File createConfigOptimizationBackup(JavaPlugin plugin) {
        if (plugin == null) {
            return null;
        }
        File backupDir = new File(plugin.getDataFolder(),
                "backups/config-optimization-" + TIMESTAMP.format(new Date()));
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create backup directory: " + backupDir.getAbsolutePath());
            return null;
        }

        copyIfExists(plugin, new File(plugin.getDataFolder(), "config.yml"), new File(backupDir, "config.yml"));
        copyIfExists(plugin, new File(plugin.getDataFolder(), "gems"), new File(backupDir, "gems"));
        copyIfExists(plugin, new File(plugin.getDataFolder(), "powers"), new File(backupDir, "powers"));
        copyIfExists(plugin, new File(plugin.getDataFolder(), "features"), new File(backupDir, "features"));
        copyIfExists(plugin, new File(plugin.getDataFolder(), "data"), new File(backupDir, "data"));
        copyIfExists(plugin, new File(plugin.getDataFolder(), "gems.yml"), new File(backupDir, "gems.yml"));
        return backupDir;
    }

    private static void copyIfExists(JavaPlugin plugin, File source, File target) {
        if (source == null || !source.exists()) {
            return;
        }
        try {
            if (source.isDirectory()) {
                Files.walk(source.toPath()).forEach(path -> copyWalkPath(plugin, source.toPath(), target.toPath(), path));
            } else {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to backup " + source.getName() + ": " + ex.getMessage());
        }
    }

    private static void copyWalkPath(JavaPlugin plugin, Path sourceRoot, Path targetRoot, Path path) {
        try {
            Path relative = sourceRoot.relativize(path);
            Path target = targetRoot.resolve(relative);
            if (Files.isDirectory(path)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to backup path " + path + ": " + ex.getMessage());
        }
    }
}
