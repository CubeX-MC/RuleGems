package org.cubexmc.storage

/**
 * Default YAML-backed storage provider preserving the existing data/gems.yml
 * format.
 */
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.RuleGems
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
    }

    override fun readGemData(): StorageLoadResult {
        return try {
            initialize()
            val file = gemsFile ?: return StorageLoadResult.failure(
                StorageException("YAML storage file was not initialized"),
            )
            val backup = backupFile(file)
            if (!file.exists()) {
                if (backup.exists()) {
                    return loadStrict(backup).also {
                        plugin.logger.warning(
                            "Primary data/gems.yml is missing; loaded the last-known-good backup without overwriting it.",
                        )
                    }
                }
                return StorageLoadResult.notFound(YamlConfiguration())
            }
            try {
                loadStrict(file)
            } catch (primaryFailure: Exception) {
                if (!backup.exists()) {
                    throw primaryFailure
                }
                try {
                    loadStrict(backup).also {
                        plugin.logger.log(
                            Level.SEVERE,
                            "Primary data/gems.yml is unreadable; using gems.yml.bak in memory. " +
                                "The damaged primary file was left untouched.",
                            primaryFailure,
                        )
                    }
                } catch (backupFailure: Exception) {
                    primaryFailure.addSuppressed(backupFailure)
                    throw primaryFailure
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to read gem data", e)
            StorageLoadResult.failure(e)
        }
    }

    override fun saveGemData(data: FileConfiguration): StorageSaveResult {
        initialize()
        // 原子落盘：先写同目录临时文件，再 ATOMIC_MOVE 覆盖，避免写一半崩溃导致 gems.yml 被截断/损坏。
        return try {
            val file = gemsFile
                ?: return StorageSaveResult.failure(StorageException("YAML storage file was not initialized"))
            val parent = file.parentFile
            val temp = File.createTempFile("gems", ".tmp", parent)
            try {
                data.save(temp)
                loadStrict(temp)
                FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { channel -> channel.force(true) }
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                Files.copy(file.toPath(), backupFile(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                StorageSaveResult.success()
            } catch (e: Exception) {
                Files.deleteIfExists(temp.toPath())
                throw e
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save gem data", e)
            StorageSaveResult.failure(e)
        }
    }

    private fun loadStrict(file: File): StorageLoadResult {
        val data = YamlConfiguration()
        data.load(file)
        return StorageLoadResult.success(data)
    }

    private fun backupFile(file: File): File = File(file.parentFile, file.name + ".bak")

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
