package org.cubexmc.update

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Utility for creating timestamped backups of user configuration files before they are mutated.
 */
object BackupHelper {
    private val TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss")

    /**
     * Creates a backup copy of the given file inside <plugin>/backups.
     *
     * @param plugin owning plugin instance
     * @param source the file to copy
     * @return the backup file if the backup succeeded, otherwise `null`
     */
    @JvmStatic
    fun createBackup(plugin: JavaPlugin?, source: File?): File? {
        if (plugin == null || source == null || !source.exists()) {
            return null
        }
        val backupDir = File(plugin.dataFolder, "backups")
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.logger.warning("Failed to create backup directory: " + backupDir.absolutePath)
            return null
        }

        val name = source.name
        val dot = name.lastIndexOf('.')
        val base = if (dot == -1) name else name.substring(0, dot)
        val ext = if (dot == -1) "" else name.substring(dot)
        val timestamp = TIMESTAMP.format(Date())
        val backupFile = File(backupDir, "$base-$timestamp$ext")

        try {
            val from = source.toPath()
            val to = backupFile.toPath()
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
            return backupFile
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to backup file ${source.name}: ${ex.message}")
            return null
        }
    }

    /**
     * Copies the main mutable RuleGems configuration surfaces into one timestamped
     * upgrade backup directory without changing the source files.
     */
    @JvmStatic
    fun createConfigOptimizationBackup(plugin: JavaPlugin?): File? {
        if (plugin == null) {
            return null
        }
        val backupDir = File(plugin.dataFolder, "backups/config-optimization-" + TIMESTAMP.format(Date()))
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.logger.warning("Failed to create backup directory: " + backupDir.absolutePath)
            return null
        }

        copyIfExists(plugin, File(plugin.dataFolder, "config.yml"), File(backupDir, "config.yml"))
        copyIfExists(plugin, File(plugin.dataFolder, "gems"), File(backupDir, "gems"))
        copyIfExists(plugin, File(plugin.dataFolder, "powers"), File(backupDir, "powers"))
        copyIfExists(plugin, File(plugin.dataFolder, "features"), File(backupDir, "features"))
        copyIfExists(plugin, File(plugin.dataFolder, "data"), File(backupDir, "data"))
        copyIfExists(plugin, File(plugin.dataFolder, "gems.yml"), File(backupDir, "gems.yml"))
        return backupDir
    }

    private fun copyIfExists(plugin: JavaPlugin, source: File?, target: File) {
        if (source == null || !source.exists()) {
            return
        }
        try {
            if (source.isDirectory) {
                Files.walk(source.toPath()).use { stream ->
                    stream.forEach { path -> copyWalkPath(plugin, source.toPath(), target.toPath(), path) }
                }
            } else {
                val parent = target.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to backup ${source.name}: ${ex.message}")
        }
    }

    private fun copyWalkPath(plugin: JavaPlugin, sourceRoot: Path, targetRoot: Path, path: Path) {
        try {
            val relative = sourceRoot.relativize(path)
            val target = targetRoot.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to backup path $path: ${ex.message}")
        }
    }
}
