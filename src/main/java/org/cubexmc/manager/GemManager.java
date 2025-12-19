package org.cubexmc.manager;

// import java.util.ArrayList; // no longer used
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.RuleGems;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;

public class GemManager {
    private final RuleGems plugin;
    private final ConfigManager configManager;
    private final EffectUtils effectUtils;
    private final LanguageManager languageManager;
    private HistoryLogger historyLogger;

    private final NamespacedKey ruleGemKey;
    private final NamespacedKey uniqueIdKey;
    private final NamespacedKey gemKeyKey;
    private final Map<Location, UUID> locationToGemUuid = new HashMap<>();
    private final Map<UUID, Location> gemUuidToLocation = new HashMap<>(); // 反向映射，O(1) 查找
    private final Map<UUID, Player> gemUuidToHolder = new HashMap<>();
    private final Map<UUID, String> gemUuidToKey = new HashMap<>();
    // 归属：按 gemId 记录当前兑换归属玩家
    private final Map<java.util.UUID, java.util.UUID> gemIdToRedeemer = new HashMap<>();
    // 玩家已兑换的 gemKey 集合（派生自 gemIdToRedeemer，用于快速判断）
    private final Map<java.util.UUID, java.util.Set<String>> playerUuidToRedeemedKeys = new HashMap<>();
    // 玩家对每个 gemKey 的归属计数，用于"最后一件才撤"
    private final Map<java.util.UUID, java.util.Map<String, Integer>> ownerKeyCount = new HashMap<>();
    // 当前 inventory_grants 生效的 key（互斥筛选后选中的集合），用于保持“先生效者优先”与 held 限次判定
    private final Map<java.util.UUID, java.util.Set<String>> playerActiveHeldKeys = new HashMap<>();
    // Separate attachments: inventory-based vs redeem-based
    private final Map<java.util.UUID, org.bukkit.permissions.PermissionAttachment> invAttachments = new HashMap<>();
    private final Map<java.util.UUID, org.bukkit.permissions.PermissionAttachment> redeemAttachments = new HashMap<>();
    // Redeem ownership state (per-gemId now); key-level summary via ownerKeyCount
    private java.util.UUID fullSetOwner = null;
    // Queue revocations for offline players (persisted)
    private final Map<java.util.UUID, java.util.Set<String>> pendingPermRevokes = new HashMap<>();
    private final Map<java.util.UUID, java.util.Set<String>> pendingGroupRevokes = new HashMap<>();

    // Per-player per-gem-instance command allowances (held): player -> gemId -> label -> remaining uses
    private final Map<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> playerGemHeldUses = new HashMap<>();
    // Per-player per-gem-instance command allowances (redeemed): player -> gemId -> label -> remaining uses
    private final Map<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> playerGemRedeemUses = new HashMap<>();
    // Global allowances (e.g., redeem_all extras): player -> label -> remaining uses
    private final Map<java.util.UUID, java.util.Map<String, Integer>> playerGlobalAllowedUses = new HashMap<>();
    private static final java.util.Locale ROOT_LOCALE = java.util.Locale.ROOT;
    
    // GemDefinition 缓存，避免重复遍历列表
    private final Map<String, org.cubexmc.model.GemDefinition> gemDefinitionCache = new HashMap<>();
    
    // 宝石逃逸调度任务（Object 用于兼容 Bukkit 和 Folia 的不同返回类型）
    private final Map<UUID, Object> gemEscapeTasks = new HashMap<>();
    
    // 玩家名称缓存（用于离线玩家显示）
    private final Map<java.util.UUID, String> playerNameCache = new HashMap<>();

    public GemManager(RuleGems plugin, ConfigManager configManager, EffectUtils effectUtils,
                      LanguageManager languageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.effectUtils = effectUtils;
        this.languageManager = languageManager;
        this.ruleGemKey = new NamespacedKey(plugin, "rule_gem");
        this.uniqueIdKey = new NamespacedKey(plugin, "unique_id");
        this.gemKeyKey = new NamespacedKey(plugin, "gem_key");
    }

