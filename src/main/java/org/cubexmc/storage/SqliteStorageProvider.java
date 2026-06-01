package org.cubexmc.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;

/**
 * SQLite-backed storage for mutable RuleGems runtime data.
 * <p>
 * The provider stores the existing gems.yml shape as a YAML payload. This keeps
 * the storage boundary stable while allowing operators to use an embedded
 * database file instead of a loose YAML data file.
 */
public class SqliteStorageProvider implements StorageProvider {

    private static final String DEFAULT_DATABASE = "data/rulegems.db";
    private static final String TABLE = "rulegems_storage";
    private static final String GEM_DATA_KEY = "gems";

    private final RuleGems plugin;
    private final FileConfiguration config;
    private File databaseFile;
    private boolean initialized;

    public SqliteStorageProvider(RuleGems plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String getName() {
        return "sqlite";
    }

    @Override
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        databaseFile = resolveDatabaseFile();
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "storage_key TEXT PRIMARY KEY,"
                    + "yaml_payload TEXT NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            migrateYamlIfEmpty(connection);
            initialized = true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize SQLite storage", e);
        }
    }

    @Override
    public FileConfiguration readGemData() {
        initialize();
        YamlConfiguration data = new YamlConfiguration();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT yaml_payload FROM " + TABLE + " WHERE storage_key = ?")) {
            statement.setString(1, GEM_DATA_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    data.loadFromString(result.getString("yaml_payload"));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to read gem data from SQLite", e);
        }
        return data;
    }

    @Override
    public void saveGemData(FileConfiguration data) {
        initialize();
        String payload = data instanceof YamlConfiguration
                ? ((YamlConfiguration) data).saveToString()
                : copyToYaml(data).saveToString();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + TABLE + " (storage_key, yaml_payload, updated_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT(storage_key) DO UPDATE SET "
                                + "yaml_payload = excluded.yaml_payload, updated_at = excluded.updated_at")) {
            statement.setString(1, GEM_DATA_KEY);
            statement.setString(2, payload);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save gem data to SQLite", e);
        }
    }

    private File resolveDatabaseFile() {
        String configured = config != null ? config.getString("storage.sqlite.file", DEFAULT_DATABASE)
                : DEFAULT_DATABASE;
        File file = new File(configured);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(plugin.getDataFolder(), configured);
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private void migrateYamlIfEmpty(Connection connection) throws Exception {
        if (hasGemData(connection)) {
            return;
        }
        File yamlFile = new File(plugin.getDataFolder(), "data/gems.yml");
        if (!yamlFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (storage_key, yaml_payload, updated_at) VALUES (?, ?, ?)")) {
            statement.setString(1, GEM_DATA_KEY);
            statement.setString(2, yaml.saveToString());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
            plugin.getLogger().info("Migrated data/gems.yml into SQLite storage.");
        }
    }

    private boolean hasGemData(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + TABLE + " WHERE storage_key = ?")) {
            statement.setString(1, GEM_DATA_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private YamlConfiguration copyToYaml(FileConfiguration source) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (String key : source.getKeys(true)) {
            yaml.set(key, source.get(key));
        }
        return yaml;
    }
}
