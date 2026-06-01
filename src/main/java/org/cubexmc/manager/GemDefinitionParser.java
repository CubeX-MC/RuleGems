package org.cubexmc.manager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

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
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.AppointDefinition;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerCondition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.model.RedeemIngredient;
import org.cubexmc.model.RedeemRecipe;
import org.cubexmc.model.RedeemRequirements;

import static org.cubexmc.utils.ConfigParseUtils.*;

/**
 * 宝石定义解析器 — 负责从 YAML 配置文件解析 GemDefinition 和 PowerStructure。
 * <p>
 * 职责：
 * <ul>
 * <li>加载 gems/ 文件夹（及旧版 config.yml 格式的）宝石定义</li>
 * <li>加载 powers/ 文件夹中的 PowerStructure 模板</li>
 * <li>提供 {@link #parsePowerStructure(Object)} 供其他模块引用</li>
 * </ul>
 */
public class GemDefinitionParser {

    private final Logger logger;
    private final LanguageManager languageManager;

    // 权力结构模板
    private final Map<String, PowerStructure> powerTemplates = new HashMap<>();
    private final Map<String, Object> rawPowerTemplates = new HashMap<>();

    // 解析结果
    private List<GemDefinition> gemDefinitions = new ArrayList<>();

    // 从 gemDefinitions 计算出的总数
    private int requiredCount;

    public GemDefinitionParser(Logger logger, LanguageManager languageManager) {
        this.logger = logger;
        this.languageManager = languageManager;
    }

    // ==================== 公共 API ====================

    public List<GemDefinition> getGemDefinitions() {
        return gemDefinitions;
    }

    public int getRequiredCount() {
        return requiredCount;
    }

    public PowerStructure getPowerTemplate(String name) {
        return powerTemplates.get(name);
    }

    public List<String> detectLegacySyntax(FileConfiguration config, File dataFolder) {
        Set<String> findings = new LinkedHashSet<>();
        if (config != null) {
            collectLegacyFromMap(config.getValues(false), findings);
            ConfigurationSection redeemAll = config.getConfigurationSection("redeem_all");
            if (redeemAll != null) {
                collectLegacyFromMap(redeemAll.getValues(false), findings);
            }
        }
        collectLegacyFromYamlFolder(new File(dataFolder, "gems"), findings, true);
        collectLegacyFromYamlFolder(new File(dataFolder, "powers"), findings, false);
        return new ArrayList<>(findings);
    }

    // ==================== 权力模板加载 ====================

    /**
     * 加载 powers/ 文件夹下的所有模板文件
     */
    public void loadPowerTemplates(File dataFolder) {
        powerTemplates.clear();
        rawPowerTemplates.clear();

        File powersFolder = new File(dataFolder, "powers");
        if (!powersFolder.exists()) {
            powersFolder.mkdirs();
            // 注意：默认文件的保存由 ConfigManager 或 plugin 处理
        }

        if (powersFolder.isDirectory()) {
            loadPowerTemplatesFromFolder(powersFolder);
        }

        for (String key : new java.util.ArrayList<>(rawPowerTemplates.keySet())) {
            if (!powerTemplates.containsKey(key)) {
                Object raw = rawPowerTemplates.remove(key);
                if (raw != null) {
                    powerTemplates.put(key, parsePowerStructure(raw));
                }
            }
        }
        rawPowerTemplates.clear();

        logger.info("Loaded " + powerTemplates.size() + " power templates.");
    }

    private void loadPowerTemplatesFromFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null)
            return;

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
                    if (templateObj instanceof ConfigurationSection || templateObj instanceof Map) {
                        rawPowerTemplates.put(key, templateObj);
                    }
                }
            } else {
                // 如果没有 templates 节点，假设整个文件是多个模板的集合（根键即模板名）
                for (String key : config.getKeys(false)) {
                    Object templateObj = config.get(key);
                    if (templateObj instanceof ConfigurationSection || templateObj instanceof Map) {
                        PowerStructure structure = parsePowerStructure(templateObj);
                        powerTemplates.put(key, structure);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to load power templates from " + file.getName() + ": " + e.getMessage());
        }
    }

    // ==================== 宝石定义加载 ====================

    /**
     * 加载宝石定义：优先从 gems/ 文件夹加载，回退到 config.yml 中的旧格式。
     * 加载完成后自动计算 {@link #requiredCount}。
     *
     * @param config     主配置文件 (config.yml)
     * @param dataFolder 插件数据目录
     */
    public void loadGemDefinitions(FileConfiguration config, File dataFolder) {
        this.gemDefinitions.clear();

        // 优先从 gems 文件夹加载
        File gemsFolder = new File(dataFolder, "gems");
        if (gemsFolder.exists() && gemsFolder.isDirectory()) {
            loadGemsFromFolder(gemsFolder);
            if (!this.gemDefinitions.isEmpty()) {
                logger.info("Loaded " + this.gemDefinitions.size() + " gem definitions from gems folder");
                recalculateRequiredCount();
                return;
            }
        }

        // 兼容旧配置：从 config.yml 读取
        ConfigurationSection sec = config.getConfigurationSection("gems");
        if (sec != null) {
            logger.warning("Legacy config format detected! Consider migrating gem configs to gems folder");
            for (String key : sec.getKeys(false)) {
                ConfigurationSection sub = sec.getConfigurationSection(key);
                Map<String, Object> m = new HashMap<>();
                if (sub != null) {
                    for (String k : sub.getKeys(false)) {
                        m.put(k, sub.get(k));
                    }
                }
                GemDefinition def = buildGemDefinitionFromMap(key, m);
                this.gemDefinitions.add(def);
            }
            recalculateRequiredCount();
            return;
        }

        // 兼容列表形式
        List<Map<?, ?>> list = config.getMapList("gems");
        if (list != null && !list.isEmpty()) {
            logger.warning("Legacy list config format detected! Consider migrating gem configs to gems folder");
            int index = 0;
            for (Map<?, ?> map : list) {
                Object keyObj = map.get("key");
                String key = keyObj != null ? stringOf(keyObj) : String.valueOf(index);
                GemDefinition def = buildGemDefinitionFromMap(key, map);
                this.gemDefinitions.add(def);
                index++;
            }
        }
        recalculateRequiredCount();
    }

    private void recalculateRequiredCount() {
        int total = 0;
        for (GemDefinition d : this.gemDefinitions)
            total += (d != null ? d.getCount() : 0);
        this.requiredCount = Math.max(0, total);
    }

    /**
     * 递归加载 gems 文件夹中的所有 .yml 文件
     */
    private void loadGemsFromFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isDirectory()) {
                loadGemsFromFolder(file);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
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
                if (gemSection == null)
                    continue;

                // 转换为 Map
                Map<String, Object> gemMap = new HashMap<>();
                for (String key : gemSection.getKeys(false)) {
                    gemMap.put(key, gemSection.get(key));
                }

                // 构建宝石定义
                GemDefinition def = buildGemDefinitionFromMap(gemKey, gemMap);
                this.gemDefinitions.add(def);

                logger.info("Loaded gem from file " + file.getName() + ": " + gemKey);
            }
        } catch (Exception e) {
            logger.severe("Failed to load gem config file " + file.getName() + ": " + e.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", e);
        }
    }

    // ==================== 核心解析方法 ====================

    private GemDefinition buildGemDefinitionFromMap(String gemKey, Map<?, ?> map) {
        Material material = parseMaterial(stringOf(map.get("material")), Material.RED_STAINED_GLASS);
        String displayName = parseDisplayName(map);
        Particle particle = parseParticle(stringOf(map.get("particle")), Particle.FLAME);
        Sound sound = parseSound(stringOf(map.get("sound")), Sound.ENTITY_EXPERIENCE_ORB_PICKUP);

        // per-gem 事件覆盖（可选）
        ExecuteConfig onPickup = parseExecuteConfig(map.get("on_pickup"));
        ExecuteConfig onScatter = parseExecuteConfig(map.get("on_scatter"));
        ExecuteConfig onRedeem = parseExecuteConfig(map.get("on_redeem"));

        // 注：permissions、vault_group、command_allows 由 parsePowerStructure 统一解析
        List<String> lore = toStringList(map.get("lore"));
        List<String> redeemTitle = toStringList(map.get("redeem_title"));
        boolean enchanted = parseBooleanLenient(map.get("enchanted"));
        List<String> mutex = toStringList(map.get("mutual_exclusive"));
        int count = parseIntSafe(map.get("count"), 1);

        Location[] range = parseRandomPlaceRange(map, gemKey);
        Location altarLocation = parseAltarLocation(map, gemKey);
        PowerStructure powerStructure = resolveGemPower(map);
        RedeemRequirements redeemRequirements = parseRedeemRequirements(map.get("redeem_requirements"));

        return new GemDefinition.Builder(gemKey)
                .material(material).displayName(displayName).particle(particle).sound(sound)
                .onPickup(onPickup).onScatter(onScatter).onRedeem(onRedeem)
                .powerStructure(powerStructure).lore(lore).redeemTitle(redeemTitle)
                .enchanted(enchanted).mutualExclusive(mutex).count(count)
                .randomPlaceCorner1(range[0]).randomPlaceCorner2(range[1])
                .altarLocation(altarLocation).redeemRequirements(redeemRequirements).build();
    }

    public RedeemRequirements parseRedeemRequirements(Object obj) {
        Map<?, ?> map = asMap(obj);
        if (map == null) {
            return RedeemRequirements.NONE;
        }
        boolean allowRedeemAll = map.containsKey("allow_redeem_all")
                ? parseBooleanLenient(map.get("allow_redeem_all"))
                : false;
        List<RedeemRecipe> recipes;
        Object anyOf = map.get("any_of");
        if (anyOf instanceof List) {
            if (hasTopLevelRecipeFields(map)) {
                logger.warning("redeem_requirements mixes any_of with top-level recipe fields; using any_of.");
            }
            recipes = new ArrayList<>();
            for (Object recipeObj : (List<?>) anyOf) {
                RedeemRecipe recipe = parseRedeemRecipe(recipeObj);
                if (recipe.hasRequirements()) {
                    recipes.add(recipe);
                }
            }
        } else {
            RedeemRecipe recipe = parseRedeemRecipe(map);
            recipes = recipe.hasRequirements()
                    ? Collections.singletonList(recipe)
                    : Collections.emptyList();
        }
        return recipes.isEmpty()
                ? new RedeemRequirements(Collections.emptyList(), allowRedeemAll, stringOf(map.get("failure_message")))
                : new RedeemRequirements(recipes, allowRedeemAll, stringOf(map.get("failure_message")));
    }

    private boolean hasTopLevelRecipeFields(Map<?, ?> map) {
        return map.containsKey("requires_held")
                || map.containsKey("requires_redeemed")
                || map.containsKey("consumes")
                || map.containsKey("requires_any")
                || map.containsKey("requires_count")
                || map.containsKey("requires_count_from");
    }

    private RedeemRecipe parseRedeemRecipe(Object obj) {
        Map<?, ?> map = asMap(obj);
        if (map == null) {
            logger.warning("Invalid redeem_requirements recipe entry; expected map.");
            return new RedeemRecipe(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0, Collections.emptyList());
        }
        return new RedeemRecipe(
                parseRedeemIngredients(map.get("requires_held"), "requires_held"),
                parseRedeemIngredients(map.get("consumes"), "consumes"),
                parseRedeemIngredients(map.get("requires_redeemed"), "requires_redeemed"),
                toStringList(map.get("requires_any")),
                parseIntSafe(map.get("requires_count"), 0),
                toStringList(map.get("requires_count_from")));
    }

    private List<RedeemIngredient> parseRedeemIngredients(Object obj, String fieldName) {
        if (obj == null) {
            return Collections.emptyList();
        }
        List<?> raw = obj instanceof List ? (List<?>) obj : Collections.singletonList(obj);
        List<RedeemIngredient> ingredients = new ArrayList<>();
        for (Object item : raw) {
            RedeemIngredient ingredient = parseRedeemIngredient(item, fieldName);
            if (ingredient != null && ingredient.isValid()) {
                ingredients.add(ingredient);
            }
        }
        return ingredients;
    }

    private RedeemIngredient parseRedeemIngredient(Object obj, String fieldName) {
        if (obj instanceof String) {
            String gemKey = ((String) obj).trim();
            if (gemKey.isEmpty()) {
                logger.warning("Ignoring empty redeem_requirements." + fieldName + " ingredient.");
                return null;
            }
            return new RedeemIngredient(gemKey, 1);
        }
        Map<?, ?> map = asMap(obj);
        if (map != null) {
            String gemKey = stringOf(map.get("gem"));
            if (gemKey == null || gemKey.trim().isEmpty()) {
                logger.warning("Ignoring redeem_requirements." + fieldName + " ingredient without gem key.");
                return null;
            }
            int amount = parseIntSafe(map.get("amount"), 1);
            if (amount <= 0) {
                logger.warning("redeem_requirements." + fieldName + " amount for '" + gemKey
                        + "' must be positive; using 1.");
                amount = 1;
            }
            return new RedeemIngredient(gemKey, amount);
        }
        logger.warning("Ignoring invalid redeem_requirements." + fieldName + " ingredient: " + obj);
        return null;
    }

    private Material parseMaterial(String value, Material fallback) {
        if (value == null || value.isEmpty())
            return fallback;
        try {
            return Material.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Particle parseParticle(String value, Particle fallback) {
        if (value == null || value.isEmpty())
            return fallback;
        try {
            return Particle.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Sound parseSound(String value, Sound fallback) {
        if (value == null || value.isEmpty())
            return fallback;
        try {
            return Sound.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String parseDisplayName(Map<?, ?> map) {
        String nameStr = stringOf(map.get("name"));
        String fallback = languageManager != null ? languageManager.getMessage("messages.gem.default_display_name")
                : "&cRule Gem";
        String raw = (nameStr != null && !nameStr.isEmpty()) ? nameStr : fallback;
        return org.cubexmc.utils.ColorUtils.translateColorCodes(raw);
    }

    private ExecuteConfig parseExecuteConfig(Object obj) {
        if (!(obj instanceof Map))
            return null;
        Map<?, ?> m = (Map<?, ?>) obj;
        return new ExecuteConfig(
                toStringList(m.get("commands")),
                stringOf(m.get("sound")),
                stringOf(m.get("particle")));
    }

    private boolean parseBooleanLenient(Object obj) {
        if (obj instanceof Boolean)
            return (Boolean) obj;
        if (obj == null)
            return false;
        String s = String.valueOf(obj).trim();
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
    }

    private int parseIntSafe(Object obj, int fallback) {
        if (obj == null)
            return fallback;
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<?, ?> asMap(Object obj) {
        if (obj instanceof ConfigurationSection) {
            return ((ConfigurationSection) obj).getValues(false);
        }
        if (obj instanceof Map) {
            return (Map<?, ?>) obj;
        }
        return null;
    }

    private Location[] parseRandomPlaceRange(Map<?, ?> map, String gemKey) {
        Location corner1 = null, corner2 = null;
        Object rangeObj = map.get("random_place_range");
        if (rangeObj instanceof Map) {
            Map<?, ?> rangeMap = (Map<?, ?>) rangeObj;
            String worldName = stringOf(rangeMap.get("world"));
            if (worldName != null && !worldName.isEmpty()) {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Object c1Obj = rangeMap.get("corner1");
                    Object c2Obj = rangeMap.get("corner2");
                    if (c1Obj instanceof Map && c2Obj instanceof Map) {
                        corner1 = parseLocationFromMap((Map<?, ?>) c1Obj, world);
                        corner2 = parseLocationFromMap((Map<?, ?>) c2Obj, world);
                    }
                } else {
                    logger.warning("World not found in spawn range config for gem " + gemKey + ": " + worldName);
                }
            }
        }
        return new Location[] { corner1, corner2 };
    }

    private Location parseAltarLocation(Map<?, ?> map, String gemKey) {
        Object altarObj = map.get("altar");
        if (!(altarObj instanceof Map))
            return null;
        Map<?, ?> altarMap = (Map<?, ?>) altarObj;
        String altarWorldName = stringOf(altarMap.get("world"));
        if (altarWorldName == null || altarWorldName.isEmpty())
            return null;
        World altarWorld = Bukkit.getWorld(altarWorldName);
        if (altarWorld == null) {
            logger.warning("World not found in altar config for gem " + gemKey + ": " + altarWorldName);
            return null;
        }
        return parseLocationFromMap(altarMap, altarWorld);
    }

    private PowerStructure resolveGemPower(Map<?, ?> map) {
        Object powerObj = map.get("power");
        if (powerObj != null) {
            PowerStructure powerStructure = parsePowerStructure(powerObj);
            PowerStructure rootStructure = parseLegacyRootPower(map);
            if (rootStructure.hasAnyContent()) {
                logger.warning("Gem root power fields are deprecated; move permissions, command_allows, effects, "
                        + "and permission_groups under power:. future version may remove this compatibility.");
                powerStructure.merge(rootStructure);
            }
            return powerStructure;
        }
        PowerStructure rootStructure = parseLegacyRootPower(map);
        if (rootStructure.hasAnyContent()) {
            logger.warning("Gem root power fields without power: are deprecated; move them under power:. "
                    + "future version may remove this compatibility.");
        }
        return rootStructure;
    }

    private PowerStructure parseLegacyRootPower(Map<?, ?> map) {
        if (!hasPowerFields(map)) {
            return new PowerStructure();
        }
        return parsePowerStructure(map);
    }

    private boolean hasPowerFields(Map<?, ?> map) {
        return map.containsKey("permissions")
                || map.containsKey("command_allows")
                || map.containsKey("allowed_commands")
                || map.containsKey("effects")
                || map.containsKey("vault_group")
                || map.containsKey("vault_groups")
                || map.containsKey("permission_group")
                || map.containsKey("permission_groups")
                || map.containsKey("appoints")
                || map.containsKey("conditions");
    }

    private void collectLegacyFromYamlFolder(File folder, Set<String> findings, boolean gemFile) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectLegacyFromYamlFolder(file, findings, gemFile);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                for (String key : yaml.getKeys(false)) {
                    Object value = yaml.get(key);
                    Map<?, ?> map = asMap(value);
                    if (map == null) {
                        continue;
                    }
                    collectLegacyFromMap(map, findings);
                    if (gemFile) {
                        Object power = map.get("power");
                        if (power != null && hasPowerFields(map)) {
                            findings.add("gem root power fields outside power");
                        }
                        Object requirements = map.get("redeem_requirements");
                        Map<?, ?> reqMap = asMap(requirements);
                        if (reqMap != null && containsMapIngredientList(reqMap)) {
                            findings.add("redeem_requirements map ingredient list");
                        }
                    }
                }
            }
        }
    }

    private void collectLegacyFromMap(Map<?, ?> map, Set<String> findings) {
        if (map == null) {
            return;
        }
        if (map.containsKey("template")) {
            findings.add("template -> base");
        }
        if (map.containsKey("vault_group")) {
            findings.add("vault_group -> permission_groups");
        }
        if (map.containsKey("vault_groups")) {
            findings.add("vault_groups -> permission_groups");
        }
        if (map.containsKey("permission_group")) {
            findings.add("permission_group -> permission_groups");
        }
        Object power = map.get("power");
        Map<?, ?> powerMap = asMap(power);
        if (powerMap != null) {
            collectLegacyFromMap(powerMap, findings);
        }
        Object appoints = map.get("appoints");
        Map<?, ?> appointsMap = asMap(appoints);
        if (appointsMap != null) {
            for (Object appoint : appointsMap.values()) {
                collectLegacyFromMap(asMap(appoint), findings);
            }
        }
    }

    private boolean containsMapIngredientList(Map<?, ?> requirements) {
        return containsMapItem(requirements.get("requires_held"))
                || containsMapItem(requirements.get("requires_redeemed"))
                || containsMapItem(requirements.get("consumes"));
    }

    private boolean containsMapItem(Object obj) {
        if (!(obj instanceof List)) {
            return false;
        }
        for (Object item : (List<?>) obj) {
            if (asMap(item) != null) {
                return true;
            }
        }
        return false;
    }

    private List<String> parsePermissionGroups(Map<?, ?> map) {
        Set<String> groups = new LinkedHashSet<>();
        addGroups(groups, toStringList(map.get("permission_groups")));

        boolean usedLegacy = false;
        String group = stringOf(map.get("vault_group"));
        if (group != null && !group.trim().isEmpty()) {
            groups.add(group.trim());
            usedLegacy = true;
        }
        List<String> vaultGroups = toStringList(map.get("vault_groups"));
        if (!vaultGroups.isEmpty()) {
            addGroups(groups, vaultGroups);
            usedLegacy = true;
        }
        String permissionGroup = stringOf(map.get("permission_group"));
        if (permissionGroup != null && !permissionGroup.trim().isEmpty()) {
            groups.add(permissionGroup.trim());
            usedLegacy = true;
        }
        if (usedLegacy) {
            logger.warning("Power group keys vault_group/vault_groups/permission_group are deprecated; "
                    + "use permission_groups. future version may remove this compatibility.");
        }
        return new ArrayList<>(groups);
    }

    private void addGroups(Set<String> groups, List<String> values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                groups.add(value.trim());
            }
        }
    }

    /**
     * 从 Map 解析 Location（仅坐标，世界已知）
     */
    private Location parseLocationFromMap(Map<?, ?> map, World world) {
        try {
            double x = 0, y = 0, z = 0;
            Object xObj = map.get("x");
            Object yObj = map.get("y");
            Object zObj = map.get("z");
            if (xObj != null)
                x = Double.parseDouble(String.valueOf(xObj));
            if (yObj != null)
                y = Double.parseDouble(String.valueOf(yObj));
            if (zObj != null)
                z = Double.parseDouble(String.valueOf(zObj));
            return new Location(world, x, y, z);
        } catch (Exception e) {
            logger.warning("Failed to parse Location: " + e.getMessage());
            return null;
        }
    }

    // ==================== AllowedCommand 解析 ====================

    private List<AllowedCommand> parseAllowedCommands(Object allowsObj) {
        List<AllowedCommand> allowed = new ArrayList<>();
        if (allowsObj instanceof List) {
            List<?> raw = (List<?>) allowsObj;
            for (Object e : raw) {
                if (e instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) e;

                    // 唯一格式：command（玩家输入）+ execute（实际执行）
                    Object commandObj = map.get("command");
                    Object executeObj = map.get("execute");
                    Object uses = map.get("time_limit");
                    Object cooldownObj = map.get("cooldown");

                    if (commandObj == null || executeObj == null) {
                        logger.warning("command_allows config error: must contain 'command' and 'execute' fields");
                        continue;
                    }

                    int u = -1; // 默认无限
                    int cooldown = 0;
                    try {
                        u = Integer.parseInt(String.valueOf(uses));
                    } catch (Exception ignored) {
                    }
                    try {
                        cooldown = Integer.parseInt(String.valueOf(cooldownObj));
                    } catch (Exception ignored) {
                    }

                    // 解析命令标签（移除斜杠）
                    String cmd = String.valueOf(commandObj).trim();
                    if (cmd.startsWith("/"))
                        cmd = cmd.substring(1);

                    // 提取第一个单词作为label（不包含参数）
                    String cmdLabel = cmd.split("\\s+")[0].toLowerCase();

                    // 解析执行命令列表
                    List<String> executeCmds = toStringList(executeObj);
                    allowed.add(new AllowedCommand(cmdLabel, u, executeCmds, cooldown));
                }
            }
        }
        return allowed;
    }

    // ==================== Effect 解析 ====================

    /**
     * 解析药水效果配置
     * 支持两种格式:
     * 1. 简单格式: ["SPEED", "NIGHT_VISION"]
     * 2. 详细格式: [{type: "SPEED", amplifier: 1, particles: false}, ...]
     */
    private List<EffectConfig> parseEffects(Object effectsObj) {
        List<EffectConfig> effects = new ArrayList<>();
        if (effectsObj == null)
            return effects;

        if (effectsObj instanceof List) {
            List<?> raw = (List<?>) effectsObj;
            for (Object e : raw) {
                if (e instanceof String) {
                    // 简单格式: 只有效果类型名称
                    String typeName = ((String) e).toUpperCase().trim();
                    PotionEffectType type = PotionEffectType.getByName(typeName);
                    if (type != null) {
                        effects.add(new EffectConfig(type));
                    } else {
                        logger.warning("Unknown potion effect type: " + typeName);
                    }
                } else if (e instanceof Map) {
                    // 详细格式
                    Map<?, ?> map = (Map<?, ?>) e;
                    String typeName = stringOf(map.get("type"));
                    if (typeName == null || typeName.isEmpty()) {
                        logger.warning("effects config error: missing 'type' field");
                        continue;
                    }

                    PotionEffectType type = PotionEffectType.getByName(typeName.toUpperCase().trim());
                    if (type == null) {
                        logger.warning("Unknown potion effect type: " + typeName);
                        continue;
                    }

                    // 解析可选参数
                    int amplifier = 0;
                    Object ampObj = map.get("amplifier");
                    if (ampObj != null) {
                        try {
                            amplifier = Integer.parseInt(String.valueOf(ampObj));
                        } catch (Exception ignored) {
                        }
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

    // ==================== PowerStructure 解析 ====================

    /**
     * 解析权力结构。
     * 支持从模板引用、Map 解析、或混合模式。
     */
    public PowerStructure parsePowerStructure(Object obj) {
        if (obj == null)
            return new PowerStructure();

        // 1. 字符串引用模板
        if (obj instanceof String) {
            String templateName = (String) obj;
            PowerStructure template = powerTemplates.get(templateName);

            if (template == null && rawPowerTemplates.containsKey(templateName)) {
                Object raw = rawPowerTemplates.remove(templateName);
                if (raw != null) {
                    template = parsePowerStructure(raw);
                    powerTemplates.put(templateName, template);
                }
            }

            if (template != null) {
                return template.copy();
            } else {
                logger.warning("Unknown power template: " + templateName);
                return new PowerStructure();
            }
        }

        // 1.5 列表引用模板 (组合多个模板)
        if (obj instanceof List) {
            PowerStructure combined = new PowerStructure();
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof String) {
                    String templateName = (String) item;
                    PowerStructure template = powerTemplates.get(templateName);
                    if (template == null && rawPowerTemplates.containsKey(templateName)) {
                        Object raw = rawPowerTemplates.remove(templateName);
                        if (raw != null) {
                            template = parsePowerStructure(raw);
                            powerTemplates.put(templateName, template);
                        }
                    }
                    if (template != null) {
                        combined.merge(template);
                    } else {
                        logger.warning("Unknown power template in list: " + templateName);
                    }
                } else if (item instanceof Map || item instanceof ConfigurationSection) {
                    // 允许列表中包含内联定义
                    PowerStructure inline = parsePowerStructure(item);
                    combined.merge(inline);
                }
            }
            return combined;
        }

        // 2. Map 解析
        if (obj instanceof Map || obj instanceof ConfigurationSection) {
            Map<?, ?> map;
            map = asMap(obj);

            PowerStructure structure = new PowerStructure();

            // 检查是否有 base/template 字段用于继承
            Object baseObj = map.get("base");
            if (baseObj == null && map.containsKey("template")) {
                baseObj = map.get("template");
                logger.warning("Power field 'template' is deprecated; use 'base' instead. "
                        + "future version may remove this compatibility.");
            }

            if (baseObj instanceof String) {
                PowerStructure template = parsePowerStructure((String) baseObj);
                if (template.hasAnyContent()) {
                    structure = template;
                }
            } else if (baseObj instanceof List) {
                // 支持多重继承
                for (Object item : (List<?>) baseObj) {
                    if (item instanceof String) {
                        PowerStructure template = parsePowerStructure((String) item);
                        if (template.hasAnyContent()) {
                            structure.merge(template);
                        }
                    }
                }
            }

            // 解析并合并属性
            List<String> perms = toStringList(map.get("permissions"));
            if (!perms.isEmpty()) {
                structure.getPermissions().addAll(perms);
            }

            List<String> groups = parsePermissionGroups(map);
            if (!groups.isEmpty()) {
                for (String group : groups) {
                    if (!structure.getVaultGroups().contains(group)) {
                        structure.getVaultGroups().add(group);
                    }
                }
            }

            List<AllowedCommand> allowed = parseAllowedCommands(map.get("command_allows"));
            if (!allowed.isEmpty()) {
                structure.getAllowedCommands().addAll(allowed);
            }

            List<EffectConfig> effects = parseEffects(map.get("effects"));
            if (!effects.isEmpty()) {
                structure.getEffects().addAll(effects);
            }

            // 解析委任 (Appoints)
            Object appointsObj = map.get("appoints");
            if (appointsObj instanceof Map || appointsObj instanceof ConfigurationSection) {
                Map<?, ?> appointsMap = (appointsObj instanceof ConfigurationSection)
                        ? ((ConfigurationSection) appointsObj).getValues(false)
                        : (Map<?, ?>) appointsObj;

                for (Map.Entry<?, ?> entry : appointsMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    AppointDefinition def = parseAppointDefinition(key, entry.getValue());
                    if (def != null) {
                        structure.getAppoints().put(key, def);
                    }
                }
            }

            // 解析条件
            Object condObj = map.get("conditions");
            if (condObj instanceof Map || condObj instanceof ConfigurationSection) {
                PowerCondition condition = parseCondition(condObj);
                structure.setCondition(condition);
            }

            return structure;
        }

        return new PowerStructure();
    }

    // ==================== AppointDefinition 解析 ====================

    /**
     * 解析委任定义
     */
    private AppointDefinition parseAppointDefinition(String key, Object obj) {
        if (obj == null)
            return null;

        Map<?, ?> map;
        map = asMap(obj);
        if (map == null) {
            return null;
        }

        AppointDefinition def = new AppointDefinition(key);

        // 基本属性
        def.setDisplayName(stringOf(map.get("display_name")));
        def.setDescription(stringOf(map.get("description")));

        Object maxObj = map.get("max_count");
        if (maxObj != null) {
            try {
                def.setMaxAppointments(Integer.parseInt(String.valueOf(maxObj)));
            } catch (Exception ignored) {
            }
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
                map.containsKey("effects") || map.containsKey("command_allows") || map.containsKey("vault_groups")
                || map.containsKey("vault_group") || map.containsKey("permission_group")
                || map.containsKey("permission_groups")) {
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

    // ==================== PowerCondition 解析 ====================

    /**
     * 解析条件配置
     */
    private PowerCondition parseCondition(Object obj) {
        PowerCondition condition = new PowerCondition();
        Map<?, ?> map;

        if (obj instanceof ConfigurationSection) {
            map = ((ConfigurationSection) obj).getValues(false);
        } else if (obj instanceof Map) {
            map = (Map<?, ?>) obj;
        } else {
            return condition;
        }

        // 时间条件
        Object timeObj = map.get("time");
        if (timeObj instanceof Map || timeObj instanceof ConfigurationSection) {
            Map<?, ?> timeMap = (timeObj instanceof ConfigurationSection)
                    ? ((ConfigurationSection) timeObj).getValues(false)
                    : (Map<?, ?>) timeObj;

            if (isTrue(timeMap.get("enabled"))) {
                condition.setTimeEnabled(true);
                String typeStr = stringOf(timeMap.get("type"));
                try {
                    if (typeStr != null) {
                        condition.setTimeType(PowerCondition.TimeType.valueOf(typeStr.toUpperCase()));
                    }
                } catch (Exception ignored) {
                }

                Object fromObj = timeMap.get("from");
                Object toObj = timeMap.get("to");
                if (fromObj instanceof Number)
                    condition.setTimeFrom(((Number) fromObj).longValue());
                if (toObj instanceof Number)
                    condition.setTimeTo(((Number) toObj).longValue());
            }
        }

        // 世界条件
        Object worldObj = map.get("worlds");
        if (worldObj instanceof Map || worldObj instanceof ConfigurationSection) {
            Map<?, ?> worldMap = (worldObj instanceof ConfigurationSection)
                    ? ((ConfigurationSection) worldObj).getValues(false)
                    : (Map<?, ?>) worldObj;

            if (isTrue(worldMap.get("enabled"))) {
                condition.setWorldEnabled(true);
                String modeStr = stringOf(worldMap.get("mode"));
                try {
                    if (modeStr != null) {
                        condition.setWorldMode(
                                PowerCondition.WorldMode.valueOf(modeStr.toUpperCase()));
                    }
                } catch (Exception ignored) {
                }

                condition.setWorldList(toStringList(worldMap.get("list")));
            }
        }

        return condition;
    }
}
