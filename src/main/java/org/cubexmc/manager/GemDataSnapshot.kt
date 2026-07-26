package org.cubexmc.manager

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Immutable serialized staging form used between storage validation and the
 * live-state commit. It prevents a cached FileConfiguration from changing
 * underneath a reload.
 */
class GemDataSnapshot private constructor(private val yaml: String) {
    fun materialize(): YamlConfiguration =
        YamlConfiguration().also { it.loadFromString(yaml) }

    companion object {
        @JvmStatic
        fun capture(data: FileConfiguration): GemDataSnapshot =
            GemDataSnapshot(data.saveToString())
    }
}
