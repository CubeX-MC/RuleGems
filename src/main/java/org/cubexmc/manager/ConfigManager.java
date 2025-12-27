package org.cubexmc.manager;

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
import org.bukkit.potion.PotionEffectType;
import org.cubexmc.RuleGems;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.update.ConfigUpdater;

public class ConfigManager {
    private final RuleGems plugin;

    private FileConfiguration config;
    private FileConfiguration gemsData;
    private File gemsFile;
    
    // 权力结构模板
    private final java.util.Map<String, PowerStructure> powerTemplates = new java.util.HashMap<>();

    // 从 config 里读取的值
    private int requiredCount;
    private ExecuteConfig gemScatterExecute;
    // 已移除全局默认展示参数，强制使用每颗宝石定义

    private Location randomPlaceCorner1; // 随机放置范围的角落1
    private Location randomPlaceCorner2; // 随机放置范围的角落2
    private String language;
    // Global toggles
    private boolean broadcastRedeemTitle = true;
    // Redeem all presentation
    private java.util.List<String> redeemAllTitle = java.util.Collections.emptyList();
    private Boolean redeemAllBroadcast = null; // null => inherit global
    private String redeemAllSound = "ENTITY_ENDER_DRAGON_GROWL";
    // Redeem all extra grants
    private java.util.List<String> redeemAllPermissions = java.util.Collections.emptyList();
    private java.util.List<org.cubexmc.model.AllowedCommand> redeemAllAllowed = java.util.Collections.emptyList();

    // 每颗宝石的定义（可选）。当存在时，requiredCount 默认等于定义数量
    private java.util.List<GemDefinition> gemDefinitions = new java.util.ArrayList<>();

    // 授权策略
    private boolean inventoryGrantsEnabled;
    private boolean redeemEnabled;
    private boolean fullSetGrantsAllEnabled;

    // 宝石逃逸配置
    private boolean gemEscapeEnabled;
    private long gemEscapeMinIntervalTicks;  // 存储为 tick 单位
    private long gemEscapeMaxIntervalTicks;  // 存储为 tick 单位
    private boolean gemEscapeBroadcast;
    private String gemEscapeSound;
    private String gemEscapeParticle;

    // 放置兑换（祭坛模式）配置
    private boolean placeRedeemEnabled;
    // 长按右键兑换
    private boolean holdToRedeemEnabled;
    // 是否需要下蹲才能触发长按兑换（true=下蹲兑换，普通放置；false=普通兑换，下蹲放置）
    private boolean sneakToRedeem;
    // 长按时长（tick）
    private int holdToRedeemDurationTicks;
    private int placeRedeemRadius;
    private String placeRedeemSound;
    private String placeRedeemParticle;
    private boolean placeRedeemBeaconBeam;
    private int placeRedeemBeaconDuration;

    public ConfigManager(RuleGems plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        ConfigUpdater.merge(plugin);
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        
        // 加载权力结构模板
        loadPowerTemplates();
        
        // 确保 gems 文件夹存在并复制默认配置
        initGemsFolder();
        
        this.language = plugin.getConfig().getString("language", "zh");

        // 读取随机放置范围
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

        // 宝石总数严格等于 sum(count)
        int total = 0;
        for (GemDefinition d : this.gemDefinitions) total += (d != null ? d.getCount() : 0);
        this.requiredCount = Math.max(0, total);

        // 7) 授权策略
        ConfigurationSection gp = this.config.getConfigurationSection("grant_policy");
        this.inventoryGrantsEnabled = gp != null && gp.getBoolean("inventory_grants", false);
        this.redeemEnabled = gp == null || gp.getBoolean("redeem_enabled", true);
        this.fullSetGrantsAllEnabled = gp == null || gp.getBoolean("full_set_grants_all", true);
        this.placeRedeemEnabled = gp != null && gp.getBoolean("place_redeem_enabled", false);
        // 兼容旧配置: hold_to_redeem 可能在 grant_policy 或者 hold_to_redeem_enabled
        this.holdToRedeemEnabled = gp != null && (gp.getBoolean("hold_to_redeem_enabled", gp.getBoolean("hold_to_redeem", true)));
        
        // 从独立的 hold_to_redeem 配置块读取，同时兼容旧的 grant_policy.sneak_to_redeem
        ConfigurationSection htr = this.config.getConfigurationSection("hold_to_redeem");
        if (htr != null) {
            this.sneakToRedeem = htr.getBoolean("sneak_to_redeem", true);
            // 读取长按时长（秒），转换为tick（1秒=20tick）
            double durationSeconds = htr.getDouble("duration", 3.0);
            this.holdToRedeemDurationTicks = (int) (durationSeconds * 20);
        } else {
            // 兼容旧配置
            this.sneakToRedeem = gp == null || gp.getBoolean("sneak_to_redeem", true);
            this.holdToRedeemDurationTicks = 60; // 默认3秒
        }

        // 7.1) global toggles
        ConfigurationSection toggles = this.config.getConfigurationSection("toggles");
        if (toggles != null) {
            this.broadcastRedeemTitle = toggles.getBoolean("broadcast_redeem_title", true);
        }

        // 7.2) redeem_all at root (replaces previous titles.redeem_all)
        ConfigurationSection ra = this.config.getConfigurationSection("redeem_all");
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
            // extras for redeem_all
            this.redeemAllPermissions = toStringList(ra.get("permissions"));
            this.redeemAllAllowed = parseAllowedCommands(ra.get("command_allows"));
        } else {
            this.redeemAllTitle = java.util.Collections.emptyList();
            this.redeemAllBroadcast = null;
            this.redeemAllPermissions = java.util.Collections.emptyList();
            this.redeemAllAllowed = java.util.Collections.emptyList();
        }

