package me.hushu.manager;

import java.io.File;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.hushu.PowerGem;
import me.hushu.model.ExecuteConfig;

public class ConfigManager {
    private final PowerGem plugin;

    private FileConfiguration config;
    private FileConfiguration gemsData;
    private File gemsFile;

    // 从 config 里读取的值
    private int requiredCount;
    private ExecuteConfig gemUnionExecute;
    private ExecuteConfig gemScatterExecute;
    private ExecuteConfig powerRevokeExecute;
    private Material gemMaterial;
    private String gemName;            // 宝石名称（可带颜色）
    private Particle gemParticle;      // 宝石粒子特效
    private Sound gemSound;            // 宝石声音
    private boolean useRequiredLoc;   // 是否强制要求放置在指定坐标
    private Location requiredLocCenter; // 存所有必放坐标
    private int requiredLocRadius;

    private Location randomPlaceCorner1; // 随机放置范围的角落1
    private Location randomPlaceCorner2; // 随机放置范围的角落2
    private String language;

    public ConfigManager(PowerGem plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        
        this.language = plugin.getConfig().getString("language", "zh");

        // 1) 读取宝石总数
        this.requiredCount = this.config.getInt("required_count", 5);

        // 2) 读取宝石材质
        String matStr = this.config.getString("gem_material", "RED_STAINED_GLASS").toUpperCase();
        try {
            this.gemMaterial = Material.valueOf(matStr);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[PowerGem] 无效的 gem_material: " + matStr + "，将使用RED_STAINED_GLASS代替。");
            this.gemMaterial = Material.RED_STAINED_GLASS;
        }

        // 3) 读取宝石名称
        String gemNameStr = this.config.getString("gem_name", "&c权力宝石");
        // 需要把 & 转义成 §
        this.gemName = ChatColor.translateAlternateColorCodes('&', gemNameStr);
        // 4) 读取宝石粒子特效
        this.gemParticle = Particle.valueOf(this.config.getString("gem_particle", "FLAME"));
        // 5) 读取宝石声音
        this.gemSound = Sound.valueOf(this.config.getString("gem_sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        // 4) 是否强制要求放置在指定坐标
        this.useRequiredLoc = this.config.getBoolean("use_required_location", false);

        // 如果启用，就读坐标列表
        if (this.useRequiredLoc) {
            ConfigurationSection locsSection = this.config.getConfigurationSection("required_locations");
            String worldName = locsSection.getString("world");
            this.requiredLocRadius = locsSection.getInt("radius");
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                this.requiredLocCenter = getLocationFromConfig(locsSection, "center", w);
            } else {
                plugin.getLogger().warning("读取到无效世界: " + worldName);
            }
            plugin.getLogger().info(String.format("启用指定放置坐标: %.2f %.2f %.2f",
                    this.requiredLocCenter.getX(), this.requiredLocCenter.getY(), this.requiredLocCenter.getZ()));
        } else {
            plugin.getLogger().info("未启用指定坐标放置模式。");
        }

        // 5) 读取随机放置范围
        ConfigurationSection randomPlaceRange = getConfig().getConfigurationSection("random_place_range");
        if (randomPlaceRange == null) {
            plugin.getLogger().severe("配置文件中缺少 random_place_range 部分。");
            return;
        }

        String worldName = randomPlaceRange.getString("world");
        if (worldName == null) {
            plugin.getLogger().severe("random_place_range 中缺少 world 名称。");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().severe("无法找到指定的世界: " + worldName);
            return;
        }

        // 读取 corner1 和 corner2 作为 Location 对象
        this.randomPlaceCorner1 = getLocationFromConfig(randomPlaceRange, "corner1", world);
        this.randomPlaceCorner2 = getLocationFromConfig(randomPlaceRange, "corner2", world);

        if (this.randomPlaceCorner1 == null || this.randomPlaceCorner2 == null) {
            plugin.getLogger().severe("random_place_range 中的 corner1 或 corner2 配置无效。");
            return;
        }

        // 6) 读取情景效果
        this.gemUnionExecute       = loadExecuteConfig("gem_union_execute");
        this.gemScatterExecute     = loadExecuteConfig("gem_scatter_execute");
        this.powerRevokeExecute    = loadExecuteConfig("power_revoke_execute");
    }

    public void initGemFile() {
        gemsFile = new File(this.plugin.getDataFolder(), "powergems.yml");
        if (!gemsFile.exists()) {
            gemsFile.getParentFile().mkdirs();
            this.plugin.saveResource("powergems.yml", false);
        }
    }

    private ExecuteConfig loadExecuteConfig(String path) {
        ExecuteConfig execCfg = new ExecuteConfig(
                this.config.getStringList(path + ".commands"),
                this.config.getString(path + ".sound"),
                this.config.getString(path + ".particle"));
        return execCfg;
    }

    private Location getLocationFromConfig(ConfigurationSection configSection, String path, World world) {
        ConfigurationSection locSection = configSection.getConfigurationSection(path);
        if (locSection == null) {
            plugin.getLogger().severe("配置中缺少 " + path + " 节。");
            return null;
        }

        double x = locSection.getDouble("x");
        double y = locSection.getDouble("y");
        double z = locSection.getDouble("z");

        return new Location(world, x, y, z);
    }

    public void reloadConfigs() {
        plugin.reloadConfig();
        loadConfigs();
    }

    // 把宝石数据写回到 gemsData 并保存
//    public void saveGemData(/* 你需要的映射 */) {
//        try {
//            gemsData.save(gemsFile);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
    public void saveGemData(FileConfiguration data) {
        try {
            data.save(gemsFile);
        } catch (Exception e) {
            e.printStackTrace();
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

    // 读取/写入某些具体数据
    public int getRequiredCount() { return requiredCount; }
    public ExecuteConfig getGemUnionExecute() { return gemUnionExecute; }
    public ExecuteConfig getGemScatterExecute() { return gemScatterExecute; }
    public ExecuteConfig getPowerRevokeExecute() { return powerRevokeExecute; }
    public Location getRandomPlaceCorner1() { return randomPlaceCorner1; }
    public Location getRandomPlaceCorner2() { return randomPlaceCorner2; }
    public Sound getGemSound() { return gemSound; }
    public Particle getGemParticle() { return gemParticle; }
    public Material getGemMaterial() { return gemMaterial; }
    public String getGemName() { return gemName; }
    public boolean isUseRequiredLoc() { return useRequiredLoc; }
    public Location getRequiredLocCenter() { return requiredLocCenter; }
    public int getRequiredLocRadius() { return requiredLocRadius; }
    public FileConfiguration getConfig() { return config; }
    public String getLanguage() { return language; }
}
