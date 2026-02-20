package org.cubexmc.manager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.RuleGems;
import org.cubexmc.model.GemDefinition;

/**
 * 宝石状态管理器 - 负责宝石的核心状态管理
 * 包括：位置映射、持有者映射、UUID-Key映射、物品创建等
 */
public class GemStateManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    @SuppressWarnings("unused")
    private final RuleGems plugin; // 保留用于未来扩展
    private final GemDefinitionParser gemParser;
    private final LanguageManager languageManager;

    // NamespacedKeys for persistent data
    private final NamespacedKey ruleGemKey;
    private final NamespacedKey uniqueIdKey;
    private final NamespacedKey gemKeyKey;

    // 核心映射
    private final Map<Location, UUID> locationToGemUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Location> gemUuidToLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Player> gemUuidToHolder = new ConcurrentHashMap<>();
    private final Map<UUID, String> gemUuidToKey = new ConcurrentHashMap<>();

    // GemDefinition 缓存
    private final Map<String, GemDefinition> gemDefinitionCache = new ConcurrentHashMap<>();

    // 玩家名称缓存（用于离线玩家显示）
    private final Map<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    public GemStateManager(RuleGems plugin, GemDefinitionParser gemParser, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemParser = gemParser;
        this.languageManager = languageManager;
        this.ruleGemKey = new NamespacedKey(plugin, "rule_gem");
        this.uniqueIdKey = new NamespacedKey(plugin, "unique_id");
        this.gemKeyKey = new NamespacedKey(plugin, "gem_key");
    }

    // ==================== 状态访问器 ====================

    public Map<Location, UUID> getLocationToGemUuid() {
        return locationToGemUuid;
    }

    public Map<UUID, Location> getGemUuidToLocation() {
        return gemUuidToLocation;
    }

    public Map<UUID, Player> getGemUuidToHolder() {
        return gemUuidToHolder;
    }

    public Map<UUID, String> getGemUuidToKey() {
        return gemUuidToKey;
    }

    public NamespacedKey getRuleGemKey() {
        return ruleGemKey;
    }

    public NamespacedKey getUniqueIdKey() {
        return uniqueIdKey;
    }

    public NamespacedKey getGemKeyKey() {
        return gemKeyKey;
    }

    public Map<String, GemDefinition> getGemDefinitionCache() {
        return gemDefinitionCache;
    }

    public Map<UUID, String> getPlayerNameCache() {
        return playerNameCache;
    }

    // ==================== 封装状态写入 ====================

    public void bindPlacedGem(Location location, UUID gemId) {
        if (location == null || gemId == null) return;
        locationToGemUuid.put(location, gemId);
        gemUuidToLocation.put(gemId, location);
    }

    public void unbindPlacedGem(Location location, UUID gemId) {
        if (gemId == null) return;
        if (location != null) {
            locationToGemUuid.remove(location, gemId);
        } else {
            Location old = gemUuidToLocation.get(gemId);
            if (old != null) {
                locationToGemUuid.remove(old, gemId);
            }
        }
        gemUuidToLocation.remove(gemId);
    }

    public void setGemHolder(UUID gemId, Player player) {
        if (gemId == null || player == null) return;
        gemUuidToHolder.put(gemId, player);
    }

    public void clearGemHolder(UUID gemId) {
        if (gemId == null) return;
        gemUuidToHolder.remove(gemId);
    }

    public void setGemKey(UUID gemId, String gemKey) {
        if (gemId == null) return;
        if (gemKey == null || gemKey.isEmpty()) {
            gemUuidToKey.remove(gemId);
        } else {
            gemUuidToKey.put(gemId, gemKey);
        }
    }

    public void clearPlacedMappings() {
        locationToGemUuid.clear();
        gemUuidToLocation.clear();
    }

    public void clearHolderMappings() {
        gemUuidToHolder.clear();
    }

    public void clearGemKeys() {
        gemUuidToKey.clear();
    }

    public Map<Location, UUID> snapshotPlacedGems() {
        return new HashMap<>(locationToGemUuid);
    }

    // ==================== 清理方法 ====================

    public void clearAll() {
        locationToGemUuid.clear();
        gemUuidToLocation.clear();
        gemUuidToHolder.clear();
        gemUuidToKey.clear();
        gemDefinitionCache.clear();
        playerNameCache.clear();
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 从 gemsData 加载放置宝石、持有宝石和玩家名称缓存。
     *
     * @param gemsData          配置数据
     * @param randomPlaceGemFn  当持有者离线时的放置回调
     */
    public void loadData(FileConfiguration gemsData, Consumer<UUID> randomPlaceGemFn) {
        // 放置的宝石（兼容旧键名 "placed-gams"）
        ConfigurationSection placedGemsSection = gemsData.getConfigurationSection("placed-gems");
        if (placedGemsSection == null) {
            placedGemsSection = gemsData.getConfigurationSection("placed-gams");
        }
        if (placedGemsSection != null) {
            for (String uuidStr : placedGemsSection.getKeys(false)) {
                String worldName = placedGemsSection.getString(uuidStr + ".world");
                double x = placedGemsSection.getDouble(uuidStr + ".x");
                double y = placedGemsSection.getDouble(uuidStr + ".y");
                double z = placedGemsSection.getDouble(uuidStr + ".z");
                String gemKey = placedGemsSection.getString(uuidStr + ".gem_key", "default");
                World w = Bukkit.getWorld(worldName);
                if (w == null) continue;
                Location loc = new Location(w, x, y, z);
                UUID gemId;
                try { gemId = UUID.fromString(uuidStr); } catch (Exception ignored) { continue; }
                locationToGemUuid.put(loc, gemId);
                gemUuidToLocation.put(gemId, loc);
                gemUuidToKey.put(gemId, gemKey);
            }
        }
        // 持有的宝石
        ConfigurationSection heldGemsSection = gemsData.getConfigurationSection("held-gems");
        if (heldGemsSection != null) {
            for (String uuidStr : heldGemsSection.getKeys(false)) {
                String playerUUIDStr = heldGemsSection.getString(uuidStr + ".player_uuid");
                String gemKey = heldGemsSection.getString(uuidStr + ".gem_key", "default");
                if (playerUUIDStr == null) continue;
                UUID playerUUID, gemId;
                try {
                    playerUUID = UUID.fromString(playerUUIDStr);
                    gemId = UUID.fromString(uuidStr);
                } catch (Exception ignored) { continue; }
                gemUuidToKey.put(gemId, gemKey);
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    gemUuidToHolder.put(gemId, player);
                } else {
                    randomPlaceGemFn.accept(gemId);
                }
            }
        }
        // 玩家名称缓存
        ConfigurationSection namesSection = gemsData.getConfigurationSection("player_names");
        if (namesSection != null) {
            for (String uuidStr : namesSection.getKeys(false)) {
                try {
                    UUID uid = UUID.fromString(uuidStr);
                    String name = namesSection.getString(uuidStr);
                    if (name != null && !name.isEmpty()) {
                        playerNameCache.put(uid, name);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load player name cache entry for UUID " + uuidStr + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * 将放置宝石、持有宝石和玩家名称缓存写入 gemsData。
     */
    public void saveData(FileConfiguration gemsData) {
        for (Map.Entry<Location, UUID> e : locationToGemUuid.entrySet()) {
            Location loc = e.getKey();
            UUID gemId = e.getValue();
            if (loc == null || loc.getWorld() == null) {
                plugin.getLogger().warning("Skipping gem save for " + gemId + " because location/world is unavailable.");
                continue;
            }
            String path = "placed-gems." + gemId.toString();
            gemsData.set(path + ".world", loc.getWorld().getName());
            gemsData.set(path + ".x", loc.getX());
            gemsData.set(path + ".y", loc.getY());
            gemsData.set(path + ".z", loc.getZ());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(gemId));
        }
        for (Map.Entry<UUID, Player> e : gemUuidToHolder.entrySet()) {
            UUID gemId = e.getKey();
            Player player = e.getValue();
            if (player == null) {
                plugin.getLogger().warning("Skipping holder save for gem " + gemId + " because player is null.");
                continue;
            }
            String path = "held-gems." + gemId.toString();
            gemsData.set(path + ".player", player.getName());
            gemsData.set(path + ".player_uuid", player.getUniqueId().toString());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(gemId));
        }
        for (Map.Entry<UUID, String> e : playerNameCache.entrySet()) {
            gemsData.set("player_names." + e.getKey().toString(), e.getValue());
        }
    }

    /**
     * 确保配置中定义的每一颗 gem 至少存在一颗。若缺失则随机放置。
     */
    public void ensureConfiguredGemsPresent(Consumer<UUID> randomPlaceGemFn) {
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<UUID, String> e : gemUuidToKey.entrySet()) {
            String k = e.getValue();
            if (k == null) continue;
            String lk = k.toLowerCase(ROOT_LOCALE);
            counts.put(lk, counts.getOrDefault(lk, 0) + 1);
        }
        for (GemDefinition d : defs) {
            String k = d.getGemKey();
            if (k == null) continue;
            String lk = k.toLowerCase(ROOT_LOCALE);
            int have = counts.getOrDefault(lk, 0);
            int need = Math.max(1, d.getCount());
            for (int i = have; i < need; i++) {
                UUID newId = UUID.randomUUID();
                gemUuidToKey.put(newId, k);
                randomPlaceGemFn.accept(newId);
            }
        }
    }

    // ==================== 宝石状态查询 ====================

    /**
     * 获取宝石的位置（如果已放置）
     */
    public Location getGemLocation(UUID gemId) {
        return gemUuidToLocation.get(gemId);
    }

    /**
     * 获取宝石的持有者（如果被持有）
     */
    public Player getGemHolder(UUID gemId) {
        return gemUuidToHolder.get(gemId);
    }

    /**
     * 获取宝石的 key
     */
    public String getGemKey(UUID gemId) {
        return gemUuidToKey.get(gemId);
    }

    /**
     * 获取所有宝石的 UUID 集合
     */
    public Set<UUID> getAllGemUuids() {
        return new HashSet<>(gemUuidToKey.keySet());
    }

    /**
     * 根据位置查找宝石 UUID
     */
    public UUID getGemUuidByLocation(Location loc) {
        return locationToGemUuid.get(loc);
    }

    /**
     * 根据 gemId 查找位置
     */
    public Location findLocationByGemId(UUID gemId) {
        return gemUuidToLocation.get(gemId);
    }

    /**
     * 获取所有已放置宝石的数量
     */
    public int getPlacedCount() {
        return locationToGemUuid.size();
    }

    /**
     * 获取所有被持有宝石的数量
     */
    public int getHeldCount() {
        return gemUuidToHolder.size();
    }

    /**
     * 获取宝石总数（已放置 + 被持有）
     */
    public int getTotalGemCount() {
        return locationToGemUuid.size() + gemUuidToHolder.size();
    }

    /**
     * 获取所有宝石位置的快照
     */
    public Map<UUID, Location> getAllGemLocations() {
        return new HashMap<>(gemUuidToLocation);
    }

    /**
     * 获取宝石的显示名称
     */
    public String getGemDisplayName(UUID gemId) {
        String gemKey = gemUuidToKey.get(gemId);
        if (gemKey == null) return null;
        GemDefinition def = findGemDefinition(gemKey);
        if (def != null && def.getDisplayName() != null) {
            return ChatColor.translateAlternateColorCodes('&', def.getDisplayName());
        }
        return gemKey;
    }

    /**
     * 缓存玩家名称
     */
    public void cachePlayerName(Player player) {
        if (player != null) {
            playerNameCache.put(player.getUniqueId(), player.getName());
        }
    }

    /**
     * 获取缓存的玩家名称（支持离线玩家）
     */
    public String getCachedPlayerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            playerNameCache.put(uuid, online.getName());
            return online.getName();
        }
        String cached = playerNameCache.get(uuid);
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline.getName();
            if (name != null && !name.isEmpty()) {
                playerNameCache.put(uuid, name);
                return name;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to resolve offline player name for UUID " + uuid + ": " + e.getMessage());
        }
        return uuid.toString().substring(0, 8);
    }

    /**
     * 从玩家背包中移除指定宝石物品
     */
    public void removeGemItemFromInventory(Player player, UUID targetId) {
        if (player == null || targetId == null) return;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (isRuleGem(off)) {
            UUID id = getGemUUID(off);
            if (targetId.equals(id)) {
                inv.setItemInOffHand(new ItemStack(Material.AIR));
                return;
            }
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isRuleGem(it)) continue;
            UUID id = getGemUUID(it);
            if (targetId.equals(id)) {
                inv.setItem(i, new ItemStack(Material.AIR));
                break;
            }
        }
    }

    /**
     * 处理宝石方块被敲击的事件（启用瞬时破坏）
     */
    public void onGemDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block == null) return;
        if (locationToGemUuid.containsKey(block.getLocation())) {
            event.setInstaBreak(true);
        }
    }

    // ==================== 宝石识别 ====================

    /**
     * 判断物品是否为权力宝石
     */
    public boolean isRuleGem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(ruleGemKey, PersistentDataType.BYTE);
    }

    /**
     * 判断方块是否为放置的权力宝石
     */
    public boolean isRuleGem(Block block) {
        if (block == null) return false;
        return locationToGemUuid.containsKey(block.getLocation());
    }

    /**
     * 从物品获取宝石 UUID
     */
    public UUID getGemUUID(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String uuidStr = pdc.get(uniqueIdKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从方块获取宝石 UUID
     */
    public UUID getGemUUID(Block block) {
        if (block == null) return null;
        return locationToGemUuid.get(block.getLocation());
    }

    // ==================== GemDefinition 查找 ====================

    /**
     * 查找 GemDefinition（带缓存）
     */
    public GemDefinition findGemDefinition(String key) {
        if (key == null) return null;
        GemDefinition cached = gemDefinitionCache.get(key.toLowerCase(ROOT_LOCALE));
        if (cached != null) return cached;
        for (GemDefinition d : gemParser.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(key)) {
                gemDefinitionCache.put(key.toLowerCase(ROOT_LOCALE), d);
                return d;
            }
        }
        return null;
    }

    /**
     * 重建 GemDefinition 缓存
     */
    public void rebuildGemDefinitionCache() {
        gemDefinitionCache.clear();
        java.util.List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs != null) {
            for (GemDefinition d : defs) {
                if (d.getGemKey() != null) {
                    gemDefinitionCache.put(d.getGemKey().toLowerCase(ROOT_LOCALE), d);
                }
            }
        }
    }

    // ==================== 宝石物品创建 ====================

    /**
     * 创建一颗宝石物品
     */
    public ItemStack createRuleGem(UUID gemId) {
        String gemKey = gemUuidToKey.getOrDefault(gemId, null);
        ItemStack ruleGem = new ItemStack(Material.RED_STAINED_GLASS, 1);
        boolean enchantedGlint = false;
        if (gemKey != null) {
            GemDefinition def = findGemDefinition(gemKey);
            if (def != null) {
                ruleGem = new ItemStack(def.getMaterial(), 1);
                enchantedGlint = def.isEnchanted();
            }
        }
        ItemMeta meta = ruleGem.getItemMeta();
        if (meta == null) return ruleGem;

        // 名称
        String defaultDisplayName = null;
        if (languageManager != null) {
            defaultDisplayName = languageManager.getMessage("messages.gem.default_display_name");
        }
        if (defaultDisplayName == null || defaultDisplayName.startsWith("Missing message")) {
            defaultDisplayName = "&cRule Gem";
        }
        String displayName = ChatColor.translateAlternateColorCodes('&', defaultDisplayName);

        // Lore
        java.util.List<String> lore = new java.util.ArrayList<>();
        if (gemKey != null) {
            GemDefinition def = findGemDefinition(gemKey);
            if (def != null && def.getDisplayName() != null) {
                displayName = ChatColor.translateAlternateColorCodes('&', def.getDisplayName());
            }
            if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
                for (String line : def.getLore()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line));
                }
            }
        }
        meta.setLore(lore);
        meta.setDisplayName(displayName);

        // 附魔光效
        if (enchantedGlint) {
            try {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
                org.cubexmc.gui.ItemBuilder.applyGlowEffect(meta);
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to apply enchanted glint effect to gem item: " + e.getMessage());
            }
        }

        // 写入 PDC
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(ruleGemKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString());
        if (gemKey != null) {
            pdc.set(gemKeyKey, PersistentDataType.STRING, gemKey);
        }

        ruleGem.setItemMeta(meta);
        return ruleGem;
    }

    /**
     * 获取宝石的材质
     */
    public Material getGemMaterial(UUID gemId) {
        String key = gemUuidToKey.get(gemId);
        if (key != null) {
            GemDefinition def = findGemDefinition(key);
            if (def != null && def.getMaterial() != null) return def.getMaterial();
        }
        return Material.RED_STAINED_GLASS;
    }

    // ==================== 工具方法 ====================

    /**
     * 判断材质是否需要支撑
     */
    public boolean isSupportRequired(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        if (name.endsWith("_TORCH") || name.endsWith("_CARPET") || name.endsWith("_CANDLE")) return true;
        if (name.startsWith("POTTED_")) return true;
        if ("SCULK_CATALYST".equals(name)) return true;
        try {
            if (!mat.isSolid()) return true;
        } catch (Throwable e) {
            plugin.getLogger().fine("Failed to check if material " + name + " is solid: " + e.getMessage());
        }
        return false;
    }

    /**
     * 判断某坐标下方是否有支撑
     */
    public boolean hasBlockSupport(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Location below = loc.clone().add(0, -1, 0);
        Block b = below.getBlock();
        if (b == null) return false;
        Material m = b.getType();
        try {
            return m.isSolid();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 解析 gem 标识符（UUID 或 key）
     */
    public UUID resolveGemIdentifier(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();

        // 1. 尝试完整 UUID
        try {
            UUID id = UUID.fromString(trimmed);
            if (gemUuidToKey.containsKey(id)) return id;
        } catch (Exception e) {
            plugin.getLogger().fine("Input '" + trimmed + "' is not a valid UUID, trying other formats: " + e.getMessage());
        }

        // 2. 尝试简短 UUID 前缀匹配
        if (trimmed.length() >= 8 && !trimmed.contains(" ")) {
            for (UUID id : gemUuidToKey.keySet()) {
                if (id.toString().toLowerCase().startsWith(trimmed.toLowerCase())) {
                    return id;
                }
            }
        }

        // 3. 按 gemKey/名称匹配
        String key = resolveGemKeyByNameOrKey(trimmed);
        if (key == null) return null;

        UUID firstHeld = null;
        for (Map.Entry<UUID, String> e : gemUuidToKey.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(key)) {
                UUID gemId = e.getKey();
                if (gemUuidToLocation.containsKey(gemId)) {
                    return gemId;
                }
                if (firstHeld == null && gemUuidToHolder.containsKey(gemId)) {
                    firstHeld = gemId;
                }
            }
        }
        return firstHeld;
    }

    /**
     * 按名称或 key 解析 gemKey
     */
    public String resolveGemKeyByNameOrKey(String input) {
        if (input == null || input.isEmpty()) return null;
        String lc = input.toLowerCase(ROOT_LOCALE);
        for (GemDefinition d : gemParser.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(input)) return d.getGemKey();
            String name = d.getDisplayName();
            if (name != null && ChatColor.stripColor(name).replace("§", "&").replace("&", "").toLowerCase().contains(lc)) {
                return d.getGemKey();
            }
        }
        return null;
    }

    /**
     * 确保 gemId 有对应的 gemKey
     */
    public void ensureGemKeyAssigned(UUID gemId) {
        if (gemUuidToKey.containsKey(gemId)) return;
        java.util.List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        String key = defs.get(new java.util.Random().nextInt(defs.size())).getGemKey();
        gemUuidToKey.put(gemId, key);
    }

    /**
     * 输出宝石状态到命令发送者
     */
    public void gemStatus(CommandSender sender) {
        Map<String, String> summary = new HashMap<>();
        summary.put("count", String.valueOf(gemParser.getRequiredCount()));
        summary.put("placed_count", String.valueOf(locationToGemUuid.size()));
        summary.put("held_count", String.valueOf(gemUuidToHolder.size()));
        languageManager.sendMessage(sender, "gem_status.total_expected", summary);
        languageManager.sendMessage(sender, "gem_status.total_counts", summary);

        java.util.List<Map.Entry<UUID, String>> entries = new java.util.ArrayList<>(gemUuidToKey.entrySet());
        entries.sort((a, b) -> {
            String ka = a.getValue() != null ? a.getValue().toLowerCase() : "";
            String kb = b.getValue() != null ? b.getValue().toLowerCase() : "";
            int c = ka.compareTo(kb);
            if (c != 0) return c;
            return a.getKey().toString().compareTo(b.getKey().toString());
        });

        boolean isPlayerSender = sender instanceof Player;
        for (Map.Entry<UUID, String> ent : entries) {
            UUID gemId = ent.getKey();
            String gemKey = ent.getValue();
            GemDefinition def = gemKey != null ? findGemDefinition(gemKey) : null;
            String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : "Gem";

            String statusText;
            Player holder = gemUuidToHolder.get(gemId);
            Location loc = gemUuidToLocation.get(gemId);

            if (holder != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("player", holder.getName());
                statusText = languageManager.formatMessage("gem_status.status_held", ph);
            } else if (loc != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("x", String.valueOf(loc.getBlockX()));
                ph.put("y", String.valueOf(loc.getBlockY()));
                ph.put("z", String.valueOf(loc.getBlockZ()));
                ph.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "?");
                statusText = languageManager.formatMessage("gem_status.status_placed", ph);
            } else {
                statusText = languageManager.getMessage("gem_status.status_unknown");
            }

            Map<String, String> linePh = new HashMap<>();
            linePh.put("gem_key", gemKey != null ? gemKey : "?");
            linePh.put("gem_name", displayName);
            linePh.put("uuid", gemId.toString().substring(0, 8));
            linePh.put("status", statusText);
            String plain = languageManager.formatMessage("gem_status.gem_line", linePh);

            if (isPlayerSender) {
                sendClickableGemStatus((Player) sender, gemId, plain, def);
            } else {
                sender.sendMessage(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', plain)));
            }
        }
    }

    private void sendClickableGemStatus(Player player, UUID gemId, String plain, GemDefinition def) {
        net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(
            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', plain)));
        
        StringBuilder loreBuilder = new StringBuilder();
        if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
            for (String line : def.getLore()) {
                loreBuilder.append(ChatColor.translateAlternateColorCodes('&', line)).append("\n");
            }
        } else {
            String noMoreInfo = languageManager != null ? languageManager.getMessage("gui.no_more_info") : "No more info";
            loreBuilder.append(ChatColor.GRAY).append(noMoreInfo);
        }

        net.md_5.bungee.api.chat.hover.content.Text text = 
            new net.md_5.bungee.api.chat.hover.content.Text(loreBuilder.toString().trim());
        comp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, text));

        String clickCmd = "/rulegems tp " + gemId.toString();
        comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
            net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, clickCmd));
        
        player.spigot().sendMessage(comp);
    }
}

