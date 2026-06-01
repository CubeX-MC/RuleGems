package org.cubexmc.storage;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Storage boundary for mutable RuleGems runtime data.
 */
public interface StorageProvider {

    String getName();

    void initialize();

    FileConfiguration readGemData();

    void saveGemData(FileConfiguration data);
}
