package org.cubexmc.update

import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.config.FileBackups
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Backups never overwrite earlier runs; a partial directory is retained but not reported as success. */
object BackupHelper {
    @JvmStatic
    fun createBackup(plugin: JavaPlugin?, source: File?): File? {
        if (plugin == null || source == null || !source.exists()) return null
        return try {
            FileBackups.copyUnique(source, File(plugin.dataFolder, "backups"))
        } catch (failure: IOException) {
            plugin.logger.warning("Failed to back up ${source.name}: ${failure.message}")
            null
        }
    }

    @JvmStatic
    fun createConfigOptimizationBackup(plugin: JavaPlugin?): File? {
        if (plugin == null) return null
        return try {
            val root = File(plugin.dataFolder, "backups").toPath()
            Files.createDirectories(root)
            val destination = Files.createTempDirectory(root, "config-optimization-")
            for (name in listOf("config.yml", "gems", "powers", "features", "data", "gems.yml")) {
                copyIfExists(File(plugin.dataFolder, name), destination.resolve(name))
            }
            destination.toFile()
        } catch (failure: IOException) {
            plugin.logger.warning("Configuration backup incomplete; partial files retained: ${failure.message}")
            null
        } catch (failure: UncheckedIOException) {
            plugin.logger.warning("Configuration backup incomplete; partial files retained: ${failure.message}")
            null
        }
    }

    private fun copyIfExists(source: File, target: Path) {
        if (!source.exists()) return
        if (source.isDirectory) {
            Files.walk(source.toPath()).use { paths ->
                paths.forEach { path -> copyPath(path, target.resolve(source.toPath().relativize(path))) }
            }
        } else {
            copyPath(source.toPath(), target)
        }
    }

    private fun copyPath(source: Path, target: Path) {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target)
        } else {
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}
