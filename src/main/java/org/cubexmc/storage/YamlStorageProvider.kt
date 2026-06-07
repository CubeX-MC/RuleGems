package org.cubexmc.storage

/**
 * Default YAML-backed storage provider preserving the existing data/gems.yml
 * format.
 */
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.RuleGems
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level

class YamlStorageProvider(private val plugin: RuleGems) : StorageProvider {
    private var gemsFile: File? = null

    override fun getName(): String = "yaml"

    override fun initialize() {
        val dataFolder = File(plugin.dataFolder, "data")
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }
        gemsFile = File(dataFolder, "gems.yml")
        migrateLegacyDataFile()
        val file = gemsFile
        if (file != null && !file.exists()) {
            try {
                file.createNewFile()
            } catch (e: Exception) {
                plugin.logger.warning("Failed to create data/gems.yml: " + e.message)
            }
        }
    }

    override fun readGemData(): FileConfiguration {
        initialize()
        val file = gemsFile ?: return YamlConfiguration()
        return YamlConfiguration.loadConfiguration(file)
    }

    override fun saveGemData(data: FileConfiguration) {
        initialize()
        // 原子落盘：先写同目录临时文件，再 ATOMIC_MOVE 覆盖，避免写一半崩溃导致 gems.yml 被截断/损坏。
        try {
            val file = gemsFile ?: return
            val parent = file.parentFile
            val temp = File.createTempFile("gems", ".tmp", parent)
            try {
                data.save(temp)
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: Exception) {
                Files.deleteIfExists(temp.toPath())
                throw e
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save gem data", e)
        }
    }

    private fun migrateLegacyDataFile() {
        val file = gemsFile ?: return
        val oldDataFile = File(plugin.dataFolder, "data.yml")
        if (!oldDataFile.exists() || file.exists()) {
            return
        }
        try {
            Files.move(oldDataFile.toPath(), file.toPath())
            plugin.logger.info("Migrated data.yml to data/gems.yml")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to migrate data.yml: " + e.message)
        }
    }
}
