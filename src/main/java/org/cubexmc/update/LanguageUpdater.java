package org.cubexmc.update;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Synchronises bundled language resources into user-maintained language files while keeping
 * existing translations intact. Missing entries are appended and a backup is created prior to any
 * write operation.
 */
public final class LanguageUpdater {

    private LanguageUpdater() {
    }

    public static void merge(JavaPlugin plugin, File targetFile, String resourcePath) {
        if (plugin == null || targetFile == null || resourcePath == null || resourcePath.isEmpty()) {
            return;
        }

        if (!targetFile.exists()) {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            plugin.saveResource(resourcePath, false);
            return;
        }

        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.getLogger().warning("Language resource missing from jar: " + resourcePath);
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(targetFile);

            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue;
                }
                if (!existing.contains(key)) {
                    existing.set(key, defaults.get(key));
                    changed = true;
                }
            }

            if (!changed) {
                return;
            }

            File backup = BackupHelper.createBackup(plugin, targetFile);
            if (backup != null) {
                plugin.getLogger().info("Backed up " + targetFile.getName() + " to " + backup.getName());
            }

            existing.save(targetFile);
            plugin.getLogger().info("Merged new defaults into " + targetFile.getName());
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to merge language defaults for " + targetFile.getName() + ": " + ex.getMessage());
        }
    }
}