        // 8) 读取情景效果
        this.gemScatterExecute     = loadExecuteConfig("gem_scatter_execute");

        // 9) 宝石逃逸配置
        ConfigurationSection escapeSection = this.config.getConfigurationSection("gem_escape");
        if (escapeSection != null) {
            this.gemEscapeEnabled = escapeSection.getBoolean("enabled", false);
            this.gemEscapeMinIntervalTicks = parseTimeToTicks(escapeSection.getString("min_interval", "30m"));
            this.gemEscapeMaxIntervalTicks = parseTimeToTicks(escapeSection.getString("max_interval", "2h"));
            this.gemEscapeBroadcast = escapeSection.getBoolean("broadcast", true);
            this.gemEscapeSound = escapeSection.getString("sound", "ENTITY_ENDERMAN_TELEPORT");
            this.gemEscapeParticle = escapeSection.getString("particle", "PORTAL");
        } else {
            this.gemEscapeEnabled = false;
            this.gemEscapeMinIntervalTicks = 30 * 60 * 20L;  // 30分钟
            this.gemEscapeMaxIntervalTicks = 2 * 60 * 60 * 20L;  // 2小时
            this.gemEscapeBroadcast = true;
            this.gemEscapeSound = "ENTITY_ENDERMAN_TELEPORT";
            this.gemEscapeParticle = "PORTAL";
        }
        // 确保 min <= max
        if (this.gemEscapeMinIntervalTicks > this.gemEscapeMaxIntervalTicks) {
            long tmp = this.gemEscapeMinIntervalTicks;
            this.gemEscapeMinIntervalTicks = this.gemEscapeMaxIntervalTicks;
            this.gemEscapeMaxIntervalTicks = tmp;
        }
        // 确保最小间隔至少 1 秒
        if (this.gemEscapeMinIntervalTicks < 20L) {
            this.gemEscapeMinIntervalTicks = 20L;
        }

