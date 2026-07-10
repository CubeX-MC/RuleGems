package org.cubexmc.update

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Applies new default values from the jar's {@code config.yml} into the user's config while keeping
 * customised values intact. Any file mutation is preceded by a timestamped backup.
 */
object ConfigUpdater {
    private const val CONFIG_RESOURCE = "config.yml"

    @JvmStatic
    fun merge(plugin: JavaPlugin) {
        merge(plugin, CONFIG_RESOURCE)
        merge(plugin, "features/appoint.yml")
        merge(plugin, "features/navigate.yml")
        merge(plugin, "features/intel.yml")
    }

    @JvmStatic
    fun merge(plugin: JavaPlugin?, resourcePath: String?) {
        if (plugin == null || resourcePath == null || resourcePath.isEmpty()) {
            return
        }

        val target = File(plugin.dataFolder, resourcePath)
        if (!target.exists()) {
            plugin.saveResource(resourcePath, false)
            plugin.logger.info("Created default $resourcePath because it was missing.")
            return
        }

        try {
            val input = plugin.getResource(resourcePath)
            if (input == null) {
                plugin.logger.warning("Default resource missing from jar: $resourcePath")
                return
            }
            input.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                val existing = YamlConfiguration.loadConfiguration(target)

                var changed = false
                for (key in defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key)) {
                        continue // Child values will ensure the section exists
                    }
                    if (!existing.contains(key)) {
                        existing.set(key, defaults.get(key))
                        changed = true
                    }
                }

                if (!changed) {
                    return
                }

                val backup = BackupHelper.createBackup(plugin, target)
                if (backup != null) {
                    plugin.logger.info("Backed up ${target.name} to ${backup.name}")
                }

                existing.save(target)
                plugin.logger.info("Merged new defaults into ${target.name}")
            }
        } catch (ex: Exception) {
            plugin.logger.warning("Failed to merge defaults into $resourcePath: ${ex.message}")
        }
    }
}
