package org.cubexmc.update

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Synchronises bundled language resources into user-maintained language files while keeping
 * existing translations intact. Missing entries are appended and a backup is created prior to any
 * write operation.
 */
object LanguageUpdater {
    @JvmStatic
    fun merge(plugin: JavaPlugin?, targetFile: File?, resourcePath: String?) {
        if (plugin == null || targetFile == null || resourcePath == null || resourcePath.isEmpty()) {
            return
        }

        if (!targetFile.exists()) {
            val parent = targetFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            plugin.saveResource(resourcePath, false)
            return
        }

        try {
            val input = plugin.getResource(resourcePath)
            if (input == null) {
                plugin.logger.warning("Language resource missing from jar: $resourcePath")
                return
            }
            input.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                val existing = YamlConfiguration.loadConfiguration(targetFile)

                var changed = false
                for (key in defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key)) {
                        continue
                    }
                    if (!existing.contains(key)) {
                        existing.set(key, defaults.get(key))
                        changed = true
                    }
                }

                if (!changed) {
                    return
                }

                val backup = BackupHelper.createBackup(plugin, targetFile)
                if (backup != null) {
                    plugin.logger.info("Backed up ${targetFile.name} to ${backup.name}")
                }

                existing.save(targetFile)
                plugin.logger.info("Merged new defaults into ${targetFile.name}")
            }
        } catch (ex: Exception) {
            plugin.logger.warning("Failed to merge language defaults for ${targetFile.name}: ${ex.message}")
        }
    }
}