        // 10) 放置兑换（祭坛模式）全局设置
        ConfigurationSection prSection = this.config.getConfigurationSection("place_redeem");
        if (prSection != null) {
            this.placeRedeemRadius = prSection.getInt("radius", 1);
            this.placeRedeemSound = prSection.getString("sound", "BLOCK_BEACON_ACTIVATE");
            this.placeRedeemParticle = prSection.getString("particle", "TOTEM");
            this.placeRedeemBeaconBeam = prSection.getBoolean("beacon_beam", true);
            this.placeRedeemBeaconDuration = prSection.getInt("beacon_beam_duration", 5);
        } else {
            this.placeRedeemRadius = 1;
            this.placeRedeemSound = "BLOCK_BEACON_ACTIVATE";
            this.placeRedeemParticle = "TOTEM";
            this.placeRedeemBeaconBeam = true;
            this.placeRedeemBeaconDuration = 5;
        }
    }

    private void loadGemDefinitions() {
        this.gemDefinitions.clear();
        
        // 优先从 gems 文件夹加载
        File gemsFolder = new File(plugin.getDataFolder(), "gems");
        if (gemsFolder.exists() && gemsFolder.isDirectory()) {
            loadGemsFromFolder(gemsFolder);
            if (!this.gemDefinitions.isEmpty()) {
                plugin.getLogger().info("从 gems 文件夹加载了 " + this.gemDefinitions.size() + " 个宝石配置");
                return;
            }
        }
        
        // 兼容旧配置：从 config.yml 读取
        ConfigurationSection sec = this.config.getConfigurationSection("gems");
        if (sec != null) {
            plugin.getLogger().warning("检测到旧版配置格式！建议将宝石配置迁移到 gems 文件夹");
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
            plugin.getLogger().warning("检测到旧版列表配置格式！建议将宝石配置迁移到 gems 文件夹");
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
    
    /**
     * 递归加载 gems 文件夹中的所有 .yml 文件
     */
    private void loadGemsFromFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归加载子文件夹
                loadGemsFromFolder(file);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
                // 加载 yml 文件
                loadGemsFromFile(file);
            }
        }
    }
    
    /**
     * 从单个 yml 文件加载宝石配置
     */
    private void loadGemsFromFile(File file) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            
            // 遍历文件中的所有顶级键
            for (String gemKey : yaml.getKeys(false)) {
                ConfigurationSection gemSection = yaml.getConfigurationSection(gemKey);
                if (gemSection == null) continue;
                
                // 转换为 Map
                java.util.Map<String, Object> gemMap = new java.util.HashMap<>();
                for (String key : gemSection.getKeys(false)) {
                    gemMap.put(key, gemSection.get(key));
                }
                
                // 构建宝石定义
                GemDefinition def = buildGemDefinitionFromMap(gemKey, gemMap);
                this.gemDefinitions.add(def);
                
                plugin.getLogger().info("从文件 " + file.getName() + " 加载宝石: " + gemKey);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("加载宝石配置文件 " + file.getName() + " 失败: " + e.getMessage());
            e.printStackTrace();
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
        // 注：permissions、vault_group、command_allows 由 parsePowerStructure 统一解析
        java.util.List<String> lore = toStringList(map.get("lore"));
        // redeem_title: list(1-2 entries) or string
        java.util.List<String> redeemTitle = toStringList(map.get("redeem_title"));
        // optional: visual enchanted glint
        boolean enchanted = false;
        Object encObj = map.get("enchanted");
        if (encObj instanceof Boolean) {
            enchanted = (Boolean) encObj;
        } else if (encObj != null) {
            // accept string-like truthy
            String s = String.valueOf(encObj).trim();
            enchanted = s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
        }
        java.util.List<String> mutex = toStringList(map.get("mutual_exclusive"));
        int count = 1;
        Object cObj = map.get("count");
        if (cObj != null) {
            try { count = Integer.parseInt(String.valueOf(cObj)); } catch (Exception ignored) {}
        }
        
        // 解析宝石特定的随机生成范围（可选）
        Location corner1 = null;
        Location corner2 = null;
        Object rangeObj = map.get("random_place_range");
        if (rangeObj instanceof java.util.Map) {
            java.util.Map<?, ?> rangeMap = (java.util.Map<?, ?>) rangeObj;
            String worldName = stringOf(rangeMap.get("world"));
            if (worldName != null && !worldName.isEmpty()) {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Object c1Obj = rangeMap.get("corner1");
                    Object c2Obj = rangeMap.get("corner2");
                    if (c1Obj instanceof java.util.Map && c2Obj instanceof java.util.Map) {
                        corner1 = parseLocationFromMap((java.util.Map<?, ?>) c1Obj, world);
                        corner2 = parseLocationFromMap((java.util.Map<?, ?>) c2Obj, world);
                    }
                } else {
                    plugin.getLogger().warning("宝石 " + gemKey + " 的生成范围配置中找不到世界: " + worldName);
                }
            }
        }

        // 解析放置兑换祭坛位置（可选）
        Location altarLocation = null;
        Object altarObj = map.get("altar");
        if (altarObj instanceof java.util.Map) {
            java.util.Map<?, ?> altarMap = (java.util.Map<?, ?>) altarObj;
            String altarWorldName = stringOf(altarMap.get("world"));
            if (altarWorldName != null && !altarWorldName.isEmpty()) {
                World altarWorld = Bukkit.getWorld(altarWorldName);
                if (altarWorld != null) {
                    altarLocation = parseLocationFromMap(altarMap, altarWorld);
                } else {
                    plugin.getLogger().warning("宝石 " + gemKey + " 的祭坛配置中找不到世界: " + altarWorldName);
                }
            }
        }
        
        // 解析 PowerStructure (支持模板引用)
        PowerStructure powerStructure = null;
        Object powerObj = map.get("power");
        if (powerObj != null) {
            powerStructure = parsePowerStructure(powerObj);
            // 同时也尝试解析根节点的旧格式配置并合并 (防止 ConfigUpdater 添加了默认 power 导致旧配置失效)
            PowerStructure rootStructure = parsePowerStructure(map);
            if (rootStructure.hasAnyContent()) {
                powerStructure.merge(rootStructure);
            }
        } else {
            // 兼容旧格式：直接在根节点解析
            powerStructure = parsePowerStructure(map);
        }

        // 使用 PowerStructure 构造 GemDefinition
        GemDefinition def = new GemDefinition(gemKey, material, displayName, particle, sound, onPickup, onScatter, onRedeem, powerStructure, lore, redeemTitle, enchanted, mutex, count, corner1, corner2, altarLocation);
        return def;
    }
    
    /**
     * 从 Map 解析 Location（仅坐标，世界已知）
     */
    private Location parseLocationFromMap(java.util.Map<?, ?> map, World world) {
        try {
            double x = 0, y = 0, z = 0;
            Object xObj = map.get("x");
            Object yObj = map.get("y");
            Object zObj = map.get("z");
            if (xObj != null) x = Double.parseDouble(String.valueOf(xObj));
            if (yObj != null) y = Double.parseDouble(String.valueOf(yObj));
            if (zObj != null) z = Double.parseDouble(String.valueOf(zObj));
            return new Location(world, x, y, z);
        } catch (Exception e) {
            plugin.getLogger().warning("解析 Location 失败: " + e.getMessage());
            return null;
        }
    }

    private java.util.List<org.cubexmc.model.AllowedCommand> parseAllowedCommands(Object allowsObj) {
        java.util.List<org.cubexmc.model.AllowedCommand> allowed = new java.util.ArrayList<>();
        if (allowsObj instanceof java.util.List) {
            java.util.List<?> raw = (java.util.List<?>) allowsObj;
            for (Object e : raw) {
                if (e instanceof java.util.Map) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) e;
                    
                    // 唯一格式：command（玩家输入）+ execute（实际执行）
                    Object commandObj = map.get("command");
                    Object executeObj = map.get("execute");
                    Object uses = map.get("time_limit");
                    Object cooldownObj = map.get("cooldown");
                    
                    if (commandObj == null || executeObj == null) {
                        plugin.getLogger().warning("command_allows 配置错误：必须包含 'command' 和 'execute' 字段");
                        continue;
                    }
                    
                    int u = -1;  // 默认无限
                    int cooldown = 0;
                    try { u = Integer.parseInt(String.valueOf(uses)); } catch (Exception ignored) {}
                    try { cooldown = Integer.parseInt(String.valueOf(cooldownObj)); } catch (Exception ignored) {}
                    
                    // 解析命令标签（移除斜杠）
                    String cmd = String.valueOf(commandObj).trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    
                    // 提取第一个单词作为label（不包含参数）
                    String label = cmd.split("\\s+")[0].toLowerCase();
                    
                    // 解析执行命令列表
                    java.util.List<String> executeCmds = toStringList(executeObj);
                    allowed.add(new org.cubexmc.model.AllowedCommand(label, u, executeCmds, cooldown));
                }
            }
        }
        return allowed;
    }

    /**
     * 解析药水效果配置
     * 支持两种格式:
     * 1. 简单格式: ["SPEED", "NIGHT_VISION"]
     * 2. 详细格式: [{type: "SPEED", amplifier: 1, particles: false}, ...]
     */
    private java.util.List<EffectConfig> parseEffects(Object effectsObj) {
        java.util.List<EffectConfig> effects = new java.util.ArrayList<>();
        if (effectsObj == null) return effects;
        
        if (effectsObj instanceof java.util.List) {
            java.util.List<?> raw = (java.util.List<?>) effectsObj;
            for (Object e : raw) {
                if (e instanceof String) {
                    // 简单格式: 只有效果类型名称
                    String typeName = ((String) e).toUpperCase().trim();
                    PotionEffectType type = PotionEffectType.getByName(typeName);
                    if (type != null) {
                        effects.add(new EffectConfig(type));
                    } else {
                        plugin.getLogger().warning("未知的药水效果类型: " + typeName);
                    }
                } else if (e instanceof java.util.Map) {
                    // 详细格式
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) e;
                    String typeName = stringOf(map.get("type"));
                    if (typeName == null || typeName.isEmpty()) {
                        plugin.getLogger().warning("effects 配置错误：缺少 'type' 字段");
                        continue;
                    }
                    
                    PotionEffectType type = PotionEffectType.getByName(typeName.toUpperCase().trim());
                    if (type == null) {
                        plugin.getLogger().warning("未知的药水效果类型: " + typeName);
                        continue;
                    }
                    
                    // 解析可选参数
                    int amplifier = 0;
                    Object ampObj = map.get("amplifier");
                    if (ampObj != null) {
                        try { amplifier = Integer.parseInt(String.valueOf(ampObj)); } catch (Exception ignored) {}
                    }
                    
                    boolean ambient = false;
                    Object ambObj = map.get("ambient");
                    if (ambObj instanceof Boolean) {
                        ambient = (Boolean) ambObj;
                    } else if (ambObj != null) {
                        ambient = "true".equalsIgnoreCase(String.valueOf(ambObj));
                    }
                    
                    boolean particles = true;
                    Object partObj = map.get("particles");
                    if (partObj instanceof Boolean) {
                        particles = (Boolean) partObj;
                    } else if (partObj != null) {
                        particles = !"false".equalsIgnoreCase(String.valueOf(partObj));
                    }
                    
                    boolean icon = true;
                    Object iconObj = map.get("icon");
                    if (iconObj instanceof Boolean) {
                        icon = (Boolean) iconObj;
                    } else if (iconObj != null) {
                        icon = !"false".equalsIgnoreCase(String.valueOf(iconObj));
                    }
                    
                    effects.add(new EffectConfig(type, amplifier, ambient, particles, icon));
                }
            }
        }
        return effects;
    }
    
    private String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 解析时间字符串为 tick 数量
     * 支持的单位: s(秒), m(分钟), h(小时), d(天)
     * 示例: "30m", "2h", "1d", "90s"
     * 如果没有单位，默认为分钟
     */
    private long parseTimeToTicks(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return 30 * 60 * 20L; // 默认 30 分钟
        }
        
        timeStr = timeStr.trim().toLowerCase();
        
        // 尝试解析带单位的格式
        long multiplier = 60 * 20L; // 默认单位：分钟 -> tick
        String numPart = timeStr;
        
        if (timeStr.endsWith("s")) {
            multiplier = 20L; // 秒 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("m")) {
            multiplier = 60 * 20L; // 分钟 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("h")) {
            multiplier = 60 * 60 * 20L; // 小时 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("d")) {
            multiplier = 24 * 60 * 60 * 20L; // 天 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        }
        
        try {
            double value = Double.parseDouble(numPart.trim());
            return (long) (value * multiplier);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("无法解析时间格式: " + timeStr + "，使用默认值 30m");
            return 30 * 60 * 20L;
        }
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

    /**
     * 初始化 gems 文件夹并复制默认配置
     */
    private void initGemsFolder() {
        File gemsFolder = new File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) {
            gemsFolder.mkdirs();
            plugin.getLogger().info("创建 gems 文件夹");
        }
        
        // 复制默认的 gems.yml 文件
        File defaultGemsFile = new File(gemsFolder, "gems.yml");
        if (!defaultGemsFile.exists()) {
            try {
                plugin.saveResource("gems/gems.yml", false);
                plugin.getLogger().info("创建默认宝石配置文件: gems/gems.yml");
            } catch (Exception e) {
                plugin.getLogger().warning("无法复制默认 gems.yml 文件: " + e.getMessage());
            }
        }
    }
    
    public void initGemFile() {
        // 数据文件统一放到 data 文件夹
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
        loadConfigs();
    }

    // 把宝石数据写回到 gemsData 并保存
