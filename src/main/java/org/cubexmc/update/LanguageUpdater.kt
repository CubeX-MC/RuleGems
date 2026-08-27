package org.cubexmc.update

import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.YamlDefaults
import java.io.File

/** Shared default merging, preserving user translations and RuleGems' backup directory. */
object LanguageUpdater {
    @JvmStatic
    fun merge(plugin: JavaPlugin?, targetFile: File?, resourcePath: String?) {
        if (plugin == null || targetFile == null || resourcePath.isNullOrEmpty()) return
        // Custom locales need not have a bundled counterpart.
        plugin.getResource(resourcePath)?.use { } ?: return
        YamlDefaults(plugin).mergeResourceIntoDataFile(resourcePath, targetFile, ConfigUpdater.options(plugin))
    }
}
