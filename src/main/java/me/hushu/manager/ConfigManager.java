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

import me.hushu.RulerGem;
import me.hushu.model.ExecuteConfig;
import me.hushu.model.GemDefinition;

public class ConfigManager {
    private final RulerGem plugin;

    private FileConfiguration config;
    private FileConfiguration gemsData;
    private File gemsFile;

    // 从 config 里读取的值
    private int requiredCount;
    private ExecuteConfig gemUnionExecute;
    private ExecuteConfig gemScatterExecute;
    private ExecuteConfig powerRevokeExecute;
    // 已移除全局默认展示参数，强制使用每颗宝石定义
    private boolean useRequiredLoc;   // 是否强制要求放置在指定坐标
    private Location requiredLocCenter; // 存所有必放坐标
    private int requiredLocRadius;

    private Location randomPlaceCorner1; // 随机放置范围的角落1
    private Location randomPlaceCorner2; // 随机放置范围的角落2
    private String language;
    // Global toggles
    private boolean broadcastRedeemTitle = true;
    // Redeem all presentation
    private java.util.List<String> redeemAllTitle = java.util.Collections.emptyList();
    private Boolean redeemAllBroadcast = null; // null => inherit global
    private String redeemAllSound = "ENTITY_ENDER_DRAGON_GROWL";

    // 每颗宝石的定义（可选）。当存在时，requiredCount 默认等于定义数量
    private java.util.List<GemDefinition> gemDefinitions = new java.util.ArrayList<>();

    // 授权策略
    private boolean inventoryGrantsEnabled;
    private boolean redeemEnabled;
    private boolean fullSetGrantsAllEnabled;

    public ConfigManager(RulerGem plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        
        this.language = plugin.getConfig().getString("language", "zh");

        // 1) 不再读取全局默认，强制使用映射式 gems 定义
        // 4) 是否强制要求放置在指定坐标（兼容两种写法）
        boolean v1 = this.config.getBoolean("use_required_location", false);
        boolean v2 = this.config.getBoolean("use_required_locations", v1);
        this.useRequiredLoc = v2;

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

        // 6) per-gem 定义（必填：强制使用映射/列表形式提供每颗宝石）
        loadGemDefinitions();

        // 宝石总数严格等于定义数量
        this.requiredCount = this.gemDefinitions.size();

        // 7) 授权策略
        ConfigurationSection gp = this.config.getConfigurationSection("grant_policy");
        this.inventoryGrantsEnabled = gp != null && gp.getBoolean("inventory_grants", false);
        this.redeemEnabled = gp == null ? true : gp.getBoolean("redeem_enabled", true);
        this.fullSetGrantsAllEnabled = gp == null ? true : gp.getBoolean("full_set_grants_all", true);

        // 7.1) global toggles
        ConfigurationSection toggles = this.config.getConfigurationSection("toggles");
        if (toggles != null) {
            this.broadcastRedeemTitle = toggles.getBoolean("broadcast_redeem_title", true);
        }

        // 7.2) titles
        ConfigurationSection titles = this.config.getConfigurationSection("titles");
        if (titles != null) {
            ConfigurationSection ra = titles.getConfigurationSection("redeem_all");
            if (ra != null) {
                Object titlesObj = ra.get("titles");
                this.redeemAllTitle = toStringList(titlesObj);
                if (ra.isSet("broadcast")) {
                    this.redeemAllBroadcast = ra.getBoolean("broadcast");
                } else {
                    this.redeemAllBroadcast = null;
                }
                String s = stringOf(ra.get("sound"));
                if (s != null && !s.isEmpty()) this.redeemAllSound = s;
            } else {
                this.redeemAllTitle = java.util.Collections.emptyList();
                this.redeemAllBroadcast = null;
            }
        } else {
            this.redeemAllTitle = java.util.Collections.emptyList();
            this.redeemAllBroadcast = null;
        }

        // 8) 读取情景效果
        this.gemUnionExecute       = loadExecuteConfig("gem_union_execute");
        this.gemScatterExecute     = loadExecuteConfig("gem_scatter_execute");
        // 兼容历史 key：优先 power_revoke_execute，不存在则读取 revoke_power
        ExecuteConfig revoke = loadExecuteConfig("power_revoke_execute");
        if ((revoke == null || (revoke.getCommands() == null && revoke.getSound() == null && revoke.getParticle() == null))
        && this.config.getConfigurationSection("revoke_power") != null) {
            revoke = loadExecuteConfig("revoke_power");
        }
        this.powerRevokeExecute = revoke;
    }

