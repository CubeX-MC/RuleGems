package org.cubexmc.manager;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.update.ConfigUpdater;

/**
 * ConfigManager — 配置协调器。
 * <p>
 * 负责文件 I/O、配置加载编排、以及跨域查询方法。
 * 解析逻辑委托给 {@link GemDefinitionParser}，
 * 运行时游戏设置存储在 {@link GameplayConfig}。
 */
public class ConfigManager {

    private final RuleGems plugin;
    private final LanguageManager languageManager;

    private FileConfiguration config;
    private FileConfiguration gemsData;
    private File gemsFile;
    private String language;

    // 内部委托对象
    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;

    public ConfigManager(RuleGems plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;
        this.gemParser = new GemDefinitionParser(plugin.getLogger(), languageManager);
        this.gameplayConfig = new GameplayConfig();
    }

    // ==================== 公共访问器 ====================

    /** 宝石定义解析器 */
    public GemDefinitionParser getGemParser() { return gemParser; }

    /** 运行时游戏玩法配置 */
    public GameplayConfig getGameplayConfig() { return gameplayConfig; }

    public FileConfiguration getConfig() { return config; }

    public String getLanguage() { return language; }

    // ==================== 加载 / 重载 ====================

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        ConfigUpdater.merge(plugin);
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 确保默认资源存在
        ensurePowersFolder();
        initGemsFolder();

        this.language = config.getString("language", "zh");

        // 1) 加载权力结构模板
        gemParser.loadPowerTemplates(plugin.getDataFolder());

        // 2) 校验全局随机放置范围（提前退出逻辑保留，与旧版行为一致）
        ConfigurationSection randomPlaceRange = config.getConfigurationSection("random_place_range");
        if (randomPlaceRange == null) {
            languageManager.logMessage("config.missing_random_place");
            return;
        }
        String worldName = randomPlaceRange.getString("world");
        if (worldName == null) {
            languageManager.logMessage("config.missing_world_name");
            return;
        }
        World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            java.util.Map<String, String> ph = new java.util.HashMap<>();
            ph.put("world", worldName);
            languageManager.logMessage("config.world_not_found", ph);
            return;
        }
        Location c1 = getLocationFromConfig(randomPlaceRange, "corner1", world);
        Location c2 = getLocationFromConfig(randomPlaceRange, "corner2", world);
        if (c1 == null || c2 == null) {
            languageManager.logMessage("config.invalid_corners");
            return;
        }

        // 3) 加载宝石定义
        gemParser.loadGemDefinitions(config, plugin.getDataFolder());

        // 4) 加载游戏玩法配置（GameplayConfig 原地刷新）
        gameplayConfig.loadFrom(config, gemParser, languageManager, plugin.getLogger(),
                this::getLocationFromConfig);
    }

    public void reloadConfigs() {
        loadConfigs();
    }

    // ==================== 数据文件 I/O ====================

    public void initGemFile() {
        File dataFolder = new File(this.plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        gemsFile = new File(dataFolder, "gems.yml");

        // 迁移旧数据文件
        File oldDataFile = new File(this.plugin.getDataFolder(), "data.yml");
        if (oldDataFile.exists() && !gemsFile.exists()) {
            try {
                java.nio.file.Files.move(oldDataFile.toPath(), gemsFile.toPath());
                plugin.getLogger().info("Migrated data.yml to data/gems.yml");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to migrate data.yml: " + e.getMessage());
            }
        }

        if (!gemsFile.exists()) {
            try {
                gemsFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create data/gems.yml: " + e.getMessage());
            }
        }
    }

    public void saveGemData(FileConfiguration data) {
        try {
            data.save(gemsFile);
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to save gem data", e);
        }
    }

    public FileConfiguration readGemsData() {
        initGemFile();
        gemsData = YamlConfiguration.loadConfiguration(gemsFile);
        return gemsData;
    }

    public FileConfiguration getGemsData() {
        if (gemsData == null) {
            readGemsData();
        }
        return gemsData;
    }

    // ==================== 跨域查询 ====================

    /**
     * 收集所有已配置的 allowed-command label（供 proxy 注册）。
     * 需要访问 gemParser（宝石定义）和 gameplayConfig（redeem_all power）。
     */
    public Set<String> collectAllowedCommandLabels() {
        Set<String> labels = new LinkedHashSet<>();
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs != null) {
            for (GemDefinition def : defs) {
                if (def == null || def.getAllowedCommands() == null) continue;
                for (AllowedCommand cmd : def.getAllowedCommands()) {
                    if (cmd == null) continue;
                    String label = cmd.getLabel();
                    if (label != null && !label.isEmpty()) {
                        labels.add(label.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        PowerStructure raPower = gameplayConfig.getRedeemAllPowerStructure();
        if (raPower != null && raPower.getAllowedCommands() != null) {
            for (AllowedCommand cmd : raPower.getAllowedCommands()) {
                if (cmd == null) continue;
                String label = cmd.getLabel();
                if (label != null && !label.isEmpty()) {
                    labels.add(label.toLowerCase(Locale.ROOT));
                }
            }
        }
        return labels;
    }

    // ==================== 内部辅助 ====================

    private void initGemsFolder() {
        File gemsFolder = new File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) {
            gemsFolder.mkdirs();
            plugin.getLogger().info("Creating gems folder");
        }
        File defaultGemsFile = new File(gemsFolder, "gems.yml");
        if (!defaultGemsFile.exists()) {
            try {
                plugin.saveResource("gems/gems.yml", false);
                plugin.getLogger().info("Creating default gem config file: gems/gems.yml");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to copy default gems.yml file: " + e.getMessage());
            }
        }
    }

    private void ensurePowersFolder() {
        File powersFolder = new File(plugin.getDataFolder(), "powers");
        if (!powersFolder.exists()) {
            powersFolder.mkdirs();
            plugin.saveResource("powers/powers.yml", false);
        }
    }

    private Location getLocationFromConfig(ConfigurationSection configSection, String path, World world) {
        ConfigurationSection locSection = configSection.getConfigurationSection(path);
        if (locSection == null) {
            plugin.getLogger().severe("Missing section '" + path + "' in configuration.");
            return null;
        }
        double x = locSection.getDouble("x");
        double y = locSection.getDouble("y");
        double z = locSection.getDouble("z");
        return new Location(world, x, y, z);
    }
}