//    public void saveGemData(/* 你需要的映射 */) {
//        try {
//            gemsData.save(gemsFile);
//        } catch (Exception e) {
//            e.printStackTrace();
    /**
     * 加载权力结构模板
     */
    private void loadPowerTemplates() {
        powerTemplates.clear();
        
        // 加载 powers 文件夹下的所有文件（结构与 gems 文件夹一致）
        File powersFolder = new File(plugin.getDataFolder(), "powers");
        if (!powersFolder.exists()) {
            powersFolder.mkdirs();
            plugin.saveResource("powers/powers.yml", false);
        }
        
        if (powersFolder.isDirectory()) {
            loadPowerTemplatesFromFolder(powersFolder);
        }
        
        plugin.getLogger().info("Loaded " + powerTemplates.size() + " power templates.");
    }
    
    private void loadPowerTemplatesFromFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                loadPowerTemplatesFromFolder(file);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
                loadPowerTemplatesFromFile(file);
            }
        }
    }
    
    private void loadPowerTemplatesFromFile(File file) {
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            // 检查是否有 templates 根节点
            ConfigurationSection templatesSection = config.getConfigurationSection("templates");
            if (templatesSection != null) {
                for (String key : templatesSection.getKeys(false)) {
                    Object templateObj = templatesSection.get(key);
                    if (templateObj instanceof ConfigurationSection || templateObj instanceof java.util.Map) {
                        PowerStructure structure = parsePowerStructure(templateObj);
                        powerTemplates.put(key, structure);
                    }
                }
            } else {
                // 如果没有 templates 节点，假设整个文件是多个模板的集合（根键即模板名）
                for (String key : config.getKeys(false)) {
                    Object templateObj = config.get(key);
                    if (templateObj instanceof ConfigurationSection || templateObj instanceof java.util.Map) {
                        PowerStructure structure = parsePowerStructure(templateObj);
                        powerTemplates.put(key, structure);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load power templates from " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * 获取权力结构模板
     */
    public PowerStructure getPowerTemplate(String name) {
        return powerTemplates.get(name);
    }

    /**
     * 解析权力结构
     * 支持从模板引用、Map解析、或混合模式
     */
    public PowerStructure parsePowerStructure(Object obj) {
        if (obj == null) return new PowerStructure();
        
        // 1. 字符串引用模板
        if (obj instanceof String) {
            String templateName = (String) obj;
            PowerStructure template = powerTemplates.get(templateName);
            if (template != null) {
                return template.copy();
            } else {
                plugin.getLogger().warning("Unknown power template: " + templateName);
                return new PowerStructure();
            }
        }

        // 1.5 列表引用模板 (组合多个模板)
        if (obj instanceof java.util.List) {
            PowerStructure combined = new PowerStructure();
            java.util.List<?> list = (java.util.List<?>) obj;
            for (Object item : list) {
                if (item instanceof String) {
                    String templateName = (String) item;
                    PowerStructure template = powerTemplates.get(templateName);
                    if (template != null) {
                        combined.merge(template);
                    } else {
                        plugin.getLogger().warning("Unknown power template in list: " + templateName);
                    }
                } else if (item instanceof java.util.Map || item instanceof ConfigurationSection) {
                    // 允许列表中包含内联定义
                    PowerStructure inline = parsePowerStructure(item);
                    combined.merge(inline);
                }
            }
            return combined;
        }
        
        // 2. Map 解析
        if (obj instanceof java.util.Map || obj instanceof ConfigurationSection) {
            java.util.Map<?, ?> map;
            if (obj instanceof ConfigurationSection) {
                map = ((ConfigurationSection) obj).getValues(false);
            } else {
                map = (java.util.Map<?, ?>) obj;
            }
            
            PowerStructure structure = new PowerStructure();
            
            // 检查是否有 base/template 字段用于继承
            Object baseObj = map.get("base");
            if (baseObj == null) baseObj = map.get("template");
            
            if (baseObj instanceof String) {
                PowerStructure template = powerTemplates.get((String) baseObj);
                if (template != null) {
                    structure = template.copy();
                } else {
                    plugin.getLogger().warning("Unknown base power template: " + baseObj);
                }
            } else if (baseObj instanceof java.util.List) {
                // 支持多重继承
                for (Object item : (java.util.List<?>) baseObj) {
                    if (item instanceof String) {
                        PowerStructure template = powerTemplates.get((String) item);
                        if (template != null) {
                            structure.merge(template);
                        } else {
                            plugin.getLogger().warning("Unknown base power template in list: " + item);
                        }
                    }
                }
            }
            
            // 解析并合并属性
            java.util.List<String> perms = toStringList(map.get("permissions"));
            if (!perms.isEmpty()) {
                structure.getPermissions().addAll(perms);
            }
            
            String group = stringOf(map.get("vault_group"));
            if (group != null && !group.isEmpty()) {
                structure.getVaultGroups().add(group);
            }
            
            java.util.List<String> groups = toStringList(map.get("vault_groups"));
            if (!groups.isEmpty()) {
                structure.getVaultGroups().addAll(groups);
            }
            
            java.util.List<org.cubexmc.model.AllowedCommand> allowed = parseAllowedCommands(map.get("command_allows"));
            if (!allowed.isEmpty()) {
                structure.getAllowedCommands().addAll(allowed);
            }
            
            java.util.List<EffectConfig> effects = parseEffects(map.get("effects"));
            if (!effects.isEmpty()) {
                structure.getEffects().addAll(effects);
            }
            
            // 解析委任 (Appoints)
            Object appointsObj = map.get("appoints");
            if (appointsObj instanceof java.util.Map || appointsObj instanceof ConfigurationSection) {
                java.util.Map<?, ?> appointsMap = (appointsObj instanceof ConfigurationSection) ? 
                    ((ConfigurationSection) appointsObj).getValues(false) : (java.util.Map<?, ?>) appointsObj;
                
                for (java.util.Map.Entry<?, ?> entry : appointsMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    org.cubexmc.model.AppointDefinition def = parseAppointDefinition(key, entry.getValue());
                    if (def != null) {
                        structure.getAppoints().put(key, def);
                    }
                }
            }
            
            // 解析条件
            Object condObj = map.get("conditions");
            if (condObj instanceof java.util.Map || condObj instanceof ConfigurationSection) {
                // 这里简化处理，直接覆盖条件（因为条件合并比较复杂）
                // 如果需要合并条件，可以在 PowerCondition 中实现 merge 方法
                org.cubexmc.model.PowerCondition condition = parseCondition(condObj);
                structure.setCondition(condition);
            }
            
            return structure;
        }
        
        return new PowerStructure();
    }

    /**
     * 解析委任定义
     */
    private org.cubexmc.model.AppointDefinition parseAppointDefinition(String key, Object obj) {
        if (obj == null) return null;
        
        java.util.Map<?, ?> map;
        if (obj instanceof ConfigurationSection) {
            map = ((ConfigurationSection) obj).getValues(false);
        } else if (obj instanceof java.util.Map) {
            map = (java.util.Map<?, ?>) obj;
        } else {
            return null;
        }
        
        org.cubexmc.model.AppointDefinition def = new org.cubexmc.model.AppointDefinition(key);
        
        // 基本属性
        def.setDisplayName(stringOf(map.get("display_name")));
        def.setDescription(stringOf(map.get("description")));
        
        Object maxObj = map.get("max_count");
        if (maxObj != null) {
            try { def.setMaxAppointments(Integer.parseInt(String.valueOf(maxObj))); } catch (Exception ignored) {}
        }
        
        def.setAppointSound(stringOf(map.get("appoint_sound")));
        def.setRevokeSound(stringOf(map.get("revoke_sound")));
        def.setOnAppoint(toStringList(map.get("on_appoint")));
        def.setOnRevoke(toStringList(map.get("on_revoke")));
        
        // 解析 PowerStructure
        // 支持: power: "template" 或 power: ["t1", "t2"] 或直接内联定义
        PowerStructure power = null;
        
        // 优先检查 power 字段（与 gems.yml 格式一致）
        Object powerObj = map.get("power");
        if (powerObj != null) {
            power = parsePowerStructure(powerObj);
        }
        
        // 兼容旧版 ref 字段（已弃用，建议使用 power）
        if (power == null) {
            Object refObj = map.get("ref");
            if (refObj != null) {
                power = parsePowerStructure(refObj);
            }
        }
        
        // 如果当前节点包含 permissions/effects/allowed_commands，也视为内联定义
        if (map.containsKey("permissions") || map.containsKey("allowed_commands") || 
            map.containsKey("effects") || map.containsKey("command_allows") || map.containsKey("vault_groups")) {
            PowerStructure implicit = parsePowerStructure(map);
            if (power == null) {
                power = implicit;
            } else {
                power.merge(implicit);
            }
        }
        
        if (power != null) {
            def.setPowerStructure(power);
        }
        
        return def;
    }

    /**
     * 解析条件配置
     */
    private org.cubexmc.model.PowerCondition parseCondition(Object obj) {
        org.cubexmc.model.PowerCondition condition = new org.cubexmc.model.PowerCondition();
        java.util.Map<?, ?> map;
        
        if (obj instanceof ConfigurationSection) {
            map = ((ConfigurationSection) obj).getValues(false);
        } else if (obj instanceof java.util.Map) {
            map = (java.util.Map<?, ?>) obj;
        } else {
            return condition;
        }
        
        // 时间条件
        Object timeObj = map.get("time");
        if (timeObj instanceof java.util.Map || timeObj instanceof ConfigurationSection) {
            java.util.Map<?, ?> timeMap = (timeObj instanceof ConfigurationSection) ? 
                ((ConfigurationSection) timeObj).getValues(false) : (java.util.Map<?, ?>) timeObj;
                
            if (isTrue(timeMap.get("enabled"))) {
                condition.setTimeEnabled(true);
                String typeStr = stringOf(timeMap.get("type"));
                try {
                    if (typeStr != null) {
                        condition.setTimeType(org.cubexmc.model.PowerCondition.TimeType.valueOf(typeStr.toUpperCase()));
                    }
                } catch (Exception ignored) {}
                
                Object fromObj = timeMap.get("from");
                Object toObj = timeMap.get("to");
                if (fromObj instanceof Number) condition.setTimeFrom(((Number) fromObj).longValue());
                if (toObj instanceof Number) condition.setTimeTo(((Number) toObj).longValue());
            }
        }
        
        // 世界条件
        Object worldObj = map.get("worlds");
        if (worldObj instanceof java.util.Map || worldObj instanceof ConfigurationSection) {
            java.util.Map<?, ?> worldMap = (worldObj instanceof ConfigurationSection) ? 
                ((ConfigurationSection) worldObj).getValues(false) : (java.util.Map<?, ?>) worldObj;
                
            if (isTrue(worldMap.get("enabled"))) {
                condition.setWorldEnabled(true);
                String modeStr = stringOf(worldMap.get("mode"));
                try {
                    if (modeStr != null) {
                        condition.setWorldMode(org.cubexmc.model.PowerCondition.WorldMode.valueOf(modeStr.toUpperCase()));
                    }
                } catch (Exception ignored) {}
                
                condition.setWorldList(toStringList(worldMap.get("list")));
            }
        }
        
        return condition;
    }
    
    private boolean isTrue(Object obj) {
        if (obj instanceof Boolean) return (Boolean) obj;
        return obj != null && "true".equalsIgnoreCase(obj.toString());
    }

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
    public ExecuteConfig getGemScatterExecute() { return gemScatterExecute; }
    public Location getRandomPlaceCorner1() { return randomPlaceCorner1; }
    public Location getRandomPlaceCorner2() { return randomPlaceCorner2; }
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
    public java.util.List<String> getRedeemAllPermissions() { return redeemAllPermissions; }
    public java.util.List<org.cubexmc.model.AllowedCommand> getRedeemAllAllowedCommands() { return redeemAllAllowed; }

    // 宝石逃逸配置 getters
    public boolean isGemEscapeEnabled() { return gemEscapeEnabled; }
    /** 返回最小逃逸间隔（tick 单位） */
    public long getGemEscapeMinIntervalTicks() { return gemEscapeMinIntervalTicks; }
    /** 返回最大逃逸间隔（tick 单位） */
    public long getGemEscapeMaxIntervalTicks() { return gemEscapeMaxIntervalTicks; }
    public boolean isGemEscapeBroadcast() { return gemEscapeBroadcast; }
    public String getGemEscapeSound() { return gemEscapeSound; }
    public String getGemEscapeParticle() { return gemEscapeParticle; }

    // 放置兑换（祭坛模式）配置 getters
    public boolean isPlaceRedeemEnabled() { return placeRedeemEnabled; }
    // 长按右键兑换 getter
    public boolean isHoldToRedeemEnabled() { return holdToRedeemEnabled; }
    // 是否需要下蹲才能触发长按兑换
    public boolean isSneakToRedeem() { return sneakToRedeem; }
    // 长按兑换时长（tick）
    public int getHoldToRedeemDurationTicks() { return holdToRedeemDurationTicks; }
    public int getPlaceRedeemRadius() { return placeRedeemRadius; }
    public String getPlaceRedeemSound() { return placeRedeemSound; }
    public String getPlaceRedeemParticle() { return placeRedeemParticle; }
    public boolean isPlaceRedeemBeaconBeam() { return placeRedeemBeaconBeam; }
    public int getPlaceRedeemBeaconDuration() { return placeRedeemBeaconDuration; }

    /**
     * Collect every configured allowed-command label for proxy registration.
     */
    public java.util.Set<String> collectAllowedCommandLabels() {
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        if (gemDefinitions != null) {
            for (GemDefinition def : gemDefinitions) {
                if (def == null || def.getAllowedCommands() == null) {
                    continue;
                }
                for (org.cubexmc.model.AllowedCommand cmd : def.getAllowedCommands()) {
                    if (cmd == null) {
                        continue;
                    }
                    String label = cmd.getLabel();
                    if (label == null || label.isEmpty()) {
                        continue;
                    }
                    labels.add(label.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (redeemAllAllowed != null) {
            for (org.cubexmc.model.AllowedCommand cmd : redeemAllAllowed) {
                if (cmd == null) {
                    continue;
                }
                String label = cmd.getLabel();
                if (label == null || label.isEmpty()) {
                    continue;
                }
                labels.add(label.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return labels;
    }
}
