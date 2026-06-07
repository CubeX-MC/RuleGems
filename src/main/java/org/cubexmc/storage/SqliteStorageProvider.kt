package org.cubexmc.storage

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.RuleGems
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Level

/**
 * SQLite-backed storage for mutable RuleGems runtime data.
 *
 * The provider stores the existing gems.yml shape as a YAML payload. This keeps
 * the storage boundary stable while allowing operators to use an embedded
 * database file instead of a loose YAML data file.
 */
class SqliteStorageProvider(
    private val plugin: RuleGems,
    private val config: FileConfiguration?,
) : StorageProvider {
    private var databaseFile: File? = null
    private var initialized = false

    override fun getName(): String = "sqlite"

    @Synchronized
    override fun initialize() {
        if (initialized) {
            return
        }
        databaseFile = resolveDatabaseFile()
        val parent = databaseFile?.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        try {
            openConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS $TABLE (" +
                            "storage_key TEXT PRIMARY KEY," +
                            "yaml_payload TEXT NOT NULL," +
                            "updated_at INTEGER NOT NULL" +
                            ")",
                    )
                }
                migrateYamlIfEmpty(connection)
                initialized = true
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to initialize SQLite storage", e)
        }
    }

    override fun readGemData(): FileConfiguration {
        initialize()
        val data = YamlConfiguration()
        try {
            openConnection().use { connection ->
                connection.prepareStatement("SELECT yaml_payload FROM $TABLE WHERE storage_key = ?").use { statement ->
                    statement.setString(1, GEM_DATA_KEY)
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            data.loadFromString(result.getString("yaml_payload"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to read gem data from SQLite", e)
        }
        return data
    }

    override fun saveGemData(data: FileConfiguration) {
        initialize()
        val payload = if (data is YamlConfiguration) data.saveToString() else copyToYaml(data).saveToString()
        try {
            openConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO $TABLE (storage_key, yaml_payload, updated_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT(storage_key) DO UPDATE SET " +
                        "yaml_payload = excluded.yaml_payload, updated_at = excluded.updated_at",
                ).use { statement ->
                    statement.setString(1, GEM_DATA_KEY)
                    statement.setString(2, payload)
                    statement.setLong(3, System.currentTimeMillis())
                    statement.executeUpdate()
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save gem data to SQLite", e)
        }
    }

    private fun resolveDatabaseFile(): File {
        val configured = config?.getString("storage.sqlite.file", DEFAULT_DATABASE) ?: DEFAULT_DATABASE
        val file = File(configured)
        if (file.isAbsolute) {
            return file
        }
        return File(plugin.dataFolder, configured)
    }

    private fun openConnection(): Connection {
        val file = databaseFile ?: throw IllegalStateException("SQLite database file has not been initialized")
        return DriverManager.getConnection("jdbc:sqlite:" + file.absolutePath)
    }

    private fun migrateYamlIfEmpty(connection: Connection) {
        if (hasGemData(connection)) {
            return
        }
        val yamlFile = File(plugin.dataFolder, "data/gems.yml")
        if (!yamlFile.exists()) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(yamlFile)
        connection.prepareStatement("INSERT INTO $TABLE (storage_key, yaml_payload, updated_at) VALUES (?, ?, ?)")
            .use { statement ->
                statement.setString(1, GEM_DATA_KEY)
                statement.setString(2, yaml.saveToString())
                statement.setLong(3, System.currentTimeMillis())
                statement.executeUpdate()
                plugin.logger.info("Migrated data/gems.yml into SQLite storage.")
            }
    }

    private fun hasGemData(connection: Connection): Boolean {
        connection.prepareStatement("SELECT 1 FROM $TABLE WHERE storage_key = ?").use { statement ->
            statement.setString(1, GEM_DATA_KEY)
            statement.executeQuery().use { result ->
                return result.next()
            }
        }
    }

    private fun copyToYaml(source: FileConfiguration): YamlConfiguration {
        val yaml = YamlConfiguration()
        for (key in source.getKeys(true)) {
            yaml.set(key, source.get(key))
        }
        return yaml
    }

    companion object {
        private const val DEFAULT_DATABASE = "data/rulegems.db"
        private const val TABLE = "rulegems_storage"
        private const val GEM_DATA_KEY = "gems"
    }
}