    private void loadGemDefinitions() {
        this.gemDefinitions.clear();
        // 优先使用映射形式（推荐）
        ConfigurationSection sec = this.config.getConfigurationSection("gems");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection sub = sec.getConfigurationSection(key);
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                if (sub != null) {
                    for (String k : sub.getKeys(false)) {
                        m.put(k, sub.get(k));
                    }
                }
                GemDefinition def = buildGemDefinitionFromMap(key, m);
                this.gemDefinitions.add(def);
            }
            return;
        }
        // 兼容列表形式
        java.util.List<java.util.Map<?, ?>> list = this.config.getMapList("gems");
        if (list != null && !list.isEmpty()) {
            int index = 0;
            for (java.util.Map<?, ?> map : list) {
                Object keyObj = map.get("key");
                String key = keyObj != null ? stringOf(keyObj) : String.valueOf(index);
                GemDefinition def = buildGemDefinitionFromMap(key, map);
                this.gemDefinitions.add(def);
                index++;
            }
        }
    }

    private GemDefinition buildGemDefinitionFromMap(String gemKey, java.util.Map<?, ?> map) {
        String matStr = stringOf(map.get("material"));
        Material material = Material.RED_STAINED_GLASS;
        if (matStr != null && !matStr.isEmpty()) {
            try { material = Material.valueOf(matStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        String nameStr = stringOf(map.get("name"));
        String displayName = ChatColor.translateAlternateColorCodes('&', "&c权力宝石");
        if (nameStr != null && !nameStr.isEmpty()) {
            displayName = org.bukkit.ChatColor.translateAlternateColorCodes('&', nameStr);
        }
        String particleStr = stringOf(map.get("particle"));
        Particle particle = Particle.FLAME;
        if (particleStr != null && !particleStr.isEmpty()) {
            try { particle = Particle.valueOf(particleStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        String soundStr = stringOf(map.get("sound"));
        Sound sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        if (soundStr != null && !soundStr.isEmpty()) {
            try { sound = Sound.valueOf(soundStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        // per-gem 事件覆盖（可选）
        ExecuteConfig onPickup = null;
        Object pickup = map.get("on_pickup");
        if (pickup instanceof java.util.Map) {
            onPickup = new ExecuteConfig(
                    toStringList(((java.util.Map<?, ?>) pickup).get("commands")),
                    stringOf(((java.util.Map<?, ?>) pickup).get("sound")),
                    stringOf(((java.util.Map<?, ?>) pickup).get("particle"))
            );
        }
        ExecuteConfig onScatter = null;
        Object scatter = map.get("on_scatter");
        if (scatter instanceof java.util.Map) {
            onScatter = new ExecuteConfig(
                    toStringList(((java.util.Map<?, ?>) scatter).get("commands")),
                    stringOf(((java.util.Map<?, ?>) scatter).get("sound")),
                    stringOf(((java.util.Map<?, ?>) scatter).get("particle"))
            );
        }
        ExecuteConfig onRedeem = null;
        Object redeem = map.get("on_redeem");
        if (redeem instanceof java.util.Map) {
            onRedeem = new ExecuteConfig(
                    toStringList(((java.util.Map<?, ?>) redeem).get("commands")),
                    stringOf(((java.util.Map<?, ?>) redeem).get("sound")),
                    stringOf(((java.util.Map<?, ?>) redeem).get("particle"))
            );
        }
        java.util.List<String> perms = toStringList(map.get("permissions"));
        String group = stringOf(map.get("vault_group"));
        java.util.List<String> lore = toStringList(map.get("lore"));
        // redeem_title: list(1-2 entries) or string
        java.util.List<String> redeemTitle = toStringList(map.get("redeem_title"));
        return new GemDefinition(gemKey, material, displayName, particle, sound, onPickup, onScatter, onRedeem, perms, group, lore, redeemTitle);
    }

    private String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private java.util.List<String> toStringList(Object o) {
        if (o == null) return java.util.Collections.emptyList();
        if (o instanceof java.util.List) {
            java.util.List<?> raw = (java.util.List<?>) o;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object e : raw) {
                if (e != null) out.add(String.valueOf(e));
            }
            return out;
        }
        return java.util.Collections.singletonList(String.valueOf(o));
    }

    public void initGemFile() {
        gemsFile = new File(this.plugin.getDataFolder(), "rulergem.yml");
        if (!gemsFile.exists()) {
            gemsFile.getParentFile().mkdirs();
            this.plugin.saveResource("rulergem.yml", false);
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
    // 全局展示参数已移除对应 getter
    public boolean isUseRequiredLoc() { return useRequiredLoc; }
    public Location getRequiredLocCenter() { return requiredLocCenter; }
    public int getRequiredLocRadius() { return requiredLocRadius; }
    public FileConfiguration getConfig() { return config; }
    public String getLanguage() { return language; }
    public java.util.List<GemDefinition> getGemDefinitions() { return gemDefinitions; }
    public boolean isInventoryGrantsEnabled() { return inventoryGrantsEnabled; }
    public boolean isRedeemEnabled() { return redeemEnabled; }
    public boolean isFullSetGrantsAllEnabled() { return fullSetGrantsAllEnabled; }
    public boolean isBroadcastRedeemTitle() { return broadcastRedeemTitle; }
    public java.util.List<String> getRedeemAllTitle() { return redeemAllTitle; }
    public Boolean getRedeemAllBroadcastOverride() { return redeemAllBroadcast; }
    public String getRedeemAllSound() { return redeemAllSound; }
}
