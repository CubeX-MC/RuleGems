package org.cubexmc.storage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;

/**
 * Default YAML-backed storage provider preserving the existing data/gems.yml
 * format.
 */
public class YamlStorageProvider implements StorageProvider {

    private final RuleGems plugin;
    private File gemsFile;

    public YamlStorageProvider(RuleGems plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "yaml";
    }

    @Override
    public void initialize() {
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        gemsFile = new File(dataFolder, "gems.yml");
        migrateLegacyDataFile();
        if (!gemsFile.exists()) {
            try {
                gemsFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create data/gems.yml: " + e.getMessage());
            }
        }
    }

    @Override
    public FileConfiguration readGemData() {
        initialize();
        return YamlConfiguration.loadConfiguration(gemsFile);
    }

    @Override
    public void saveGemData(FileConfiguration data) {
        initialize();
        // 原子落盘：先写同目录临时文件，再 ATOMIC_MOVE 覆盖，避免写一半崩溃导致 gems.yml 被截断/损坏。
        try {
            File parent = gemsFile.getParentFile();
            File temp = File.createTempFile("gems", ".tmp", parent);
            try {
                data.save(temp);
                Files.move(temp.toPath(), gemsFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(temp.toPath());
                throw e;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save gem data", e);
        }
    }

    private void migrateLegacyDataFile() {
        File oldDataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!oldDataFile.exists() || gemsFile.exists()) {
            return;
        }
        try {
            Files.move(oldDataFile.toPath(), gemsFile.toPath());
            plugin.getLogger().info("Migrated data.yml to data/gems.yml");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to migrate data.yml: " + e.getMessage());
        }
    }
}