    @EventHandler
    public void onGemDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block == null) return;
        // 该方块登记为宝石：忽略材质硬度，允许一击破坏
        if (locationToGemUuid.containsKey(block.getLocation())) {
            event.setInstaBreak(true);
        }
    }

    public void loadGems() {
        // 获取 gemsData
        FileConfiguration gemsData = configManager.readGemsData();

        if (gemsData == null) {
            plugin.getLogger().warning("无法加载 gemsData 配置！请检查文件是否存在。");
            return;
        }
        
        // 清空所有缓存，避免 reload 时累积重复数据
        locationToGemUuid.clear();
        gemUuidToLocation.clear();
        gemUuidToHolder.clear();
        gemUuidToKey.clear();
        gemIdToRedeemer.clear();
        playerUuidToRedeemedKeys.clear();
        ownerKeyCount.clear();
        playerActiveHeldKeys.clear();
        pendingPermRevokes.clear();
        pendingGroupRevokes.clear();
        playerGemHeldUses.clear();
        playerGemRedeemUses.clear();
        playerGlobalAllowedUses.clear();
        playerNameCache.clear();
        fullSetOwner = null;
        // 读取宝石数据
        // 放置的宝石
        // 兼容旧键名 "placed-gams" 的笔误
        ConfigurationSection placedGemsSection = gemsData.getConfigurationSection("placed-gems");
        if (placedGemsSection == null) {
            placedGemsSection = gemsData.getConfigurationSection("placed-gams");
        }

        // 读取每位玩家已兑换的 gem key 列表
        ConfigurationSection redeemedSection = gemsData.getConfigurationSection("redeemed");
        if (redeemedSection != null) {
            for (String playerUuidStr : redeemedSection.getKeys(false)) {
                java.util.UUID pu = java.util.UUID.fromString(playerUuidStr);
                java.util.List<String> list = redeemedSection.getStringList(playerUuidStr);
                if (list != null) {
                    playerUuidToRedeemedKeys.put(pu, new java.util.HashSet<>(list));
                }
            }
        }
        // 读取玩家名称缓存
        ConfigurationSection namesSection = gemsData.getConfigurationSection("player_names");
        if (namesSection != null) {
            for (String uuidStr : namesSection.getKeys(false)) {
                try {
                    java.util.UUID uid = java.util.UUID.fromString(uuidStr);
                    String name = namesSection.getString(uuidStr);
                    if (name != null && !name.isEmpty()) {
                        playerNameCache.put(uid, name);
                    }
                } catch (Exception ignored) {}
            }
        }
        // 兼容旧结构：redeem_owner 按 key；新结构：redeem_owner_by_id 按 gemId
        ConfigurationSection ownerById = gemsData.getConfigurationSection("redeem_owner_by_id");
        if (ownerById != null) {
            for (String gid : ownerById.getKeys(false)) {
                try {
                    java.util.UUID gem = java.util.UUID.fromString(gid);
                    java.util.UUID player = java.util.UUID.fromString(ownerById.getString(gid));
                    gemIdToRedeemer.put(gem, player);
                } catch (Exception ignored) {}
            }
        } else {
            ConfigurationSection ownerSec = gemsData.getConfigurationSection("redeem_owner");
            if (ownerSec != null) {
                for (String gemKey : ownerSec.getKeys(false)) {
                    String uuidStr = ownerSec.getString(gemKey);
                    try {
                        java.util.UUID player = java.util.UUID.fromString(uuidStr);
                        // 将所有当前已知的该 key 的 gemId 归属迁移给该玩家
                        for (java.util.Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
                            if (e.getValue() != null && e.getValue().equalsIgnoreCase(gemKey)) {
                                gemIdToRedeemer.put(e.getKey(), player);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        ConfigurationSection fso = gemsData.getConfigurationSection("full_set_owner");
        if (fso != null) {
            String u = fso.getString("uuid");
            try { fullSetOwner = java.util.UUID.fromString(u); } catch (Exception ignored) {}
        }
        // Load pending revocations
        ConfigurationSection pr = gemsData.getConfigurationSection("pending_revokes.permissions");
        if (pr != null) {
            for (String pid : pr.getKeys(false)) {
                try {
                    java.util.UUID id = java.util.UUID.fromString(pid);
                    java.util.List<String> list = pr.getStringList(pid);
                    if (list != null && !list.isEmpty()) pendingPermRevokes.put(id, new java.util.HashSet<>(list));
                } catch (Exception ignored) {}
            }
        }
        ConfigurationSection gr = gemsData.getConfigurationSection("pending_revokes.groups");
        if (gr != null) {
            for (String pid : gr.getKeys(false)) {
                try {
                    java.util.UUID id = java.util.UUID.fromString(pid);
                    java.util.List<String> list = gr.getStringList(pid);
                    if (list != null && !list.isEmpty()) pendingGroupRevokes.put(id, new java.util.HashSet<>(list));
                } catch (Exception ignored) {}
            }
        }
        // 读取指令可用次数
        // 读取指令可用次数：实例级(held/redeemed) + 全局
        ConfigurationSection au = gemsData.getConfigurationSection("allowed_uses");
        if (au != null) {
            for (String playerId : au.getKeys(false)) {
                try {
                    java.util.UUID uid = java.util.UUID.fromString(playerId);
                    ConfigurationSection playerSec = au.getConfigurationSection(playerId);
                    if (playerSec == null) continue;
                    // held instances
                    ConfigurationSection heldSec = playerSec.getConfigurationSection("held_instances");
                    if (heldSec != null) {
                        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perHeld = new java.util.HashMap<>();
                        for (String gid : heldSec.getKeys(false)) {
                            try {
                                java.util.UUID gem = java.util.UUID.fromString(gid);
                                ConfigurationSection labels = heldSec.getConfigurationSection(gid);
                                java.util.Map<String, Integer> map = new java.util.HashMap<>();
                                if (labels != null) {
                                    for (String l : labels.getKeys(false)) {
                                        map.put(l.toLowerCase(java.util.Locale.ROOT), labels.getInt(l, 0));
                                    }
                                }
                                perHeld.put(gem, map);
                            } catch (Exception ignored2) {}
                        }
                        if (!perHeld.isEmpty()) playerGemHeldUses.put(uid, perHeld);
                    }
                    // redeemed instances
                    ConfigurationSection redSec = playerSec.getConfigurationSection("redeemed_instances");
                    if (redSec != null) {
                        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = new java.util.HashMap<>();
                        for (String gid : redSec.getKeys(false)) {
                            try {
                                java.util.UUID gem = java.util.UUID.fromString(gid);
                                ConfigurationSection labels = redSec.getConfigurationSection(gid);
                                java.util.Map<String, Integer> map = new java.util.HashMap<>();
                                if (labels != null) {
                                    for (String l : labels.getKeys(false)) {
                                        map.put(l.toLowerCase(java.util.Locale.ROOT), labels.getInt(l, 0));
                                    }
                                }
                                perRed.put(gem, map);
                            } catch (Exception ignored2) {}
                        }
                        if (!perRed.isEmpty()) playerGemRedeemUses.put(uid, perRed);
                    }
                    // backward compatibility: legacy "instances" -> treat as redeemed_instances
                    ConfigurationSection legacy = playerSec.getConfigurationSection("instances");
                    if (legacy != null && !legacy.getKeys(false).isEmpty() && !playerGemRedeemUses.containsKey(uid)) {
                        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = new java.util.HashMap<>();
                        for (String gid : legacy.getKeys(false)) {
                            try {
                                java.util.UUID gem = java.util.UUID.fromString(gid);
                                ConfigurationSection labels = legacy.getConfigurationSection(gid);
                                java.util.Map<String, Integer> map = new java.util.HashMap<>();
                                if (labels != null) {
                                    for (String l : labels.getKeys(false)) {
                                        map.put(l.toLowerCase(java.util.Locale.ROOT), labels.getInt(l, 0));
                                    }
                                }
                                perRed.put(gem, map);
                            } catch (Exception ignored2) {}
                        }
                        if (!perRed.isEmpty()) playerGemRedeemUses.put(uid, perRed);
                    }
                    // global
                    ConfigurationSection globSec = playerSec.getConfigurationSection("global");
                    if (globSec != null) {
                        java.util.Map<String, Integer> map = new java.util.HashMap<>();
                        for (String l : globSec.getKeys(false)) {
                            map.put(l.toLowerCase(java.util.Locale.ROOT), globSec.getInt(l, 0));
                        }
                        if (!map.isEmpty()) playerGlobalAllowedUses.put(uid, map);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (placedGemsSection != null) {
            for (String uuidStr : placedGemsSection.getKeys(false)) {
                // key 类似 "gems.<uuid>"
                String worldName = placedGemsSection.getString(uuidStr + ".world");
                double x = placedGemsSection.getDouble(uuidStr + ".x");
                double y = placedGemsSection.getDouble(uuidStr + ".y");
                double z = placedGemsSection.getDouble(uuidStr + ".z");
                String gemKey = placedGemsSection.getString(uuidStr + ".gem_key", "default");

                World w = Bukkit.getWorld(worldName);
                if (w == null) {
                    continue;
                }

                Location loc = new Location(w, x, y, z);
                UUID gemId;
                try {
                    gemId = UUID.fromString(uuidStr);
                } catch (Exception ignored) {
                    continue;
                }

                locationToGemUuid.put(loc, gemId);
                gemUuidToLocation.put(gemId, loc);
                gemUuidToKey.put(gemId, gemKey);
            }
        }

        // 持有的宝石
        ConfigurationSection heldGemsSection = gemsData.getConfigurationSection("held-gems");
        if (heldGemsSection != null) {
            for (String uuidStr : heldGemsSection.getKeys(false)) {
                // key 就是uuid
                String playerUUIDStr = heldGemsSection.getString(uuidStr + ".player_uuid");
                String gemKey = heldGemsSection.getString(uuidStr + ".gem_key", "default");

                if (playerUUIDStr == null) {
                    continue;
                }
                UUID playerUUID;
                UUID gemId;
                try { 
                    playerUUID = UUID.fromString(playerUUIDStr);
                    gemId = UUID.fromString(uuidStr);
                } catch (Exception ignored) { 
                    continue; 
                }
                
                // 将 gemKey 先登记，无论玩家是否在线
                gemUuidToKey.put(gemId, gemKey);
                
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    // 玩家在线：恢复到背包
                    gemUuidToHolder.put(gemId, player);
                } else {
                    // 玩家离线或不存在：放置到世界随机位置
                    randomPlaceGem(gemId);
                }
            }
        }

        // Apply pending revocations for any already online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPendingRevokesIfAny(p);
        }

        // Log loaded counts (match lang keys expecting %count%)
        Map<String, String> placeholders1 = new HashMap<>();
        placeholders1.put("count", String.valueOf(locationToGemUuid.size()));
        languageManager.logMessage("gems_loaded", placeholders1);
        Map<String, String> placeholders2 = new HashMap<>();
        placeholders2.put("count", String.valueOf(gemUuidToHolder.size()));
        languageManager.logMessage("gems_held_loaded", placeholders2);
        
        // 重建 GemDefinition 缓存
        rebuildGemDefinitionCache();
        
        // 初始化宝石逃逸任务
        initializeEscapeTasks();
    }

    public void saveGems() {
        // 取消所有逃逸任务
        cancelAllEscapeTasks();
        FileConfiguration gemsData = configManager.getGemsData();
        gemsData.set("placed-gems", null);
        gemsData.set("held-gems", null);
        gemsData.set("redeemed", null);
        gemsData.set("redeem_owner", null);
        gemsData.set("redeem_owner_by_id", null);
        gemsData.set("full_set_owner", null);
        gemsData.set("pending_revokes", null);
        gemsData.set("allowed_uses", null);
        gemsData.set("player_names", null);

        for (Location loc : locationToGemUuid.keySet()) {
            String path = "placed-gems." + locationToGemUuid.get(loc).toString();
            gemsData.set(path + ".world", loc.getWorld().getName());
            gemsData.set(path + ".x", loc.getX());
            gemsData.set(path + ".y", loc.getY());
            gemsData.set(path + ".z", loc.getZ());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(locationToGemUuid.get(loc)));
        }
        for (UUID gemId : gemUuidToHolder.keySet()) {
            Player player = gemUuidToHolder.get(gemId);
            String path = "held-gems." + gemId.toString();
            gemsData.set(path + ".player", player.getName());
            gemsData.set(path + ".player_uuid", player.getUniqueId().toString());
            gemsData.set(path + ".gem_key", gemUuidToKey.get(gemId));
        }
        // 保存每位玩家已兑换的 gem key
        for (Map.Entry<java.util.UUID, java.util.Set<String>> e : playerUuidToRedeemedKeys.entrySet()) {
            String base = "redeemed." + e.getKey().toString();
            gemsData.set(base, new java.util.ArrayList<>(e.getValue()));
        }
        for (Map.Entry<java.util.UUID, java.util.UUID> e : gemIdToRedeemer.entrySet()) {
            gemsData.set("redeem_owner_by_id." + e.getKey().toString(), e.getValue().toString());
        }
        if (fullSetOwner != null) {
            gemsData.set("full_set_owner.uuid", fullSetOwner.toString());
        }
        // Save pending revocations
        for (Map.Entry<java.util.UUID, java.util.Set<String>> e : pendingPermRevokes.entrySet()) {
            gemsData.set("pending_revokes.permissions." + e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        for (Map.Entry<java.util.UUID, java.util.Set<String>> e : pendingGroupRevokes.entrySet()) {
            gemsData.set("pending_revokes.groups." + e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        // 保存指令可用次数
        for (Map.Entry<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> e : playerGemHeldUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<java.util.UUID, java.util.Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    gemsData.set(base + ".held_instances." + inst.getKey().toString() + "." + l.getKey(), l.getValue());
                }
            }
        }
        for (Map.Entry<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> e : playerGemRedeemUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<java.util.UUID, java.util.Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    gemsData.set(base + ".redeemed_instances." + inst.getKey().toString() + "." + l.getKey(), l.getValue());
                }
            }
        }
        for (Map.Entry<java.util.UUID, java.util.Map<String, Integer>> e : playerGlobalAllowedUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<String, Integer> l : e.getValue().entrySet()) {
                gemsData.set(base + ".global." + l.getKey(), l.getValue());
            }
        }
        // 保存玩家名称缓存
        for (Map.Entry<java.util.UUID, String> e : playerNameCache.entrySet()) {
            gemsData.set("player_names." + e.getKey().toString(), e.getValue());
        }
        configManager.saveGemData(gemsData);
    }

    /**
     * 确保配置中定义的每一颗 gem 至少存在一颗（放置或持有）。
     * 若缺失，则为该定义生成新的 UUID 并随机放置。
     */
    public void ensureConfiguredGemsPresent() {
        List<org.cubexmc.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        // 统计现有每个 key 的实例数
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (java.util.Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
            String k = e.getValue();
            if (k == null) continue;
            String lk = k.toLowerCase();
            counts.put(lk, counts.getOrDefault(lk, 0) + 1);
        }
        for (org.cubexmc.model.GemDefinition d : defs) {
            String k = d.getGemKey();
            if (k == null) continue;
            String lk = k.toLowerCase();
            int have = counts.getOrDefault(lk, 0);
            int need = Math.max(1, d.getCount());
            for (int i = have; i < need; i++) {
                java.util.UUID newId = java.util.UUID.randomUUID();
                gemUuidToKey.put(newId, k);
                randomPlaceGem(newId);
            }
        }
    }

    @EventHandler
    public void onGemPlaced(BlockPlaceEvent event) {
        // 放置宝石只更新位置，不触发授予权限
        ItemStack inHand = event.getItemInHand();

        // 判断这个 block 是否是我们的"特殊方块"
        if (!isRuleGem(inHand)) {
            return;
        }

        // 如果是宝石，先拿到它的 UUID
        UUID gemId = getGemUUID(inHand);
        if (gemId == null) {
            // 理论上不会出现，但防御性判断
            gemId = UUID.randomUUID();
        }

        Block block = event.getBlockPlaced();
        Location placedLoc = block.getLocation();
        Player placer = event.getPlayer();

        // 检查是否是放置兑换（祭坛模式）- 每颗宝石有独立的祭坛位置
        String currentGemKey = gemUuidToKey.get(gemId);
        org.cubexmc.model.GemDefinition matchedDef = findMatchingAltarGem(placedLoc, currentGemKey);
        if (matchedDef != null) {
            // 触发放置兑换
            handlePlaceRedeem(placer, gemId, placedLoc, block, matchedDef);
            return;
        }

        // 更新放置和持有信息
        gemUuidToHolder.remove(gemId);
        locationToGemUuid.put(placedLoc, gemId);
        gemUuidToLocation.put(gemId, placedLoc);

        // 记录宝石放置
        if (historyLogger != null) {
            String gemKey = gemUuidToKey.get(gemId);
            String locationStr = String.format("(%d, %d, %d) %s", 
                placedLoc.getBlockX(), 
                placedLoc.getBlockY(), 
                placedLoc.getBlockZ(),
                placedLoc.getWorld() != null ? placedLoc.getWorld().getName() : "unknown");
            historyLogger.logGemPlace(placer, gemKey != null ? gemKey : gemId.toString(), locationStr);
        }

        // Creative players keep placement items by default; ensure the gem is removed from inventory post-placement
        if (placer != null) {
            UUID placedGemId = gemId;
            SchedulerUtil.entityRun(plugin, placer, () -> {
                removeGemItemFromInventory(placer, placedGemId);
                try { placer.updateInventory(); } catch (Throwable ignored) {}
            }, 1L, -1L);
        }

        // 不再通过放置触发权限或胜利逻辑
    }

    @EventHandler
    public void onGemBroken(BlockBreakEvent event) {
        Block block = event.getBlock();

        // 如果这个坐标存在于 locationToGemUuid，说明是宝石方块
        if (locationToGemUuid.containsKey(block.getLocation())) {
            // 阻止原始方块材料的默认掉落，避免重复获取材料
            event.setDropItems(false);
            try { event.setExpToDrop(0); } catch (Throwable ignored) {}
            // 先取到UUID
            UUID gemId = locationToGemUuid.get(block.getLocation());
            Player player = event.getPlayer();
            Inventory inv = player.getInventory();
//            Map<String, String> placeholders = new HashMap<>();
//            placeholders.put("slot", String.valueOf(inv.firstEmpty()));
//            languageManager.logMessage("inventory_full", placeholders);
            if (inv.firstEmpty() == -1) {
                // 背包已满：取消破坏，保留原位置上的宝石，不生成任何掉落实体
                languageManager.logMessage("inventory_full");
                event.setCancelled(true);
                return;
            } else {
                // 给破坏者：若开启"背包即生效"，直接入包；否则仍可入包但权限不会因背包自动授予
                ItemStack gemItem = createRuleGem(gemId);
                inv.addItem(gemItem);
                gemUuidToHolder.put(gemId, player);
                // 取消宝石逃逸任务（被拾取后不再逃逸）
                cancelEscape(gemId);
                unplaceRuleGem(block.getLocation(), gemId);
                // inventory_grants: 处理持有者与限次指令的归属与发放
                handleInventoryOwnershipOnPickup(player, gemId);
                // 每颗宝石自定义拾取效果（可选），否则用全局（此处是破坏方块后"入包"的瞬间）
                org.cubexmc.model.GemDefinition def = findGemDefinition(gemUuidToKey.get(gemId));
                if (def != null && def.getOnPickup() != null) {
                    effectUtils.executeCommands(def.getOnPickup(), Collections.singletonMap("%player%", player.getName()));
                    effectUtils.playLocalSound(player.getLocation(), def.getOnPickup(), 1.0f, 1.0f);
                    effectUtils.playParticle(player.getLocation(), def.getOnPickup());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();

        for (ItemStack item : inv.getContents()) {
            if (isRuleGem(item)) {
                // 拿到这颗宝石的UUID
                UUID gemId = getGemUUID(item);

                // 移除背包物品
                inv.remove(item);

                // 在玩家脚下放置方块
                Location loc = player.getLocation();
                
                gemUuidToHolder.remove(gemId);
                placeRuleGem(loc, gemId);
            }
        }
    }

    @EventHandler
    // 丢弃将自动放置在地面
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        UUID gemId = getGemUUID(item);
        if (isRuleGem(item)) {
            // 确保 Gem 只能作为实体存在于地面
            // 如果有其他逻辑需求，可以在这里处理
            event.getItemDrop().remove();
            gemUuidToHolder.remove(gemId);
            Location loc = event.getItemDrop().getLocation();
            triggerScatterEffects(gemId, loc, event.getPlayer().getName());
            placeRuleGem(loc, gemId);
        }
    }

    /**
     * 监听玩家死亡事件：从掉落列表移除宝石并放置为方块
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLocation = player.getLocation();
        String playerName = player.getName();
        
        java.util.Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (isRuleGem(item)) {
                UUID gemId = getGemUUID(item);
                iterator.remove();
                gemUuidToHolder.remove(gemId);
                triggerScatterEffects(gemId, deathLocation, playerName);
                placeRuleGem(deathLocation, gemId);
            }
        }
    }

    // onPlayerJoin
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (isRuleGem(item)) {
                UUID gemId = getGemUUID(item);
                if (!gemUuidToHolder.containsKey(gemId)) {
                    inv.remove(item);
                    gemUuidToHolder.remove(gemId);
                }
            }
        }
        // Apply any pending revocations queued while offline
        applyPendingRevokesIfAny(player);
    }

    /**
     * 随机放置宝石
     */
    public void scatterGems() {
        languageManager.logMessage("scatter_start");
        int scatteredCount = 0;
        // 1) 取消世界中的所有已放置宝石
        Set<Location> locCopy = new HashSet<>(locationToGemUuid.keySet());
        for (Location loc : locCopy) {
            unplaceRuleGem(loc, locationToGemUuid.get(loc));
        }
        // 清空坐标-宝石映射，确保后续重新计算
        locationToGemUuid.clear();
        gemUuidToLocation.clear();

        // 2) 收回所有玩家背包中的宝石，并清空"持有映射"
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inv = player.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (isRuleGem(item)) {
                    inv.remove(item);
                }
            }
        }
        gemUuidToHolder.clear();

        // 3) 清理旧的 UUID->gemKey 关系，避免历史残留
        gemUuidToKey.clear();

        languageManager.logMessage("gems_recollected");
        List<org.cubexmc.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs != null && !defs.isEmpty()) {
            scatteredCount = 0;
            for (org.cubexmc.model.GemDefinition def : defs) {
                int cnt = Math.max(1, def.getCount());
                for (int i = 0; i < cnt; i++) {
                    UUID gemId = UUID.randomUUID();
                    gemUuidToKey.put(gemId, def.getGemKey());
                    randomPlaceGem(gemId);
                    scatteredCount++;
                }
                if (def.getOnScatter() != null) {
                    // 播放一次代表性的散落效果（选该 key 的任一实例位置）
                    UUID sample = null;
                    for (java.util.Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
                        if (def.getGemKey().equalsIgnoreCase(e.getValue())) { sample = e.getKey(); break; }
                    }
                    if (sample != null) {
                        Location placedLoc = findLocationByGemId(sample);
                        triggerScatterEffects(sample, placedLoc, null, false);
                    }
                }
            }
        } else {
            // 没有 per-gem 定义，按数量生成（向后兼容）
            int toPlace = Math.max(0, configManager.getRequiredCount());
            scatteredCount = toPlace;
            for (int i = 0; i < toPlace; i++) {
                UUID gemId = UUID.randomUUID();
                randomPlaceGem(gemId);
            }
        }
        // 实际散落数量以当前已放置统计为准
        Map<String, String> placeholders = new HashMap<>();
        // 使用计划散落数量，避免 Folia 下区域任务尚未完成导致的统计偏差
        placeholders.put("count", String.valueOf(scatteredCount));
        languageManager.logMessage("gems_scattered", placeholders);

        ExecuteConfig gemScatterExecute = configManager.getGemScatterExecute();
        effectUtils.executeCommands(gemScatterExecute, placeholders);
        effectUtils.playGlobalSound(gemScatterExecute, 1.0f, 1.0f);
        // 保存到配置文件
        for (Player p : Bukkit.getOnlinePlayers()) {
            languageManager.showTitle(p, "gems_scattered", placeholders);
        }
        saveGems();
    }

    /**
     * 查看宝石状态
     * 显示宝石数量，每个宝石的坐标或者持有者
     */
    public void gemStatus(CommandSender sender) {
        // 输出总览
        Map<String, String> summary = new HashMap<>();
        summary.put("count", String.valueOf(configManager.getRequiredCount()));
        summary.put("placed_count", String.valueOf(locationToGemUuid.size()));
        summary.put("held_count", String.valueOf(gemUuidToHolder.size()));
        languageManager.sendMessage(sender, "gem_status.total_expected", summary);
        languageManager.sendMessage(sender, "gem_status.total_counts", summary);

        // 逐颗宝石列出：[gem_key] gem_name: 被<持有人>持有 / 放置在<x,y,z world>
        // 遍历已知的全部宝石（以 gemUuidToKey 为准）
        java.util.List<java.util.Map.Entry<java.util.UUID, String>> entries = new java.util.ArrayList<>(gemUuidToKey.entrySet());
        // 稳定排序：按 gem_key 再按 UUID
        entries.sort((a, b) -> {
            String ka = a.getValue() != null ? a.getValue().toLowerCase() : "";
            String kb = b.getValue() != null ? b.getValue().toLowerCase() : "";
            int c = ka.compareTo(kb);
            if (c != 0) return c;
            return a.getKey().toString().compareTo(b.getKey().toString());
        });

        boolean isPlayerSender = sender instanceof Player;
        for (java.util.Map.Entry<java.util.UUID, String> ent : entries) {
            java.util.UUID gemId = ent.getKey();
            String gemKey = ent.getValue();
            org.cubexmc.model.GemDefinition def = gemKey != null ? findGemDefinition(gemKey) : null;
            String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : "Gem";

            // 构造状态
            String statusText;
            Player holder = gemUuidToHolder.get(gemId);
            Location loc = findLocationByGemId(gemId);
            if (holder != null && holder.isOnline()) {
                Map<String, String> ph = new HashMap<>();
                ph.put("player", holder.getName());
                statusText = languageManager.formatMessage("messages.gem_status.status_held", ph);
            } else if (loc != null) {
                String worldName = (loc.getWorld() != null ? loc.getWorld().getName() : "world");
                Map<String, String> ph = new HashMap<>();
                ph.put("x", String.valueOf(loc.getBlockX()));
                ph.put("y", String.valueOf(loc.getBlockY()));
                ph.put("z", String.valueOf(loc.getBlockZ()));
                ph.put("world", worldName);
                statusText = languageManager.formatMessage("messages.gem_status.status_placed", ph);
            } else {
                statusText = languageManager.formatMessage("messages.gem_status.status_unknown", java.util.Collections.emptyMap());
            }

            Map<String, String> linePh = new HashMap<>();
            linePh.put("gem_key", gemKey != null ? gemKey : "unknown");
            linePh.put("gem_name", displayName);
            linePh.put("status", statusText);
            // 添加 UUID 短形式（前8位）和完整形式
            String shortUuid = gemId.toString().substring(0, 8);
            linePh.put("uuid", shortUuid);
            linePh.put("full_uuid", gemId.toString());
            String plain = languageManager.formatMessage("messages.gem_status.gem_line", linePh);

            // 组件：hover 显示 lore；click 传送
            if (isPlayerSender) {
                Player ps = (Player) sender;
                net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', plain));

                // Hover lore 构建
                StringBuilder loreBuilder = new StringBuilder();
                if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
                    for (String line : def.getLore()) {
                        String l = org.bukkit.ChatColor.translateAlternateColorCodes('&', line);
                        loreBuilder.append(l).append("\n");
                    }
                } else {
                    loreBuilder.append(org.bukkit.ChatColor.GRAY).append("没有更多信息");
                }
        // Prefer content-based HoverEvent to avoid deprecation
        net.md_5.bungee.api.chat.hover.content.Text text = new net.md_5.bungee.api.chat.hover.content.Text(loreBuilder.toString().trim());
        comp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
            text
        ));

        // Click 行为：/rulegems tp <uuid>
                String clickCmd = "/rulegems tp " + gemId.toString();
                comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND,
                        clickCmd
                ));
                ps.spigot().sendMessage(comp);
            } else {
                // 非玩家：纯文本输出
                sender.sendMessage(org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', plain)));
            }
        }
    }

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
     * 判断方块是否为放置了的权力宝石
     */
    private boolean isRuleGem(Block block) {
        // 判断 block 是否是为权力宝石（使用方块坐标作为键）
        Location loc = block.getLocation();
        return locationToGemUuid.containsKey(loc);
    }

    /**
     * 根据指定的 UUID，创建一颗"权力宝石"物品。
     * @param gemId 这颗宝石的专属 UUID
     */
    public ItemStack createRuleGem(UUID gemId) {
        // 根据 gemKey 决定外观，若无则回退到全局默认
        String gemKey = gemUuidToKey.getOrDefault(gemId, null);
        ItemStack ruleGem = new ItemStack(Material.RED_STAINED_GLASS, 1);
        boolean enchantedGlint = false;
        if (gemKey != null) {
            org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
            if (def != null) {
                ruleGem = new ItemStack(def.getMaterial(), 1);
                enchantedGlint = def.isEnchanted();
            }
        }
        ItemMeta meta = ruleGem.getItemMeta();
        if (meta == null) return ruleGem;
        
        // 名称按定义或默认
        String defaultDisplayName = null;
        if (languageManager != null) {
            defaultDisplayName = languageManager.getMessage("messages.gem.default_display_name");
        }
        if (defaultDisplayName == null || defaultDisplayName.startsWith("Missing message")) {
            defaultDisplayName = "&cRule Gem";
        }
        String displayName = ChatColor.translateAlternateColorCodes('&', defaultDisplayName);
        // lore（若定义存在则带上定义）
        java.util.List<String> lore = new java.util.ArrayList<>();
        if (gemKey != null) {
            org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
            if (def != null && def.getDisplayName() != null) {
                displayName = ChatColor.translateAlternateColorCodes('&', def.getDisplayName());
            }
            if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
                for (String line : def.getLore()) {
                    lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
                }
            }
        } else {
            // 无 key 信息时不再追加任何调试 Lore 行
        }
        meta.setLore(lore);
        meta.setDisplayName(displayName);

        // 仅用于外观区分：添加隐形附魔实现发光效果
        if (enchantedGlint) {
            try {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
                // 选择一个影响最小的附魔作为"发光触发器"
                meta.addEnchant(Enchantment.LUCK, 1, true);
            } catch (Throwable ignored) { /* 某些服务端可能不支持 */ }
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 标记这是宝石
        pdc.set(ruleGemKey, PersistentDataType.BYTE, (byte) 1);
        // 写入UUID & gemKey
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString());
        if (gemKey != null) {
            pdc.set(gemKeyKey, PersistentDataType.STRING, gemKey);
        }

        ruleGem.setItemMeta(meta);
        return ruleGem;
    }

    private org.cubexmc.model.GemDefinition findGemDefinition(String key) {
        if (key == null) return null;
        // 使用缓存进行 O(1) 查找
        org.cubexmc.model.GemDefinition cached = gemDefinitionCache.get(key.toLowerCase(ROOT_LOCALE));
        if (cached != null) return cached;
        // 缓存未命中时遍历并更新缓存
        for (org.cubexmc.model.GemDefinition d : configManager.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(key)) {
                gemDefinitionCache.put(key.toLowerCase(ROOT_LOCALE), d);
                return d;
            }
        }
        return null;
    }
    
    /**
     * 重建 GemDefinition 缓存（reload 时调用）
     */
    private void rebuildGemDefinitionCache() {
        gemDefinitionCache.clear();
        List<org.cubexmc.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs != null) {
            for (org.cubexmc.model.GemDefinition d : defs) {
                if (d.getGemKey() != null) {
                    gemDefinitionCache.put(d.getGemKey().toLowerCase(ROOT_LOCALE), d);
                }
            }
        }
    }

    private void ensureGemKeyAssigned(UUID gemId) {
        if (gemUuidToKey.containsKey(gemId)) return;
        List<org.cubexmc.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) return;
        String key = defs.get(new Random().nextInt(defs.size())).getGemKey();
        gemUuidToKey.put(gemId, key);
    }

    private void grantPermissions(Player player, List<String> perms) {
        org.bukkit.permissions.PermissionAttachment attachment = redeemAttachments.computeIfAbsent(player.getUniqueId(), p -> player.addAttachment(plugin));
        for (String node : perms) {
            if (node == null || node.trim().isEmpty()) continue;
            attachment.setPermission(node, true);
        }
        player.recalculatePermissions();
    }

    // 兑换场景：除附件外，若存在 Vault 权限后端（如 LuckPerms），同时向后端添加永久节点
    private void grantRedeemPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        // 先本地附件生效（即时）
        grantPermissions(player, perms);
        // 再持久化到权限后端
        if (plugin.getVaultPerms() != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty()) continue;
                try { plugin.getVaultPerms().playerAdd(player, node); } catch (Exception ignored) {}
            }
        }
    }

    private void incrementOwnerKeyCount(java.util.UUID owner, String key, org.cubexmc.model.GemDefinition def) {
        if (owner == null || key == null) return;
        java.util.Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new java.util.HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = before + 1;
        map.put(key, after);
        if (before == 0 && def != null) {
            // 0->1：发放权限与（若 inventory_grants 关闭或兑换场景）初始化额度；这里复用现有发放逻辑
            Player p = org.bukkit.Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                if (def.getPermissions() != null && !def.getPermissions().isEmpty()) {
                    grantRedeemPermissions(p, def.getPermissions());
                }
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                    try { plugin.getVaultPerms().playerAddGroup(p, def.getVaultGroup()); } catch (Exception ignored) {}
                }
                try { p.recalculatePermissions(); } catch (Throwable ignored) {}
            }
        }
    }

    private void decrementOwnerKeyCount(java.util.UUID owner, String key, org.cubexmc.model.GemDefinition def) {
        if (owner == null || key == null) return;
        java.util.Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new java.util.HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = Math.max(0, before - 1);
        map.put(key, after);
        if (after == 0 && def != null) {
            // 1->0：撤回权限与额度
            Player p = org.bukkit.Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                if (def.getPermissions() != null) revokeNodes(p, def.getPermissions());
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                    try { plugin.getVaultPerms().playerRemoveGroup(p, def.getVaultGroup()); } catch (Exception ignored) {}
                }
                try { p.recalculatePermissions(); } catch (Throwable ignored) {}
                if (historyLogger != null) {
                    historyLogger.logPermissionRevoke(
                        owner.toString(),
                        p.getName(),
                        key,
                        def.getDisplayName(),
                        def.getPermissions(),
                        def.getVaultGroup(),
                        "归属切换：失去最后一件该类型宝石"
                    );
                }
            } else {
                // 离线撤销：队列权限与组；额度直接移除
                queueOfflineRevokes(owner,
                        def.getPermissions() != null ? def.getPermissions() : java.util.Collections.emptyList(),
                        (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty()) ? java.util.Collections.singleton(def.getVaultGroup()) : java.util.Collections.emptySet());
                if (historyLogger != null) {
                    historyLogger.logPermissionRevoke(
                        owner.toString(),
                        "未知(离线)",
                        key,
                        def.getDisplayName(),
                        def.getPermissions(),
                        def.getVaultGroup(),
                        "归属切换：失去最后一件该类型宝石（离线撤销）"
                    );
                }
            }
        }
    }

    public ConfigManager getConfigManager() { return configManager; }

    public void setHistoryLogger(HistoryLogger historyLogger) {
        this.historyLogger = historyLogger;
    }

    public void recalculateGrants(Player player) {
        if (!configManager.isInventoryGrantsEnabled()) return;
        // 先收集当前背包中的 key（保持扫描顺序，去重）
        java.util.List<String> presentKeysOrdered = new java.util.ArrayList<>();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (!isRuleGem(item)) continue;
            UUID id = getGemUUID(item);
            String key = gemUuidToKey.get(id);
            if (key == null) continue;
            String k = key.toLowerCase(java.util.Locale.ROOT);
            if (!presentKeysOrdered.contains(k)) presentKeysOrdered.add(k);
        }
        // 基于上次选中的 active 集合优先保留，新增时再做互斥筛选
        java.util.Set<String> previouslyActive = playerActiveHeldKeys.getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());
        java.util.Set<String> selectedKeys = new java.util.LinkedHashSet<>();
        for (String k : presentKeysOrdered) {
            if (previouslyActive.contains(k)) selectedKeys.add(k);
        }
        for (String k : presentKeysOrdered) {
            if (selectedKeys.contains(k)) continue;
            if (!conflictsWithSelected(k, selectedKeys)) selectedKeys.add(k);
        }
        playerActiveHeldKeys.put(player.getUniqueId(), selectedKeys);
        // 聚合权限
        java.util.Set<String> shouldHave = new java.util.HashSet<>();
        for (String k : selectedKeys) {
            org.cubexmc.model.GemDefinition def = findGemDefinition(k);
            if (def == null) continue;
            if (def.getPermissions() != null) {
                for (String node : def.getPermissions()) {
                    if (node != null && !node.trim().isEmpty()) shouldHave.add(node);
                }
            }
        }
        org.bukkit.permissions.PermissionAttachment attachment = invAttachments.computeIfAbsent(player.getUniqueId(), p -> player.addAttachment(plugin));
        // 现有的权限节点
        java.util.Set<String> current = new java.util.HashSet<>(attachment.getPermissions().keySet());
        // 需要新增
        for (String node : shouldHave) {
            if (!current.contains(node)) attachment.setPermission(node, true);
        }
        // 需要移除
        for (String node : current) {
            if (!shouldHave.contains(node)) attachment.unsetPermission(node);
        }
        player.recalculatePermissions();
    }

    private boolean conflictsWithSelected(String candidateKey, java.util.Set<String> selectedKeys) {
        org.cubexmc.model.GemDefinition c = findGemDefinition(candidateKey);
        java.util.Set<String> cm = new java.util.HashSet<>();
        if (c != null && c.getMutualExclusive() != null) {
            for (String x : c.getMutualExclusive()) if (x != null) cm.add(x.toLowerCase(java.util.Locale.ROOT));
        }
        for (String s : selectedKeys) {
            if (cm.contains(s)) return true;
            org.cubexmc.model.GemDefinition sd = findGemDefinition(s);
            if (sd != null && sd.getMutualExclusive() != null) {
                for (String x : sd.getMutualExclusive()) {
                    if (x != null && x.equalsIgnoreCase(candidateKey)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 启动后根据已加载的 locationToGemUuid，在世界中恢复方块显示。
     * 不进行数量限制或位置调整，仅按定义材质设置方块类型。
     */
    public void initializePlacedGemBlocks() {
        for (Map.Entry<Location, UUID> e : locationToGemUuid.entrySet()) {
            Location loc = e.getKey();
            UUID gemId = e.getValue();
            World w = loc.getWorld();
            if (w == null) continue;
            try {
                String key = gemUuidToKey.get(gemId);
                Material mat = Material.RED_STAINED_GLASS;
                if (key != null) {
                    org.cubexmc.model.GemDefinition def = findGemDefinition(key);
                    if (def != null && def.getMaterial() != null) mat = def.getMaterial();
                }
                final Material m = mat;
                final Location f = loc;
                SchedulerUtil.regionRun(plugin, f, () -> {
                    try {
                        if (!f.getChunk().isLoaded()) {
                            f.getChunk().load();
                        }
                        f.getBlock().setType(m);
                    } catch (Exception ignored2) {}
                }, 0L, -1L);
            } catch (Exception ignored) {}
        }
    }

    private void revokeNodes(Player player, java.util.Collection<String> nodes) {
        org.bukkit.permissions.PermissionAttachment i = invAttachments.get(player.getUniqueId());
        if (i != null) {
            for (String n : nodes) {
                try { i.unsetPermission(n); } catch (Exception ignored) {}
            }
        }
        org.bukkit.permissions.PermissionAttachment r = redeemAttachments.get(player.getUniqueId());
        if (r != null) {
            for (String n : nodes) {
                try { r.unsetPermission(n); } catch (Exception ignored) {}
            }
        }
        // 同步撤销权限后端中的永久节点（若存在）
        if (plugin.getVaultPerms() != null) {
            for (String n : nodes) {
                try { plugin.getVaultPerms().playerRemove(player, n); } catch (Exception ignored) {}
            }
        }
    }

    private void applyPendingRevokesIfAny(Player player) {
        if (player == null) return;
        java.util.UUID uid = player.getUniqueId();
        boolean changed = false;
        java.util.Set<String> perms = pendingPermRevokes.remove(uid);
        if (perms != null && !perms.isEmpty()) {
            revokeNodes(player, perms);
            changed = true;
        }
        java.util.Set<String> groups = pendingGroupRevokes.remove(uid);
        if (groups != null && !groups.isEmpty() && plugin.getVaultPerms() != null) {
            for (String g : groups) {
                try { plugin.getVaultPerms().playerRemoveGroup(player, g); } catch (Exception ignored) {}
            }
            changed = true;
        }
        if (changed) {
            try { player.recalculatePermissions(); } catch (Throwable ignored) {}
            saveGems();
        }
    }

    /**
     * 获取宝石总数（放置 + 持有）
     */
    public int getTotalGemCount() {
        return locationToGemUuid.size() + gemUuidToHolder.size();
    }

    /**
     * 示例：在某处注册一个方法，当玩家放置方块时，如果它是"自定义方块"，就记录坐标
     */
    public void placeRuleGem(Location loc, UUID gemId) {
        placeRuleGemInternal(loc, gemId, false);
    }

    private void placeRuleGemInternal(Location loc, UUID gemId, boolean ignoreLimit) {
        if (loc == null) return;
        if (!ignoreLimit && getTotalGemCount() >= configManager.getRequiredCount()) {
            languageManager.logMessage("gem_limit_reached");
            return;
        }
        // 在目标区域线程执行所有方块操作，确保 Folia 兼容
        final Location base = loc.clone();
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null) return;
            WorldBorder border = world.getWorldBorder();
            Location target = base.getBlock().getLocation();
            // 垂直向上寻找空气（最多尝试 6 格），避免方块被覆盖
            int tries = 0;
            while (tries < 6 && target.getBlock().getType().isSolid()) {
                target.add(0, 1, 0);
                tries++;
            }
            // 基础安全性校验
            if (!border.isInside(target) || target.getBlockY() < world.getMinHeight() || target.getBlockY() > world.getMaxHeight()) {
                // 回退到随机散落（异步调度，每次在候选坐标区域线程执行）
                randomPlaceGem(gemId);
                return;
            }
            // 设置材质并登记
            String gemKey = gemUuidToKey.getOrDefault(gemId, null);
            Material mat = Material.RED_STAINED_GLASS;
            if (gemKey != null) {
                org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
                if (def != null && def.getMaterial() != null) {
                    mat = def.getMaterial();
                }
            }
            target.getBlock().setType(mat);
            locationToGemUuid.put(target, gemId);
            gemUuidToLocation.put(gemId, target);
            // 调度宝石逃逸
            scheduleEscape(gemId);
        }, 0L, -1L);
    }

    /**
     * 如果方块被破坏了，也要移除坐标
     */
    public void unplaceRuleGem(Location loc, UUID gemId) {
        if (loc == null) return;
        final Location fLoc = loc.getBlock().getLocation();
        SchedulerUtil.regionRun(plugin, fLoc, () -> {
            fLoc.getBlock().setType(Material.AIR);
            locationToGemUuid.remove(fLoc, gemId);
            gemUuidToLocation.remove(gemId);
        }, 0L, -1L);
    }

    /**
     * 在指定范围内随机放置一个宝石方块
     */
    /**
     * 获取宝石的随机生成范围（优先使用宝石特定的范围，否则使用全局默认）
     * @return [corner1, corner2] 或 null（如果两者都无效）
     */
    private Location[] getGemPlaceRange(UUID gemId) {
        String gemKey = gemUuidToKey.get(gemId);
        if (gemKey != null) {
            // 查找宝石定义
            for (org.cubexmc.model.GemDefinition def : configManager.getGemDefinitions()) {
                if (def.getGemKey().equals(gemKey)) {
                    Location c1 = def.getRandomPlaceCorner1();
                    Location c2 = def.getRandomPlaceCorner2();
                    // 如果宝石有自己的生成范围配置，优先使用
                    if (c1 != null && c2 != null) {
                        return new Location[]{c1, c2};
                    }
                    break;
                }
            }
        }
        // 回退到全局默认范围
        Location defaultC1 = configManager.getRandomPlaceCorner1();
        Location defaultC2 = configManager.getRandomPlaceCorner2();
        if (defaultC1 != null && defaultC2 != null) {
            return new Location[]{defaultC1, defaultC2};
        }
        return null;
    }

    /**
     * 随机放置指定数量的宝石。
     */
    private void randomPlaceGem(UUID gemId, Location corner1, Location corner2) {
        ensureGemKeyAssigned(gemId);
        scheduleRandomAttempt(gemId, corner1, corner2, 12);
    }
    
    /**
     * 随机放置宝石（自动使用宝石特定或全局默认范围）
     */
    private void randomPlaceGem(UUID gemId) {
        Location[] range = getGemPlaceRange(gemId);
        if (range != null) {
            randomPlaceGem(gemId, range[0], range[1]);
        } else {
            // 回退：使用主世界出生点作为放置位置
            plugin.getLogger().warning("无法放置宝石 " + gemId + "：未配置生成范围，尝试使用主世界出生点");
            World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (defaultWorld != null) {
                Location spawnLoc = defaultWorld.getSpawnLocation();
                placeRuleGem(spawnLoc, gemId);
            } else {
                plugin.getLogger().severe("无法放置宝石 " + gemId + "：没有可用的世界！宝石将处于 unknown 状态");
            }
        }
    }

    // 在随机范围内尝试放置（Folia 安全）：每次选择一个候选 (x,z)，在该坐标区域线程中计算最高地面并放置
    private void scheduleRandomAttempt(UUID gemId, Location corner1, Location corner2, int attemptsLeft) {
        if (corner1 == null || corner2 == null) return;
        if (corner1.getWorld() != corner2.getWorld()) return;
        
        // 最后一次尝试失败后，回退到范围中心点
        if (attemptsLeft <= 0) {
            plugin.getLogger().warning("宝石 " + gemId + " 随机放置失败，回退到范围中心点");
            int centerX = (corner1.getBlockX() + corner2.getBlockX()) / 2;
            int centerZ = (corner1.getBlockZ() + corner2.getBlockZ()) / 2;
            World world = corner1.getWorld();
            int y = world.getHighestBlockYAt(centerX, centerZ) + 1;
            Location fallback = new Location(world, centerX, y, centerZ);
            placeRuleGem(fallback, gemId);
            return;
        }
        
        World world = corner1.getWorld();
        Random rand = new Random();
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
        int x = rand.nextInt(maxX - minX + 1) + minX;
        int z = rand.nextInt(maxZ - minZ + 1) + minZ;
        // 以世界最小高度作为占位 y，实际 y 在区域线程中计算
        final Location candidate = new Location(world, x, world.getMinHeight() + 1, z);
        SchedulerUtil.regionRun(plugin, candidate, () -> {
            try {
                int y = world.getHighestBlockYAt(x, z) + 1;
                Location place = new Location(world, x, y, z);
                WorldBorder border = world.getWorldBorder();
                if (!border.isInside(place)) {
                    scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
                    return;
                }
                // 最终放置
                placeRuleGem(place, gemId);
            } catch (Throwable t) {
                // 若计算失败，继续下一次尝试
                scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
            }
        }, 0L, -1L);
    }

    /**
     * 检查指定位置是否适合放置宝石。
     *
     * @param loc 需要检查的位置
     * @return 如果适合返回 true，否则返回 false
     */
    // 删除本地同步的安全性检测方法，安全检查在区域线程中处理

    /**
     * 找一个位置附近的安全位置。
     * @param startLoc 起始位置
     * @return 安全的位置，若未找到则返回 null
     */
    // 删除 Folia 不安全的寻找函数，改用 scheduleRandomAttempt

    public void startParticleEffectTask(Particle particle) {
        // Deprecated single-particle task retained for backward compatibility; now use per-gem particles
        SchedulerUtil.globalRun(plugin, () -> {
            for (Location loc : locationToGemUuid.keySet()) {
                Location target = loc;
                SchedulerUtil.regionRun(plugin, target, () -> {
                    World world = target.getWorld();
                    if (world == null) return;
                    // Per-gem particle
                    UUID id = locationToGemUuid.get(target);
                    org.cubexmc.model.GemDefinition def = id != null ? findGemDefinition(gemUuidToKey.get(id)) : null;
                    org.bukkit.Particle p = def != null && def.getParticle() != null ? def.getParticle() : particle;
                    world.spawnParticle(p, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 1);
                }, 0L, -1L);
            }
        }, 0L, 20L);
    }

    // ==================== 宝石逃逸机制 ====================

    /**
     * 为宝石调度逃逸任务（在随机时间后自动移动到新位置）
     */
    public void scheduleEscape(UUID gemId) {
        if (!configManager.isGemEscapeEnabled()) return;
        if (gemId == null) return;
        
        // 取消旧任务
        cancelEscape(gemId);
        
        // 计算随机延迟（已经是 tick 单位）
        long minTicks = configManager.getGemEscapeMinIntervalTicks();
        long maxTicks = configManager.getGemEscapeMaxIntervalTicks();
        Random rand = new Random();
        long range = Math.max(1L, maxTicks - minTicks);
        long delayTicks = minTicks + (long) (rand.nextDouble() * range);
        
        // 调度逃逸任务
        Object task = SchedulerUtil.globalRun(plugin, () -> triggerEscape(gemId), delayTicks, -1L);
        if (task != null) {
            gemEscapeTasks.put(gemId, task);
        }
    }

    /**
     * 取消宝石的逃逸任务
     */
    public void cancelEscape(UUID gemId) {
        if (gemId == null) return;
        Object task = gemEscapeTasks.remove(gemId);
        if (task != null) {
            SchedulerUtil.cancelTask(task);
        }
    }

    /**
     * 取消所有宝石的逃逸任务
     */
    public void cancelAllEscapeTasks() {
        for (Object task : gemEscapeTasks.values()) {
            if (task != null) {
                SchedulerUtil.cancelTask(task);
            }
        }
        gemEscapeTasks.clear();
    }

    /**
     * 为所有已放置的宝石初始化逃逸任务
     */
    public void initializeEscapeTasks() {
        if (!configManager.isGemEscapeEnabled()) return;
        for (UUID gemId : gemUuidToLocation.keySet()) {
            scheduleEscape(gemId);
        }
    }

    /**
     * 触发宝石逃逸：移动到新的随机位置
     */
    private void triggerEscape(UUID gemId) {
        if (gemId == null) return;
        
        // 移除任务记录
        gemEscapeTasks.remove(gemId);
        
        // 获取当前位置
        Location oldLocation = gemUuidToLocation.get(gemId);
        if (oldLocation == null) {
            // 宝石不在放置状态（可能被拾取了），不处理
            return;
        }
        
        // 播放逃逸特效（在旧位置）
        playEscapeEffects(oldLocation, gemId);
        
        // 移除旧位置
        unplaceRuleGem(oldLocation, gemId);
        
        // 随机放置到新位置
        randomPlaceGem(gemId);
        
        // 广播消息
        if (configManager.isGemEscapeBroadcast()) {
            broadcastEscape(gemId);
        }
        
        // 重新调度下一次逃逸（会在 placeRuleGemInternal 中调用）
    }

    /**
     * 播放宝石逃逸特效
     */
    private void playEscapeEffects(Location location, UUID gemId) {
        if (location == null || location.getWorld() == null) return;
        
        final Location loc = location.clone().add(0.5, 0.5, 0.5);
        SchedulerUtil.regionRun(plugin, loc, () -> {
            World world = loc.getWorld();
            if (world == null) return;
            
            // 播放粒子
            String particleStr = configManager.getGemEscapeParticle();
            if (particleStr != null && !particleStr.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleStr.toUpperCase());
                    world.spawnParticle(particle, loc, 50, 0.5, 0.5, 0.5, 0.1);
                } catch (IllegalArgumentException ignored) {}
            }
            
            // 播放音效
            String soundStr = configManager.getGemEscapeSound();
            if (soundStr != null && !soundStr.isEmpty()) {
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundStr.toUpperCase());
                    world.playSound(loc, sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {}
            }
        }, 0L, -1L);
    }

    /**
     * 广播宝石逃逸消息
     */
    private void broadcastEscape(UUID gemId) {
        String gemKey = gemUuidToKey.getOrDefault(gemId, "unknown");
        org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
        String gemName = def != null ? def.getDisplayName() : gemKey;
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_name", gemName);
        placeholders.put("gem_key", gemKey);
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            languageManager.sendMessage(player, "gem_escape.broadcast", placeholders);
        }
        languageManager.logMessage("gem_escape", placeholders);
    }

    // ==================== 放置兑换（祭坛模式）====================

    /**
     * 查找宝石定义（根据 key），暴露给外部使用
     */
    public org.cubexmc.model.GemDefinition findGemDefinitionByKey(String gemKey) {
        return findGemDefinition(gemKey);
    }

    /**
     * 设置宝石的祭坛位置
     */
    public void setGemAltarLocation(String gemKey, Location location) {
        org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
        if (def != null) {
            def.setAltarLocation(location);
            // 保存到配置文件
            saveGemAltarToConfig(gemKey, location);
        }
    }

    /**
     * 移除宝石的祭坛位置
     */
    public void removeGemAltarLocation(String gemKey) {
        org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
        if (def != null) {
            def.setAltarLocation(null);
            // 从配置文件移除
            removeGemAltarFromConfig(gemKey);
        }
    }

    /**
     * 保存祭坛位置到宝石配置文件
     */
    private void saveGemAltarToConfig(String gemKey, Location loc) {
        // 遍历 gems 文件夹找到包含此 gemKey 的配置文件
        java.io.File gemsFolder = new java.io.File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) return;

        for (java.io.File file : gemsFolder.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                org.bukkit.configuration.file.YamlConfiguration yaml = 
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                if (yaml.contains(gemKey)) {
                    yaml.set(gemKey + ".altar.world", loc.getWorld().getName());
                    yaml.set(gemKey + ".altar.x", loc.getBlockX());
                    yaml.set(gemKey + ".altar.y", loc.getBlockY());
                    yaml.set(gemKey + ".altar.z", loc.getBlockZ());
                    try {
                        yaml.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("保存祭坛配置失败: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    /**
     * 从宝石配置文件移除祭坛位置
     */
    private void removeGemAltarFromConfig(String gemKey) {
        java.io.File gemsFolder = new java.io.File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) return;

        for (java.io.File file : gemsFolder.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                org.bukkit.configuration.file.YamlConfiguration yaml = 
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                if (yaml.contains(gemKey)) {
                    yaml.set(gemKey + ".altar", null);
                    try {
                        yaml.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("移除祭坛配置失败: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    /**
     * 检查位置是否在某颗宝石的祭坛范围内
     * @return 匹配的 GemDefinition，或 null
     */
    private org.cubexmc.model.GemDefinition findMatchingAltarGem(Location loc, String gemKey) {
        if (!configManager.isPlaceRedeemEnabled()) return null;
        if (loc == null || gemKey == null) return null;

        org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
        if (def == null) return null;

        Location altar = def.getAltarLocation();
        if (altar == null) return null;
        if (altar.getWorld() == null || loc.getWorld() == null) return null;
        if (!altar.getWorld().equals(loc.getWorld())) return null;

        int radius = configManager.getPlaceRedeemRadius();
        double distance = altar.distance(loc);
        if (distance <= radius) {
            return def;
        }
        return null;
    }

    /**
     * 处理放置兑换逻辑
     */
    private void handlePlaceRedeem(Player player, UUID gemId, Location placedLoc, Block block, org.cubexmc.model.GemDefinition def) {
        if (player == null || gemId == null || def == null) return;

        String targetKey = def.getGemKey();
        String gemName = def.getDisplayName();

        // 播放祭坛兑换特效（包括信标光束）
        playPlaceRedeemEffects(placedLoc);

        // 标记已兑换并发放奖励（与 redeemGemInHand 相同的逻辑）
        markGemRedeemed(player, targetKey);
        applyRedeemRewards(player, def);

        // 单颗竞争撤销逻辑
        String normalizedKey = targetKey.toLowerCase(Locale.ROOT);
        UUID old = gemIdToRedeemer.put(gemId, player.getUniqueId());
        String previousOwnerName = null;
        if (old != null && !old.equals(player.getUniqueId())) {
            decrementOwnerKeyCount(old, normalizedKey, def);
            Player oldP = Bukkit.getPlayer(old);
            if (oldP != null && oldP.isOnline()) {
                previousOwnerName = oldP.getName();
            }
        }
        incrementOwnerKeyCount(player.getUniqueId(), normalizedKey, def);
        reassignRedeemInstanceAllowance(gemId, player.getUniqueId(), def, true);

        // 记录兑换事件
        if (historyLogger != null) {
            historyLogger.logGemRedeem(
                player,
                targetKey,
                gemName,
                def.getPermissions() != null ? def.getPermissions() : Collections.emptyList(),
                def.getVaultGroup(),
                previousOwnerName
            );
        }

        // 发送成功消息
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_name", gemName);
        placeholders.put("gem_key", targetKey);
        placeholders.put("player", player.getName());
        languageManager.sendMessage(player, "place_redeem.success", placeholders);

        // 广播消息
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                languageManager.sendMessage(online, "place_redeem.broadcast", placeholders);
            }
        }

        // 宝石消失并重新随机放置（与 redeem 命令相同）
        gemUuidToHolder.remove(gemId);
        SchedulerUtil.regionRun(plugin, placedLoc, () -> {
            block.setType(Material.AIR);
        }, 1L, -1L);
        randomPlaceGem(gemId);

        // 清理玩家背包中可能残留的宝石物品（创造模式）
        SchedulerUtil.entityRun(plugin, player, () -> {
            removeGemItemFromInventory(player, gemId);
            try { player.updateInventory(); } catch (Throwable ignored) {}
        }, 1L, -1L);
    }

    /**
     * 播放放置兑换特效（包括信标光束）
     */
    private void playPlaceRedeemEffects(Location location) {
        if (location == null || location.getWorld() == null) return;

        final Location loc = location.clone().add(0.5, 0.5, 0.5);
        SchedulerUtil.regionRun(plugin, loc, () -> {
            World world = loc.getWorld();
            if (world == null) return;

            // 播放粒子
            String particleStr = configManager.getPlaceRedeemParticle();
            if (particleStr != null && !particleStr.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleStr.toUpperCase());
                    world.spawnParticle(particle, loc, 100, 1.0, 1.0, 1.0, 0.1);
                } catch (IllegalArgumentException ignored) {}
            }

            // 播放音效
            String soundStr = configManager.getPlaceRedeemSound();
            if (soundStr != null && !soundStr.isEmpty()) {
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundStr.toUpperCase());
                    world.playSound(loc, sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {}
            }

            // 播放信标光束特效
            if (configManager.isPlaceRedeemBeaconBeam()) {
                playBeaconBeamEffect(loc, configManager.getPlaceRedeemBeaconDuration());
            }
        }, 0L, -1L);
    }

    /**
     * 播放信标光束特效
     * 通过临时放置信标和玻璃来创建光束效果
     */
    private void playBeaconBeamEffect(Location loc, int durationSeconds) {
        if (loc == null || loc.getWorld() == null) return;

        World world = loc.getWorld();
        Location beaconLoc = loc.clone();
        beaconLoc.setY(world.getMinHeight()); // 放在最底层

        // 使用末地水晶光束代替（更简单且不需要修改方块）
        // 或者使用 END_ROD 粒子模拟光束
        final int height = loc.getBlockY() - world.getMinHeight();
        final long durationTicks = durationSeconds * 20L;
        final int interval = 2; // 每2tick刷新一次

        // 创建向上的粒子光束
        final Object[] taskHolder = new Object[1];
        taskHolder[0] = SchedulerUtil.globalRun(plugin, () -> {
            if (world == null) {
                if (taskHolder[0] != null) SchedulerUtil.cancelTask(taskHolder[0]);
                return;
            }
            // 从底部到宝石位置生成 END_ROD 粒子
            for (int y = 0; y < height; y += 3) {
                Location particleLoc = loc.clone();
                particleLoc.setY(world.getMinHeight() + y);
                world.spawnParticle(Particle.END_ROD, particleLoc, 2, 0.1, 0, 0.1, 0.01);
            }
            // 在宝石位置生成爆发粒子
            world.spawnParticle(Particle.TOTEM, loc, 5, 0.5, 0.5, 0.5, 0.1);
        }, 0L, interval);

        // 在持续时间后取消任务
        SchedulerUtil.globalRun(plugin, () -> {
            if (taskHolder[0] != null) {
                SchedulerUtil.cancelTask(taskHolder[0]);
            }
        }, durationTicks, -1L);
    }

    /**
     * 实际的检测逻辑
     */
    public void checkPlayersNearRuleGems() {
        if (locationToGemUuid.isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayerNearRuleGems(player);
        }
    }

    /**
     * 检查某个玩家附近是否有宝石，并播放提示音。
     */
    public void checkPlayerNearRuleGems(Player player) {
        if (player == null || locationToGemUuid.isEmpty()) return;
        // 统一通过 SchedulerUtil 派发，内部已处理 Folia 与主线程
        SchedulerUtil.entityRun(plugin, player, () -> doPlayerNearCheck(player), 0L, -1L);
    }

    private void doPlayerNearCheck(Player player) {
        if (player == null) return;
        Location playerLoc = player.getLocation();
        World playerWorld = playerLoc.getWorld();
        if (playerWorld == null) return;
        for (Location blockLoc : locationToGemUuid.keySet()) {
            World w = blockLoc.getWorld();
            if (w == null || !w.equals(playerWorld)) continue;
            double distance = playerLoc.distance(blockLoc);
            if (distance < 16.0) {
                float volume = (float) (1.0 - (distance / 16.0));
                float pitch = 1.0f;
                player.playSound(playerLoc, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, volume, pitch);
            }
        }
    }

    /**
     * 判断当前所有的"特殊方块"是否都在 (0,60,0) 为中心、半径5 的**立方体**范围内
     */
    // 玩法已调整：相关方法移除，避免误用

    /**
     * 判断指定集合中的方块是否连成一个整体（面邻接，6个方向）。
     * @param locations 存放所有方块坐标的集合
     * @return 若所有方块连通则返回 true，否则 false
     */
    // 玩法调整后不再需要该方法

    /**
     * 从起始坐标 start 对集合 locations 做 BFS，返回能访问到的方块数量
     */
    // 玩法调整后不再需要该方法

    /**
     * 获取某方块坐标在 6 个方向上的邻居
     */
    // 玩法调整后不再需要该方法

    private Location findLocationByGemId(UUID gemId) {
        return gemUuidToLocation.get(gemId); // O(1) 查找
    }

    /**
     * 公共获取：根据宝石 UUID 返回其放置坐标（若未放置则为 null）。
     */
    public Location getGemLocation(UUID gemId) {
        return findLocationByGemId(gemId);
    }

    /**
     * 公共获取：根据宝石 UUID 返回其当前持有人（若无人持有则为 null）。
     */
    public Player getGemHolder(UUID gemId) {
        return gemUuidToHolder.get(gemId);
    }

    /**
     * 公共获取：根据宝石 UUID 返回其 gemKey
     */
    public String getGemKey(UUID gemId) {
        return gemUuidToKey.get(gemId);
    }

    /**
     * 判断某个坐标是否在以 center 为中心、边长=半径*2+1 的立方体内
     * 这里用"绝对值 <= 半径"来判断
     */
    // 玩法调整后不再需要该方法

    /**
     * 强制撤销指定玩家的所有宝石权限、组和限次指令
     * 用于管理员干预玩家滥用权限的情况
     * 
     * @param player 目标玩家
     * @return 是否成功撤销（true=该玩家有权限被撤销，false=该玩家没有任何宝石权限）
     */
    public boolean revokeAllPlayerPermissions(Player player) {
        if (player == null) return false;
        java.util.UUID uid = player.getUniqueId();
        boolean hadAny = false;
        
        // 1. 收集该玩家拥有的所有宝石类型
        java.util.Map<String, Integer> counts = ownerKeyCount.get(uid);
        if (counts != null && !counts.isEmpty()) {
            hadAny = true;
            
            // 撤销每个类型的权限与组
            for (Map.Entry<String, Integer> e : new java.util.HashMap<>(counts).entrySet()) {
                String key = e.getKey();
                org.cubexmc.model.GemDefinition def = findGemDefinition(key);
                if (def != null) {
                    if (def.getPermissions() != null) {
                        revokeNodes(player, def.getPermissions());
                    }
                    if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                        try { plugin.getVaultPerms().playerRemoveGroup(player, def.getVaultGroup()); } catch (Exception ignored) {}
                    }
                }
            }
            // 清空该玩家的归属计数
            counts.clear();
        }
        
        // 2. 如果该玩家是 full set owner，撤销额外权限
        if (uid.equals(fullSetOwner)) {
            hadAny = true;
            java.util.List<String> extraPerms = configManager.getRedeemAllPermissions();
            if (extraPerms != null && !extraPerms.isEmpty()) {
                revokeNodes(player, extraPerms);
            }
            fullSetOwner = null;
        }
        
        // 3. 清空该玩家的所有限次指令额度
        playerGemHeldUses.remove(uid);
        playerGemRedeemUses.remove(uid);
        playerGlobalAllowedUses.remove(uid);
        
        // 4. 清空该玩家的兑换记录
        playerUuidToRedeemedKeys.remove(uid);
        playerActiveHeldKeys.remove(uid);
        
        // 清空 gemIdToRedeemer 中该玩家的所有兑换记录（这是 rulers 命令读取的数据源）
        gemIdToRedeemer.entrySet().removeIf(entry -> uid.equals(entry.getValue()));
        
        // 5. 清空该玩家的权限附件
        org.bukkit.permissions.PermissionAttachment invAtt = invAttachments.remove(uid);
        if (invAtt != null) {
            try { player.removeAttachment(invAtt); } catch (Throwable ignored) {}
        }
        org.bukkit.permissions.PermissionAttachment redAtt = redeemAttachments.remove(uid);
        if (redAtt != null) {
            try { player.removeAttachment(redAtt); } catch (Throwable ignored) {}
        }
        
        // 6. 重算权限
        try { player.recalculatePermissions(); } catch (Throwable ignored) {}
        
        // 7. 记录日志
        if (hadAny && historyLogger != null) {
            historyLogger.logPermissionRevoke(
                uid.toString(),
                player.getName(),
                "ALL",
                "全部宝石权限",
                java.util.Collections.emptyList(),
                null,
                "管理员强制撤销"
            );
        }
        
        // 8. 持久化
        saveGems();
        
        return hadAny;
    }

    /**
     * 如果物品是宝石，则返回它的 UUID；如果不是宝石，则返回 null。
     */
    public UUID getGemUUID(ItemStack item) {
        if (!isRuleGem(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuidStr = pdc.get(uniqueIdKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;

        return UUID.fromString(uuidStr);
    }

    /**
     * 如果方块是宝石，则返回它的 UUID；如果不是宝石，则返回 null。
     */
    public UUID getGemUUID(Block block) {
        if (!isRuleGem(block)) {
            return null;
        }

        Location loc = block.getLocation();
        return locationToGemUuid.get(loc);
    }

    /**
     * 仅通过玩家主手持有的宝石进行兑换。
     * 不需要输入 key，直接识别主手物品。
     */
    public boolean redeemGemInHand(Player player) {
        if (player == null) return false;
        // 缓存玩家名称（用于离线时显示）
        cachePlayerName(player);
        if (!configManager.isRedeemEnabled()) {
            languageManager.sendMessage(player, "command.redeem.disabled");
            return true;
        }
        org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!isRuleGem(inHand)) {
            return false;
        }
        java.util.UUID matchedGemId = getGemUUID(inHand);
        if (matchedGemId == null) return false;
        // 确定 gem key
        String targetKey = gemUuidToKey.get(matchedGemId);
        if (targetKey == null || targetKey.isEmpty()) {
            // 兜底：如果缺失，尽量分配一个定义
            ensureGemKeyAssigned(matchedGemId);
            targetKey = gemUuidToKey.get(matchedGemId);
            if (targetKey == null || targetKey.isEmpty()) return false;
        }
        // 标记已兑换并发放奖励
        markGemRedeemed(player, targetKey);
        org.cubexmc.model.GemDefinition def = findGemDefinition(targetKey);
        applyRedeemRewards(player, def);

        // 从玩家移除物品并重新散落
        removeGemItemFromInventory(player, matchedGemId);
        gemUuidToHolder.remove(matchedGemId);
        randomPlaceGem(matchedGemId);

        // 单颗竞争撤销（按 gemId）：新兑换者成功后切换此 gemId 的归属，并基于计数决定是否撤销旧持有者的该类型权限与额度
        String normalizedKey = targetKey.toLowerCase(java.util.Locale.ROOT);
        java.util.UUID old = gemIdToRedeemer.put(matchedGemId, player.getUniqueId());
        String previousOwnerName = null;
        if (old != null && !old.equals(player.getUniqueId())) {
            // 旧持有者该 key 计数 -1
            decrementOwnerKeyCount(old, normalizedKey, def);
            Player oldP = Bukkit.getPlayer(old);
            if (oldP != null && oldP.isOnline()) {
                previousOwnerName = oldP.getName();
            }
        }
        // 新持有者该 key 计数 +1，并在 0->1 时发放权限与额度
        incrementOwnerKeyCount(player.getUniqueId(), normalizedKey, def);
        // 实例级额度：兑换后 gemId 的实例额度归属切到兑换者；同一人重复兑换要求重置该 gemId 的额度
        reassignRedeemInstanceAllowance(matchedGemId, player.getUniqueId(), def, /*resetEvenIfSameOwner*/ true);

        // 记录宝石兑换事件
        if (historyLogger != null) {
            historyLogger.logGemRedeem(
                player,
                targetKey,
                def != null ? def.getDisplayName() : null,
                def != null ? def.getPermissions() : null,
                def != null ? def.getVaultGroup() : null,
                previousOwnerName
            );
        }

        // 广播标题（可配置开关）
        if (configManager.isBroadcastRedeemTitle()) {
            java.util.Map<String, String> ph = new java.util.HashMap<>();
            ph.put("player", player.getName());
            ph.put("gem", def != null ? (def.getDisplayName() != null ? def.getDisplayName() : targetKey) : targetKey);
            java.util.List<String> title = def != null ? def.getRedeemTitle() : null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (title != null && !title.isEmpty()) {
                    if (title.size() == 1) {
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', languageManager.formatText(title.get(0), java.util.Collections.singletonMap("player", player.getName()))), null, 10, 70, 20);
                    } else {
                        String l1 = languageManager.formatText(title.get(0), ph);
                        String l2 = languageManager.formatText(title.get(1), ph);
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', l1), org.bukkit.ChatColor.translateAlternateColorCodes('&', l2), 10, 70, 20);
                    }
                } else {
                    // fallback to language title if present
                    languageManager.showTitle(p, "gems_scattered", java.util.Collections.singletonMap("count", String.valueOf(1)));
                }
            }
        }
        return true;
    }

    // 已弃用：旧的按名称或 key 兑换方式已移除，改为仅支持主手兑换。

    /**
     * 从玩家背包（含副手）精确移除一颗指定 UUID 的宝石物品。
     */
    private void removeGemItemFromInventory(Player player, UUID targetId) {
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

    private void markGemRedeemed(Player player, String gemKey) {
        if (player == null || gemKey == null || gemKey.isEmpty()) {
            return;
        }
        String normalizedKey = gemKey.toLowerCase(Locale.ROOT);
        playerUuidToRedeemedKeys.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>()).add(normalizedKey);
    }

    private void applyRedeemRewards(Player player, org.cubexmc.model.GemDefinition definition) {
        if (player == null || definition == null) {
            return;
        }
        ExecuteConfig onRedeem = definition.getOnRedeem();
        if (onRedeem != null) {
            Map<String, String> placeholders = java.util.Map.of("%player%", player.getName());
            effectUtils.executeCommands(onRedeem, placeholders);
            effectUtils.playLocalSound(player.getLocation(), onRedeem, 1.0f, 1.0f);
            effectUtils.playParticle(player.getLocation(), onRedeem);
        }
        // 权限与限次额度的发放改由归属计数 0->1 时处理
        if (definition.getVaultGroup() != null && !definition.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
            try {
                plugin.getVaultPerms().playerAddGroup(player, definition.getVaultGroup());
            } catch (Exception ignored) {}
        }
    }

    private void grantAllowedCommands(Player player, org.cubexmc.model.GemDefinition def) {
        if (player == null || def == null) return;
        java.util.List<org.cubexmc.model.AllowedCommand> allows = def.getAllowedCommands();
        if (allows == null || allows.isEmpty()) return;
        // 写入全局额度（例如 redeem_all 额外额度）
        java.util.UUID uid = player.getUniqueId();
        java.util.Map<String, Integer> global = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new java.util.HashMap<>());
        for (org.cubexmc.model.AllowedCommand ac : allows) {
            if (ac == null || ac.getLabel() == null) continue;
            global.put(ac.getLabel().toLowerCase(java.util.Locale.ROOT), ac.getUses());
        }
        saveGems();
    }

    // removed: per-instance 初始化在具体 gemId 切换归属/持有时进行

    private void revokeAllowedCommands(java.util.UUID oldOwner, org.cubexmc.model.GemDefinition def) {
        if (oldOwner == null || def == null) return;
        // 已由 per-instance 存储管理（见上）
        saveGems();
    }

    public boolean hasAnyAllowed(java.util.UUID uid, String label) {
        if (uid == null || label == null) return false;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        // Global allowances (e.g., redeem_all)
        java.util.Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v != null && (v > 0 || v < 0)) return true;
        }
        // Per-instance allowances (held + redeemed)
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (java.util.Map<String, Integer> byLabel : perHeld.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0)) return true;
            }
        }
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (java.util.Map<String, Integer> byLabel : perRed.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0)) return true;
            }
        }
        return false;
    }

    public boolean tryConsumeAllowed(java.util.UUID uid, String label) {
        if (uid == null || label == null) return false;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        // 先尝试持有实例（稳定顺序）
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null && !perHeld.isEmpty()) {
            java.util.List<java.util.UUID> ids = new java.util.ArrayList<>(perHeld.keySet());
            ids.sort(java.util.UUID::compareTo);
            for (java.util.UUID gid : ids) {
                java.util.Map<String, Integer> byLabel = perHeld.get(gid);
                if (byLabel == null) continue;
                Integer v = byLabel.get(l);
                if (v == null) v = 0;
                if (v < 0) { saveGems(); return true; }
                if (v > 0) { byLabel.put(l, v - 1); saveGems(); return true; }
            }
        }
        // 再尝试已兑换实例
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null && !perRed.isEmpty()) {
            java.util.List<java.util.UUID> ids = new java.util.ArrayList<>(perRed.keySet());
            ids.sort(java.util.UUID::compareTo);
            for (java.util.UUID gid : ids) {
                java.util.Map<String, Integer> byLabel = perRed.get(gid);
                if (byLabel == null) continue;
                Integer v = byLabel.get(l);
                if (v == null) v = 0;
                if (v < 0) { saveGems(); return true; }
                if (v > 0) { byLabel.put(l, v - 1); saveGems(); return true; }
            }
        }
        // 最后尝试全局
        java.util.Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v == null) v = 0;
            if (v < 0) { saveGems(); return true; }
            if (v > 0) { glob.put(l, v - 1); saveGems(); return true; }
        }
        return false;
    }

    // removed old helper: clearAllowedForGemKeyExcept

    private void handleInventoryOwnershipOnPickup(Player player, java.util.UUID gemId) {
        if (player == null || gemId == null) return;
        if (!configManager.isInventoryGrantsEnabled()) return;
        String gemKey = gemUuidToKey.get(gemId);
        if (gemKey == null) return;
        org.cubexmc.model.GemDefinition def = findGemDefinition(gemKey);
        if (def == null) return;
        // 实例级额度：将该 gemId 的"最近持有者额度"转交给当前玩家；同人反复持有不重置
        reassignHeldInstanceAllowance(gemId, player.getUniqueId(), def);
        // 维护类型计数（权限与组按 0->1 / 1->0 管理）
        java.util.UUID old = gemIdToRedeemer.put(gemId, player.getUniqueId());
        String key = gemKey.toLowerCase(java.util.Locale.ROOT);
        if (old != null && !old.equals(player.getUniqueId())) {
            decrementOwnerKeyCount(old, key, def);
        }
        incrementOwnerKeyCount(player.getUniqueId(), key, def);
    }

    private void reassignHeldInstanceAllowance(java.util.UUID gemId,
                                                java.util.UUID newOwner,
                                                org.cubexmc.model.GemDefinition def) {
        if (gemId == null || newOwner == null || def == null) return;
        // Find old owner in held map
        java.util.UUID oldOwner = null;
        for (java.util.Map.Entry<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> e : playerGemHeldUses.entrySet()) {
            if (e.getValue() != null && e.getValue().containsKey(gemId)) { oldOwner = e.getKey(); break; }
        }
        if (newOwner.equals(oldOwner)) {
            // same owner: do not reset
            return;
        }
        java.util.Map<String, Integer> payload = null;
        if (oldOwner != null) {
            java.util.Map<java.util.UUID, java.util.Map<String, Integer>> map = playerGemHeldUses.get(oldOwner);
            if (map != null) payload = map.remove(gemId);
            if (map != null && map.isEmpty()) playerGemHeldUses.remove(oldOwner);
        }
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> dest = playerGemHeldUses.computeIfAbsent(newOwner, k -> new java.util.HashMap<>());
        if (payload == null) {
            // initialize only if new owner doesn't already have this gemId mapping
            if (!dest.containsKey(gemId)) dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        saveGems();
    }

    private void reassignRedeemInstanceAllowance(java.util.UUID gemId,
                                                 java.util.UUID newOwner,
                                                 org.cubexmc.model.GemDefinition def,
                                                 boolean resetEvenIfSameOwner) {
        if (gemId == null || newOwner == null || def == null) return;
        // Find old owner in redeemed map
        java.util.UUID oldOwner = null;
        for (java.util.Map.Entry<java.util.UUID, java.util.Map<java.util.UUID, java.util.Map<String, Integer>>> e : playerGemRedeemUses.entrySet()) {
            if (e.getValue() != null && e.getValue().containsKey(gemId)) { oldOwner = e.getKey(); break; }
        }
        if (newOwner.equals(oldOwner)) {
            if (resetEvenIfSameOwner) {
                playerGemRedeemUses.computeIfAbsent(newOwner, k -> new java.util.HashMap<>())
                    .put(gemId, buildAllowedMap(def));
                saveGems();
            }
            return;
        }
        java.util.Map<String, Integer> payload = null;
        if (oldOwner != null) {
            java.util.Map<java.util.UUID, java.util.Map<String, Integer>> map = playerGemRedeemUses.get(oldOwner);
            if (map != null) payload = map.remove(gemId);
            if (map != null && map.isEmpty()) playerGemRedeemUses.remove(oldOwner);
        }
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> dest = playerGemRedeemUses.computeIfAbsent(newOwner, k -> new java.util.HashMap<>());
        if (payload == null || resetEvenIfSameOwner) {
            dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        saveGems();
    }

    private java.util.Map<String, Integer> buildAllowedMap(org.cubexmc.model.GemDefinition def) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        java.util.List<org.cubexmc.model.AllowedCommand> allows = def.getAllowedCommands();
        if (allows != null) {
            for (org.cubexmc.model.AllowedCommand ac : allows) {
                if (ac == null || ac.getLabel() == null) continue;
                map.put(ac.getLabel().toLowerCase(java.util.Locale.ROOT), ac.getUses());
            }
        }
        return map;
    }

    public void refundAllowed(java.util.UUID uid, String label) {
        if (uid == null || label == null) return;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        // per-instance first: held then redeemed
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (java.util.Map<String, Integer> byLabel : perHeld.values()) {
                if (byLabel.containsKey(l)) { int v = byLabel.getOrDefault(l, 0); byLabel.put(l, v + 1); saveGems(); return; }
            }
        }
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (java.util.Map<String, Integer> byLabel : perRed.values()) {
                if (byLabel.containsKey(l)) { int v = byLabel.getOrDefault(l, 0); byLabel.put(l, v + 1); saveGems(); return; }
            }
        }
        // global
        java.util.Map<String, Integer> glob = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new java.util.HashMap<>());
        int v = glob.getOrDefault(l, 0);
        glob.put(l, v + 1);
        saveGems();
    }

    /**
     * 获取玩家的 AllowedCommand 对象（用于获取冷却时间等信息）
     */
    public org.cubexmc.model.AllowedCommand getAllowedCommand(java.util.UUID uid, String label) {
        if (uid == null || label == null) return null;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        
        // 从各个宝石定义中查找
        for (org.cubexmc.model.GemDefinition def : configManager.getGemDefinitions()) {
            for (org.cubexmc.model.AllowedCommand cmd : def.getAllowedCommands()) {
                if (cmd.getLabel().equals(l)) {
                    return cmd;
                }
            }
        }
        
        // 从 redeemAll 中查找
        for (org.cubexmc.model.AllowedCommand cmd : configManager.getRedeemAllAllowedCommands()) {
            if (cmd.getLabel().equals(l)) {
                return cmd;
            }
        }
        
        return null;
    }
    
    public int getRemainingAllowed(java.util.UUID uid, String label) {
        if (uid == null || label == null) return 0;
        String l = label.toLowerCase(java.util.Locale.ROOT);
        int sum = 0;
        // per-instance: held + redeemed
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (java.util.Map<String, Integer> byLabel : perHeld.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0) return -1; // 无限
                    sum += v2;
                }
            }
        }
        java.util.Map<java.util.UUID, java.util.Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (java.util.Map<String, Integer> byLabel : perRed.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0) return -1;
                    sum += v2;
                }
            }
        }
        // global
        java.util.Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer vg = glob.get(l);
            if (vg != null) {
                if (vg < 0) return -1;
                sum += vg;
            }
        }
        return sum;
    }

    private void queueOfflineRevokes(java.util.UUID user,
                                     java.util.Collection<String> perms,
                                     java.util.Collection<String> groups) {
        if (user == null) return;
        if (perms != null && !perms.isEmpty()) {
            java.util.Set<String> set = pendingPermRevokes.computeIfAbsent(user, k -> new java.util.HashSet<>());
            for (String p : perms) {
                if (p != null && !p.trim().isEmpty()) set.add(p);
            }
        }
        if (groups != null && !groups.isEmpty()) {
            java.util.Set<String> set = pendingGroupRevokes.computeIfAbsent(user, k -> new java.util.HashSet<>());
            for (String g : groups) {
                if (g != null && !g.trim().isEmpty()) set.add(g);
            }
        }
        // persist immediately
        saveGems();
    }

    private void triggerScatterEffects(UUID gemId, Location location, String playerName) {
        triggerScatterEffects(gemId, location, playerName, true);
    }

    private void triggerScatterEffects(UUID gemId, Location location, String playerName, boolean allowFallback) {
        if (location == null) {
            return;
        }
        org.cubexmc.model.GemDefinition definition = findGemDefinition(gemUuidToKey.get(gemId));
        Map<String, String> placeholders = playerName == null
                ? Collections.emptyMap()
                : Collections.singletonMap("%player%", playerName);
        if (definition != null && definition.getOnScatter() != null) {
            effectUtils.executeCommands(definition.getOnScatter(), placeholders);
            effectUtils.playLocalSound(location, definition.getOnScatter(), 1.0f, 1.0f);
            effectUtils.playParticle(location, definition.getOnScatter());
            return;
        }
        if (allowFallback) {
            ExecuteConfig fallback = configManager.getGemScatterExecute();
            effectUtils.playLocalSound(location, fallback, 1.0f, 1.0f);
            effectUtils.playParticle(location, fallback);
        }
    }

    // 兑换全部：要求集齐并且都在背包中
    public boolean redeemAll(Player player) {
        // 缓存玩家名称（用于离线时显示）
        cachePlayerName(player);
        if (!configManager.isFullSetGrantsAllEnabled()) {
            languageManager.sendMessage(player, "command.redeemall.disabled");
            return true;
        }
        List<org.cubexmc.model.GemDefinition> defs = configManager.getGemDefinitions();
        if (defs == null || defs.isEmpty()) {
            return false;
        }
        // 检查每个定义是否都持有
        Map<String, UUID> keyToGemId = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (!isRuleGem(item)) continue;
            UUID id = getGemUUID(item);
            String key = gemUuidToKey.get(id);
            if (key != null && !keyToGemId.containsKey(key.toLowerCase())) {
                keyToGemId.put(key.toLowerCase(), id);
            }
        }
    for (org.cubexmc.model.GemDefinition d : defs) {
            if (!keyToGemId.containsKey(d.getGemKey().toLowerCase())) {
                return false;
            }
        }
        // 所有都持有：依次触发 per-gem 兑换，再统一散落，并将所有 gem 的"当前拥有者"切换为本玩家
        java.util.UUID previousFull = this.fullSetOwner;
        this.fullSetOwner = player.getUniqueId();
        for (org.cubexmc.model.GemDefinition d : defs) {
            String normalizedKey = d.getGemKey().toLowerCase(Locale.ROOT);
            markGemRedeemed(player, d.getGemKey());
            // 切换该 gem 的当前拥有者（按 gemId），并做计数增减
            UUID gid = keyToGemId.get(normalizedKey);
            if (gid != null) {
                java.util.UUID old = gemIdToRedeemer.put(gid, player.getUniqueId());
                if (old != null && !old.equals(player.getUniqueId())) {
                    decrementOwnerKeyCount(old, normalizedKey, d);
                }
                incrementOwnerKeyCount(player.getUniqueId(), normalizedKey, d);
                applyRedeemRewards(player, d);
                // 兑换实例额度归属切换（同人重复兑换重置）
                reassignRedeemInstanceAllowance(gid, player.getUniqueId(), d, /*resetEvenIfSameOwner*/ true);
            }
            if (gid != null) {
                removeGemItemFromInventory(player, gid);
                gemUuidToHolder.remove(gid);
                randomPlaceGem(gid);
            }
        }
            // 切换全权力持有者：撤销上一任的全部权限与组（按计数判断：全套切换视为所有 key 归属从旧->新，直接撤销旧持有者权限与额度）
        String previousFullOwnerName = null;
        if (previousFull != null && !previousFull.equals(this.fullSetOwner)) {
            Player prev = Bukkit.getPlayer(previousFull);
            if (prev != null && prev.isOnline()) {
                previousFullOwnerName = prev.getName();
                    for (org.cubexmc.model.GemDefinition d : defs) {
                        if (d.getPermissions() != null) revokeNodes(prev, d.getPermissions());
                        if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                            try { plugin.getVaultPerms().playerRemoveGroup(prev, d.getVaultGroup()); } catch (Exception ignored) {}
                        }
                        revokeAllowedCommands(previousFull, d);
                    }
                prev.recalculatePermissions();
            } else {
                // Queue all per-gem revocations for previous full owner
                java.util.Set<String> allPerms = new java.util.HashSet<>();
                java.util.Set<String> allGroups = new java.util.HashSet<>();
                for (org.cubexmc.model.GemDefinition d : defs) {
                    if (d.getPermissions() != null) allPerms.addAll(d.getPermissions());
                    if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty()) allGroups.add(d.getVaultGroup());
                }
                queueOfflineRevokes(previousFull, allPerms, allGroups);
                    for (org.cubexmc.model.GemDefinition d : defs) revokeAllowedCommands(previousFull, d);
            }
        }

        // 记录全套宝石兑换事件
        if (historyLogger != null) {
            java.util.List<String> allPermissions = new java.util.ArrayList<>();
            for (org.cubexmc.model.GemDefinition d : defs) {
                if (d.getPermissions() != null) allPermissions.addAll(d.getPermissions());
            }
            historyLogger.logFullSetRedeem(player, defs.size(), allPermissions, previousFullOwnerName);
        }
        // 广播标题（可配置开关）：优先使用 config.redeem_all，否则使用语言文件的 gems_recollected
        boolean broadcast = configManager.getRedeemAllBroadcastOverride() != null ? configManager.getRedeemAllBroadcastOverride() : configManager.isBroadcastRedeemTitle();
        if (broadcast) {
            java.util.List<String> title = configManager.getRedeemAllTitle();
            java.util.Map<String, String> ph = new java.util.HashMap<>();
            ph.put("player", player.getName());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (title != null && !title.isEmpty()) {
                    if (title.size() == 1) {
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', languageManager.formatText(title.get(0), ph)), null, 10, 70, 20);
                    } else {
                        String l1 = languageManager.formatText(title.get(0), ph);
                        String l2 = languageManager.formatText(title.get(1), ph);
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', l1), org.bukkit.ChatColor.translateAlternateColorCodes('&', l2), 10, 70, 20);
                    }
                } else {
                    languageManager.showTitle(p, "gems_recollected", ph);
                }
            }
        }
        // 额外发放：redeem_all 权限与限次指令
        java.util.List<String> extraPerms = configManager.getRedeemAllPermissions();
        if (extraPerms != null && !extraPerms.isEmpty()) {
            grantRedeemPermissions(player, extraPerms);
        }
        java.util.List<org.cubexmc.model.AllowedCommand> extraAllows = configManager.getRedeemAllAllowedCommands();
        if (extraAllows != null && !extraAllows.isEmpty()) {
            // 将这些额外次数计入玩家，使用专用 gemKey "ALL" 进行隔离
            org.cubexmc.model.GemDefinition pseudo = new org.cubexmc.model.GemDefinition(
                    "ALL",
                    org.bukkit.Material.BEDROCK,
                    "ALL",
                    org.bukkit.Particle.FLAME,
                    org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    null, null, null,
                    java.util.Collections.emptyList(),
                    null,
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    false,
                    extraAllows,
                    java.util.Collections.emptyList(),
                    1,
                    null, null, null  // 添加随机生成范围和祭坛参数
            );
            grantAllowedCommands(player, pseudo);
        }
        // 播放全局音效（支持覆盖，默认龙吼）
        try {
            org.bukkit.Sound s = org.bukkit.Sound.valueOf(configManager.getRedeemAllSound());
            effectUtils.playGlobalSound(new ExecuteConfig(java.util.Collections.emptyList(), s.name(), null), 1.0f, 1.0f);
        } catch (Exception ignored) {}
        return true;
    }

    public java.util.Set<String> getAvailableCommandLabels(java.util.UUID uid) {
        java.util.Set<String> labels = new java.util.HashSet<>();
        if (uid == null) {
            return labels;
        }
        collectActiveLabelsFromNestedMap(labels, playerGemHeldUses.get(uid));
        collectActiveLabelsFromNestedMap(labels, playerGemRedeemUses.get(uid));
        collectActiveLabelsFromFlatMap(labels, playerGlobalAllowedUses.get(uid));
        return labels;
    }

    private void collectActiveLabelsFromNestedMap(java.util.Set<String> labels,
                                                  java.util.Map<java.util.UUID, java.util.Map<String, Integer>> nested) {
        if (nested == null || nested.isEmpty()) {
            return;
        }
        for (java.util.Map<String, Integer> inner : nested.values()) {
            collectActiveLabelsFromFlatMap(labels, inner);
        }
    }

    private void collectActiveLabelsFromFlatMap(java.util.Set<String> labels,
                                               java.util.Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, Integer> entry : map.entrySet()) {
            String key = entry.getKey();
            Integer remaining = entry.getValue();
            if (key == null || key.isBlank() || remaining == null) {
                continue;
            }
            if (remaining == 0) {
                continue;
            }
            String base = key.split(" ")[0].toLowerCase(ROOT_LOCALE);
            if (!base.isEmpty()) {
                labels.add(base);
            }
        }
    }

    /**
     * 返回当前的统治者玩家列表：
     * - fullSetOwner（若存在）
     * - 由 gemIdToRedeemer/ownerKeyCount 汇总的当前持有者
     */
    public java.util.Map<java.util.UUID, java.util.Set<String>> getCurrentRulers() {
        java.util.Map<java.util.UUID, java.util.Set<String>> map = new java.util.HashMap<>();
        if (this.fullSetOwner != null) {
            map.put(this.fullSetOwner, new java.util.HashSet<>(java.util.Collections.singleton("ALL")));
        }
        for (java.util.Map.Entry<java.util.UUID, java.util.UUID> e : gemIdToRedeemer.entrySet()) {
            java.util.UUID u = e.getValue();
            if (u == null) continue;
            String k = gemUuidToKey.get(e.getKey());
            if (k == null) continue;
            map.computeIfAbsent(u, kk -> new java.util.HashSet<>()).add(k.toLowerCase(java.util.Locale.ROOT));
        }
        return map;
    }

    public java.util.UUID getFullSetOwner() {
        return this.fullSetOwner;
    }

    private String resolveGemKeyByNameOrKey(String input) {
        if (input == null) return null;
        String lc = input.toLowerCase();
        for (org.cubexmc.model.GemDefinition d : configManager.getGemDefinitions()) {
            if (d.getGemKey().equalsIgnoreCase(input)) return d.getGemKey();
            String name = d.getDisplayName();
            if (name != null && org.bukkit.ChatColor.stripColor(name).replace("§", "&").replace("&", "").toLowerCase().contains(lc)) {
                return d.getGemKey();
            }
        }
        return null;
    }

    /**
     * 返回该宝石使用的材质；若未知则返回 RED_STAINED_GLASS 作为回退。
     */
    public org.bukkit.Material getGemMaterial(java.util.UUID gemId) {
        String key = gemUuidToKey.get(gemId);
        if (key != null) {
            org.cubexmc.model.GemDefinition def = null;
            for (org.cubexmc.model.GemDefinition d : configManager.getGemDefinitions()) {
                if (d.getGemKey().equalsIgnoreCase(key)) { def = d; break; }
            }
            if (def != null && def.getMaterial() != null) return def.getMaterial();
        }
        return org.bukkit.Material.RED_STAINED_GLASS;
    }

    /**
     * 某些材质（非完整方块）需要下方有支撑。如果需要支撑且目标位置下方不是实心方块，我们应当拒绝放置。
     * 这里采用一个保守的白名单：常见的易受支撑影响的方块返回 true。完整方块返回 false。
     */
    public boolean isSupportRequired(org.bukkit.Material mat) {
        if (mat == null) return false;
        // 运行时名称检查，避免编译期依赖高版本常量
        String name = mat.name();
        if ("SCULK_CATALYST".equals(name)) return true;
        // 保守策略：非实体（非 solid）的方块一般需要支撑
        try {
            if (!mat.isSolid()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 判断某坐标下方方块是否提供支撑（是否为实心方块）。
     */
    public boolean hasBlockSupport(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.Location below = loc.clone().add(0, -1, 0);
        org.bukkit.block.Block b = below.getBlock();
        if (b == null) return false;
        org.bukkit.Material m = b.getType();
        try {
            return m.isSolid();
        } catch (Throwable t) {
            // 某些版本 API 差异，保守返回 true 以避免误拒
            return true;
        }
    }

    /**
     * 获取所有已注册宝石的 UUID 集合
     */
    public java.util.Set<java.util.UUID> getAllGemUuids() {
        return new java.util.HashSet<>(gemUuidToKey.keySet());
    }

    /**
     * 解析命令中的 gem 标识：优先按 UUID 匹配现存的宝石，否则按 gemKey/显示名匹配唯一的一颗。
     * 若无法解析或不存在则返回 null。
     */
    public java.util.UUID resolveGemIdentifier(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();
        
        // 1. 尝试完整 UUID
        try {
            java.util.UUID id = java.util.UUID.fromString(trimmed);
            if (gemUuidToKey.containsKey(id)) return id;
        } catch (Exception ignored) {}
        
        // 2. 尝试简短 UUID 前缀匹配（至少8位）
        if (trimmed.length() >= 8 && !trimmed.contains(" ")) {
            for (java.util.UUID id : gemUuidToKey.keySet()) {
                if (id.toString().toLowerCase().startsWith(trimmed.toLowerCase())) {
                    return id;
                }
            }
        }
        
        // 3. Fallback to gem key/name：优先返回 placed 状态的宝石，跳过 held 状态
        String key = resolveGemKeyByNameOrKey(trimmed);
        if (key == null) return null;
        
        java.util.UUID firstHeld = null;
        for (Map.Entry<java.util.UUID, String> e : gemUuidToKey.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(key)) {
                java.util.UUID gemId = e.getKey();
                // 检查是否为 placed 状态（在世界中放置）
                if (gemUuidToLocation.containsKey(gemId)) {
                    return gemId; // 优先返回 placed 状态的宝石
                }
                // 记录第一个 held 状态的宝石作为后备
                if (firstHeld == null && gemUuidToHolder.containsKey(gemId)) {
                    firstHeld = gemId;
                }
            }
        }
        // 如果没有 placed 状态的，返回第一个 held 状态的（兼容性）
        return firstHeld;
    }

    /**
     * 强制将指定宝石移动到目标坐标：
     * - 若在玩家背包中，移除该物品
     * - 若已经放置在世界中，移除原位置
     * - 清理同 gemKey 的重复实例，确保全服唯一
     * - 最后在目标位置放置此宝石
     */
    public void forcePlaceGem(java.util.UUID gemId, org.bukkit.Location target) {
        if (gemId == null || target == null) return;
        final Location oldLoc = findLocationByGemId(gemId);
        final org.bukkit.entity.Player holder = gemUuidToHolder.get(gemId);
        // key no longer used here
        // 在目标区进行安全检查并放置；只有成功放置后才清理旧位置/持有/重复
        final Location base = target.clone();
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null) return;
            WorldBorder border = world.getWorldBorder();
            Location t = base.getBlock().getLocation();
            if (!border.isInside(t) || t.getBlockY() < world.getMinHeight() || t.getBlockY() > world.getMaxHeight()) {
                return; // 不放置，不清理
            }
            // 支撑性检查（如 SCULK_CATALYST）
            Material mat = getGemMaterial(gemId);
            if (isSupportRequired(mat) && !hasBlockSupport(t)) {
                return; // 不放置，不清理
            }
            // 放置并登记
            try {
                if (!t.getChunk().isLoaded()) t.getChunk().load();
            } catch (Throwable ignored) {}
            t.getBlock().setType(mat);
            locationToGemUuid.put(t, gemId);
            gemUuidToLocation.put(gemId, t);
            // 成功后：清理旧位置
            if (oldLoc != null) {
                unplaceRuleGem(oldLoc, gemId);
            }
            // 成功后：移除持有并撤回临时附件
            if (holder != null) {
                gemUuidToHolder.remove(gemId);
                removeGemItemFromInventory(holder, gemId);
                recalculateGrants(holder);
            }
            // 不再清理同 key 的重复：允许同类多实例存在
        }, 0L, -1L);
    }

    /**
     * 缓存玩家名称（用于离线时显示）
     * @param player 玩家
     */
    public void cachePlayerName(Player player) {
        if (player != null) {
            playerNameCache.put(player.getUniqueId(), player.getName());
        }
    }

    /**
     * 获取玩家名称（优先从缓存获取，回退到 Bukkit API）
     * @param uuid 玩家UUID
     * @return 玩家名称，如果无法获取则返回 UUID 的前8位
     */
    public String getCachedPlayerName(java.util.UUID uuid) {
        if (uuid == null) return "Unknown";
        
        // 1. 先检查是否在线
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            // 在线时更新缓存
            playerNameCache.put(uuid, online.getName());
            return online.getName();
        }
        
        // 2. 从缓存获取
        String cached = playerNameCache.get(uuid);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        
        // 3. 尝试从 OfflinePlayer 获取（可能需要查询 Mojang API，较慢）
        try {
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline.getName();
            if (name != null && !name.isEmpty()) {
                playerNameCache.put(uuid, name);
                return name;
            }
        } catch (Exception ignored) {}
        
        // 4. 最后回退到 UUID 前8位
        return uuid.toString().substring(0, 8);
    }
}
