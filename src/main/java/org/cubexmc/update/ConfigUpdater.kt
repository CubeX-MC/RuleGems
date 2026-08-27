package org.cubexmc.update

import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.DefaultMergeOptions
import org.cubexmc.config.YamlDefaults

/** Keeps the existing resource list and backup location while using the shared merger. */
object ConfigUpdater {
    @JvmStatic
    fun merge(plugin: JavaPlugin) {
        for (resource in listOf("config.yml", "features/appoint.yml", "features/navigate.yml", "features/intel.yml")) {
            merge(plugin, resource)
        }
    }

    @JvmStatic
    fun merge(plugin: JavaPlugin?, resourcePath: String?) {
        if (plugin == null || resourcePath.isNullOrEmpty()) return
        YamlDefaults(plugin).mergeResourceIntoDataFile(resourcePath, options(plugin))
    }

    internal fun options(plugin: JavaPlugin): DefaultMergeOptions =
        DefaultMergeOptions.copyMissingKeys()
            .backupWith { file -> BackupHelper.createBackup(plugin, file) }
            .warnAboutCommentLoss(false)
            .failOnError(true)
}
