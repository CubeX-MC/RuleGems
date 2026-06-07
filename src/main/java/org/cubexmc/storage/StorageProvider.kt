package org.cubexmc.storage

import org.bukkit.configuration.file.FileConfiguration

/**
 * Storage boundary for mutable RuleGems runtime data.
 */
interface StorageProvider {
    fun getName(): String

    fun initialize()

    fun readGemData(): FileConfiguration

    fun saveGemData(data: FileConfiguration)
}
