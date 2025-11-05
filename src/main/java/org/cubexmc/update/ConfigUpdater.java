package org.cubexmc.update;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies new default values from the jar's {@code config.yml} into the user's config while keeping
 * customised values intact. Any file mutation is preceded by a timestamped backup.
 */
public final class ConfigUpdater {

    private static final String CONFIG_RESOURCE = "config.yml";

    private ConfigUpdater() {
    }

    public static void merge(JavaPlugin plugin) {
        merge(plugin, CONFIG_RESOURCE);
    }

    public static void merge(JavaPlugin plugin, String resourcePath) {
        if (plugin == null || resourcePath == null || resourcePath.isEmpty()) {
            return;
        }

        File target = new File(plugin.getDataFolder(), resourcePath);
        if (!target.exists()) {
            plugin.saveResource(resourcePath, false);
            plugin.getLogger().info("Created default " + resourcePath + " because it was missing.");
            return;
        }

        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.getLogger().warning("Default resource missing from jar: " + resourcePath);
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(target);

            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue; // Child values will ensure the section exists
                }
                if (!existing.contains(key)) {
                    existing.set(key, defaults.get(key));
                    changed = true;
                }
            }

            if (!changed) {
                return;
            }

            File backup = BackupHelper.createBackup(plugin, target);
            if (backup != null) {
                plugin.getLogger().info("Backed up " + target.getName() + " to " + backup.getName());
            }

            existing.save(target);
            plugin.getLogger().info("Merged new defaults into " + target.getName());
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to merge defaults into " + resourcePath + ": " + ex.getMessage());
        }
    }
}
