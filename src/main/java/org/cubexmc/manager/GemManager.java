package org.cubexmc.manager;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.event.GemPickupEvent;
import org.cubexmc.event.GemPlaceEvent;
import org.cubexmc.event.GemRedeemEvent;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import org.cubexmc.model.PowerStructure;

/**
 * GemManager — 门面类（Facade）。
 * <p>
 * 协调四个子管理器（State / Allowance / Permission / Placement），
 * 对外保持统一的公共 API，供命令、监听器、GUI 等模块调用。
 */
public class GemManager {

    private final RuleGems plugin;
    private final ConfigManager configManager;
    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;
    private final EffectUtils effectUtils;
    private final LanguageManager languageManager;
    private HistoryLogger historyLogger;

    // ========== 子管理器 ==========
    private final GemStateManager stateManager;
    private final GemAllowanceManager allowanceManager;
    private final GemPermissionManager permissionManager;
    private final GemPlacementManager placementManager;
    private final GemScatterService scatterService;

    public GemManager(RuleGems plugin, ConfigManager configManager, GemDefinitionParser gemParser,
            GameplayConfig gameplayConfig, EffectUtils effectUtils,
            LanguageManager languageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.gemParser = gemParser;
        this.gameplayConfig = gameplayConfig;
        this.effectUtils = effectUtils;
        this.languageManager = languageManager;

        this.stateManager = new GemStateManager(plugin, gemParser, languageManager);
        this.allowanceManager = new GemAllowanceManager(gemParser, gameplayConfig);
        this.permissionManager = new GemPermissionManager(plugin, gameplayConfig, stateManager);
        this.placementManager = new GemPlacementManager(plugin, gemParser, gameplayConfig, languageManager,
                stateManager);
        this.scatterService = new GemScatterService(stateManager, placementManager, gemParser, gameplayConfig,
                effectUtils, languageManager, this::saveGems);

        // 交叉引用 & 回调
        this.allowanceManager.setSaveCallback(this::saveGems);
        this.permissionManager.setSaveCallback(this::saveGems);
        this.permissionManager.setAllowanceManager(allowanceManager);
        this.placementManager.setEffectUtils(effectUtils);

        // 定时刷盘：每 60 秒将额度脏数据持久化（避免频繁全量保存）
        SchedulerUtil.globalRun(plugin, () -> allowanceManager.flushIfDirty(),
                20L * 60, 20L * 60);
    }

    // ========== 子管理器访问器 ==========

    public GemStateManager getStateManager() {
        return stateManager;
    }

    public GemAllowanceManager getAllowanceManager() {
        return allowanceManager;
    }

    public GemPermissionManager getPermissionManager() {
        return permissionManager;
    }

    public GemPlacementManager getPlacementManager() {
        return placementManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public boolean isInventoryGrantsEnabled() {
        return gameplayConfig.isInventoryGrantsEnabled();
    }

    public void setHistoryLogger(HistoryLogger historyLogger) {
        this.historyLogger = historyLogger;
        this.permissionManager.setHistoryLogger(historyLogger);
    }

    // ====================================================================
    // 加载 / 保存
    // ====================================================================

    public void loadGems() {
        FileConfiguration gemsData = configManager.readGemsData();
        if (gemsData == null) {
            plugin.getLogger().warning("Failed to load gemsData config! Please check if the file exists.");
            return;
        }

        // 清空所有子管理器状态
        stateManager.clearAll();
        permissionManager.clearAll();
        allowanceManager.clearAll();

        // 委托加载
        permissionManager.loadData(gemsData);
        allowanceManager.loadData(gemsData);
        stateManager.loadData(gemsData, placementManager::randomPlaceGem);

        // 对已在线玩家执行待处理的离线撤销
        for (Player p : Bukkit.getOnlinePlayers()) {
            permissionManager.applyPendingRevokesIfAny(p);
        }

        // 日志
        Map<String, String> ph1 = new HashMap<>();
        ph1.put("count", String.valueOf(stateManager.getPlacedCount()));
        languageManager.logMessage("gems_loaded", ph1);
        Map<String, String> ph2 = new HashMap<>();
        ph2.put("count", String.valueOf(stateManager.getHeldCount()));
        languageManager.logMessage("gems_held_loaded", ph2);

        stateManager.rebuildGemDefinitionCache();
        placementManager.initializeEscapeTasks();
    }

    public void saveGems() {
        placementManager.cancelAllEscapeTasks();

        // 步骤 1：主线程中提取全部将要保存的数据为主线程安全的 Map
        Map<String, Object> snapshot = new HashMap<>();

        // 委托各自模块填充纯数据 (不依赖 Bukkit FileConfiguration)
        stateManager.populateSaveSnapshot(snapshot);
        permissionManager.populateSaveSnapshot(snapshot);
        allowanceManager.populateSaveSnapshot(snapshot);

        // 步骤 2：执行磁盘 I/O (如果插件已禁用，则同步执行；否则异步执行)
        Runnable saveTask = () -> {
            FileConfiguration gemsData = configManager.getGemsData();

            // 清理旧节点
            for (String key : new String[] {
                    "placed-gems", "held-gems", "redeemed", "redeem_owner",
                    "redeem_owner_by_id", "full_set_owner", "pending_revokes",
                    "allowed_uses", "player_names" }) {
                gemsData.set(key, null);
            }

            // 将 Snapshot 里的数据写入 YAML
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                gemsData.set(entry.getKey(), entry.getValue());
            }

            // 保存到磁盘
            configManager.saveGemData(gemsData);
        };

        if (plugin.isEnabled()) {
            SchedulerUtil.asyncRun(plugin, saveTask, 0L);
        } else {
            saveTask.run();
        }
    }

    // ====================================================================
    // 初始化辅助
    // ====================================================================

    public void ensureConfiguredGemsPresent() {
        stateManager.ensureConfiguredGemsPresent(placementManager::randomPlaceGem);
    }

    public void initializePlacedGemBlocks() {
        placementManager.initializePlacedGemBlocks();
    }

    // ====================================================================
    // 事件处理（协调子管理器）
    // ====================================================================

    /** 宝石方块被敲击 → 允许一击破坏 */
    public void onGemDamage(BlockDamageEvent event) {
        stateManager.onGemDamage(event);
    }

    /** 宝石放置 */
    public void onGemPlaced(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!stateManager.isRuleGem(inHand))
            return;

        UUID gemId = stateManager.getGemUUID(inHand);
        if (gemId == null)
            gemId = UUID.randomUUID();

        Block block = event.getBlockPlaced();
        Location placedLoc = block.getLocation();
        Player placer = event.getPlayer();

        // 祭坛模式检测
        String currentGemKey = stateManager.getGemKey(gemId);
        GemDefinition matchedDef = findMatchingAltarGem(placedLoc, currentGemKey);
        if (matchedDef != null) {
            handlePlaceRedeem(placer, gemId, placedLoc, block, matchedDef);
            return;
        }

        // 普通放置 — fire GemPlaceEvent before modifying state
        String gemKeyForEvent = stateManager.getGemKey(gemId);
        GemPlaceEvent placeEvent = new GemPlaceEvent(placer, gemId, gemKeyForEvent, placedLoc);
        Bukkit.getPluginManager().callEvent(placeEvent);
        if (placeEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        stateManager.clearGemHolder(gemId);
        stateManager.bindPlacedGem(placedLoc, gemId);

        if (historyLogger != null) {
            String gemKey = stateManager.getGemKey(gemId);
            String locationStr = String.format("(%d, %d, %d) %s",
                    placedLoc.getBlockX(), placedLoc.getBlockY(), placedLoc.getBlockZ(),
                    placedLoc.getWorld() != null ? placedLoc.getWorld().getName() : "unknown");
            historyLogger.logGemPlace(placer, gemKey != null ? gemKey : gemId.toString(), locationStr);
        }

        // 创造模式下确保移除背包残留
        if (placer != null) {
            UUID fGemId = gemId;
            SchedulerUtil.entityRun(plugin, placer, () -> {
                stateManager.removeGemItemFromInventory(placer, fGemId);
                try {
                    placer.updateInventory();
                } catch (Throwable e) {
                    plugin.getLogger().fine("Failed to update placer inventory: " + e.getMessage());
                }
            }, 1L, -1L);
        }
    }

    /** 宝石方块被破坏 → 回收到玩家背包 */
    public void onGemBroken(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!stateManager.getLocationToGemUuid().containsKey(block.getLocation()))
            return;

        event.setDropItems(false);
        try {
            event.setExpToDrop(0);
        } catch (Throwable e) {
            plugin.getLogger().fine("Failed to set exp drop to zero: " + e.getMessage());
        }

        UUID gemId = stateManager.getLocationToGemUuid().get(block.getLocation());
        Player player = event.getPlayer();
        Inventory inv = player.getInventory();

        if (inv.firstEmpty() == -1) {
            languageManager.logMessage("inventory_full");
            event.setCancelled(true);
            return;
        }

        // Fire GemPickupEvent before giving the gem to the player
        String pickupKey = stateManager.getGemKey(gemId);
        GemPickupEvent pickupEvent = new GemPickupEvent(player, gemId, pickupKey, block.getLocation());
        Bukkit.getPluginManager().callEvent(pickupEvent);
        if (pickupEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        ItemStack gemItem = stateManager.createRuleGem(gemId);
        inv.addItem(gemItem);
        stateManager.setGemHolder(gemId, player);
        placementManager.cancelEscape(gemId);
        placementManager.unplaceRuleGem(block.getLocation(), gemId);
        permissionManager.handleInventoryOwnershipOnPickup(player, gemId);

        GemDefinition def = stateManager.findGemDefinition(stateManager.getGemKey(gemId));
        if (def != null && def.getOnPickup() != null) {
            effectUtils.executeCommands(def.getOnPickup(),
                    Collections.singletonMap("%player%", player.getName()));
            effectUtils.playLocalSound(player.getLocation(), def.getOnPickup(), 1.0f, 1.0f);
            effectUtils.playParticle(player.getLocation(), def.getOnPickup());
        }
    }

    /** 玩家退出 → 背包中的宝石需放到世界 */
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (ItemStack item : player.getInventory().getContents()) {
            if (stateManager.isRuleGem(item)) {
                UUID gemId = stateManager.getGemUUID(item);
                player.getInventory().remove(item);
                stateManager.clearGemHolder(gemId);
                placementManager.placeRuleGem(player.getLocation(), gemId);
            }
        }
    }

    /** 玩家丢弃宝石 → 放置到地面 */
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (!stateManager.isRuleGem(item))
            return;
        UUID gemId = stateManager.getGemUUID(item);
        event.getItemDrop().remove();
        stateManager.clearGemHolder(gemId);
        Location loc = event.getItemDrop().getLocation();
        placementManager.triggerScatterEffects(gemId, loc, event.getPlayer().getName());
        placementManager.placeRuleGem(loc, gemId);
    }

    /** 玩家死亡 → 掉落列表中的宝石变为方块 */
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLoc = player.getLocation();
        java.util.Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (stateManager.isRuleGem(item)) {
                UUID gemId = stateManager.getGemUUID(item);
                it.remove();
                stateManager.clearGemHolder(gemId);
                placementManager.triggerScatterEffects(gemId, deathLoc, player.getName());
                placementManager.placeRuleGem(deathLoc, gemId);
            }
        }
    }

    /** 玩家加入 → 清理异常状态 + 执行离线待撤销 */
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (ItemStack item : player.getInventory().getContents()) {
            if (stateManager.isRuleGem(item)) {
                UUID gemId = stateManager.getGemUUID(item);
                if (!stateManager.getGemUuidToHolder().containsKey(gemId)) {
                    player.getInventory().remove(item);
                }
            }
        }
        permissionManager.applyPendingRevokesIfAny(player);
    }

    // ====================================================================
    // 散落
    // ====================================================================

    public void scatterGems() {
        scatterService.scatterGems();
    }

    // ====================================================================
    // 兑换
    // ====================================================================

    /**
     * 主手持有宝石的兑换流程
     */
    public boolean redeemGemInHand(Player player) {
        if (player == null)
            return false;
        stateManager.cachePlayerName(player);
        if (!gameplayConfig.isRedeemEnabled()) {
            languageManager.sendMessage(player, "command.redeem.disabled");
            return true;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!stateManager.isRuleGem(inHand))
            return false;
        UUID matchedGemId = stateManager.getGemUUID(inHand);
        if (matchedGemId == null)
            return false;

        String targetKey = stateManager.getGemKey(matchedGemId);
        if (targetKey == null || targetKey.isEmpty()) {
            stateManager.ensureGemKeyAssigned(matchedGemId);
            targetKey = stateManager.getGemKey(matchedGemId);
            if (targetKey == null || targetKey.isEmpty())
                return false;
        }

        GemDefinition def = stateManager.findGemDefinition(targetKey);

        // Fire GemRedeemEvent before modifying state
        GemRedeemEvent redeemEvent = new GemRedeemEvent(player, matchedGemId, targetKey,
                GemRedeemEvent.RedeemContext.HAND);
        Bukkit.getPluginManager().callEvent(redeemEvent);
        if (redeemEvent.isCancelled()) {
            return true;
        }

        String previousOwnerName = processRedeemCore(player, matchedGemId, targetKey, def);

        stateManager.removeGemItemFromInventory(player, matchedGemId);
        stateManager.getGemUuidToHolder().remove(matchedGemId);
        placementManager.randomPlaceGem(matchedGemId);

        if (historyLogger != null) {
            historyLogger.logGemRedeem(player, targetKey,
                    def != null ? def.getDisplayName() : null,
                    def != null ? def.getPermissions() : null,
                    def != null ? def.getVaultGroup() : null,
                    previousOwnerName);
        }

        broadcastRedeemTitle(player, def, targetKey);
        return true;
    }

    /**
     * 全套集齐兑换
     */
    public boolean redeemAll(Player player) {
        stateManager.cachePlayerName(player);
        if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
            languageManager.sendMessage(player, "command.redeemall.disabled");
            return true;
        }
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs == null || defs.isEmpty())
            return false;

        // 检查每个定义是否都持有
        Map<String, UUID> keyToGemId = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (!stateManager.isRuleGem(item))
                continue;
            UUID id = stateManager.getGemUUID(item);
            String key = stateManager.getGemKey(id);
            if (key != null && !keyToGemId.containsKey(key.toLowerCase())) {
                keyToGemId.put(key.toLowerCase(), id);
            }
        }
        for (GemDefinition d : defs) {
            if (!keyToGemId.containsKey(d.getGemKey().toLowerCase()))
                return false;
        }

        // 依次兑换每颗宝石
        UUID previousFull = permissionManager.getFullSetOwner();
        permissionManager.setFullSetOwner(player.getUniqueId());
        for (GemDefinition d : defs) {
            String normalizedKey = d.getGemKey().toLowerCase(Locale.ROOT);
            UUID gid = keyToGemId.get(normalizedKey);

            // Fire GemRedeemEvent per gem (FULL_SET context)
            GemRedeemEvent redeemEvent = new GemRedeemEvent(player, gid, d.getGemKey(),
                    GemRedeemEvent.RedeemContext.FULL_SET);
            Bukkit.getPluginManager().callEvent(redeemEvent);
            if (redeemEvent.isCancelled())
                continue; // skip this gem if cancelled

            permissionManager.markGemRedeemed(player, d.getGemKey());
            if (gid != null) {
                UUID old = permissionManager.getGemIdToRedeemer().put(gid, player.getUniqueId());
                if (old != null && !old.equals(player.getUniqueId())) {
                    permissionManager.decrementOwnerKeyCount(old, normalizedKey, d);
                }
                permissionManager.incrementOwnerKeyCount(player.getUniqueId(), normalizedKey, d);
                applyRedeemRewards(player, d);
                allowanceManager.reassignRedeemInstanceAllowance(gid, player.getUniqueId(), d, true);
                stateManager.removeGemItemFromInventory(player, gid);
                stateManager.getGemUuidToHolder().remove(gid);
                placementManager.randomPlaceGem(gid);
            }
        }

        // 撤销前一持有者
        revokePreviousFullSetOwner(previousFull, defs);

        if (historyLogger != null) {
            java.util.List<String> allPerms = new java.util.ArrayList<>();
            for (GemDefinition d : defs) {
                if (d.getPermissions() != null)
                    allPerms.addAll(d.getPermissions());
            }
            historyLogger.logFullSetRedeem(player, defs.size(), allPerms,
                    previousFull != null && !previousFull.equals(player.getUniqueId())
                            ? stateManager.getCachedPlayerName(previousFull)
                            : null);
        }

        broadcastRedeemAllTitle(player, defs);
        applyRedeemAllPower(player, defs);
        return true;
    }

    // ====================================================================
    // 兑换内部辅助
    // ====================================================================

    /**
     * 兑换核心逻辑（供 redeemGemInHand / handlePlaceRedeem / redeemAll 共享）。
     * 执行：标记兑换 → 应用奖励 → 归属转移（竞争撤销）→ 额度重分配。
     *
     * @return 被竞争替换的前任拥有者名称（可为 null）
     */
    private String processRedeemCore(Player player, UUID gemId, String targetKey, GemDefinition def) {
        permissionManager.markGemRedeemed(player, targetKey);
        applyRedeemRewards(player, def);

        String normalizedKey = targetKey.toLowerCase(Locale.ROOT);
        UUID old = permissionManager.getGemIdToRedeemer().put(gemId, player.getUniqueId());
        String previousOwnerName = null;
        if (old != null && !old.equals(player.getUniqueId())) {
            permissionManager.decrementOwnerKeyCount(old, normalizedKey, def);
            Player oldP = Bukkit.getPlayer(old);
            if (oldP != null && oldP.isOnline())
                previousOwnerName = oldP.getName();
        }
        permissionManager.incrementOwnerKeyCount(player.getUniqueId(), normalizedKey, def);
        allowanceManager.reassignRedeemInstanceAllowance(gemId, player.getUniqueId(), def, true);
        return previousOwnerName;
    }

    private GemDefinition findMatchingAltarGem(Location loc, String gemKey) {
        if (!gameplayConfig.isPlaceRedeemEnabled() || loc == null || gemKey == null)
            return null;
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def == null)
            return null;
        Location altar = def.getAltarLocation();
        if (altar == null || altar.getWorld() == null || loc.getWorld() == null)
            return null;
        if (!altar.getWorld().equals(loc.getWorld()))
            return null;
        if (altar.distance(loc) <= gameplayConfig.getPlaceRedeemRadius())
            return def;
        return null;
    }

    private void handlePlaceRedeem(Player player, UUID gemId, Location placedLoc, Block block, GemDefinition def) {
        if (player == null || gemId == null || def == null)
            return;
        String targetKey = def.getGemKey();

        // Fire GemRedeemEvent (altar context) before modifying state
        GemRedeemEvent redeemEvent = new GemRedeemEvent(player, gemId, targetKey, GemRedeemEvent.RedeemContext.ALTAR);
        Bukkit.getPluginManager().callEvent(redeemEvent);
        if (redeemEvent.isCancelled()) {
            return;
        }

        placementManager.playPlaceRedeemEffects(placedLoc);
        String previousOwnerName = processRedeemCore(player, gemId, targetKey, def);

        if (historyLogger != null) {
            historyLogger.logGemRedeem(player, targetKey, def.getDisplayName(),
                    def.getPermissions() != null ? def.getPermissions() : Collections.emptyList(),
                    def.getVaultGroup(), previousOwnerName);
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("gem_name", def.getDisplayName());
        ph.put("gem_key", targetKey);
        ph.put("player", player.getName());
        languageManager.sendMessage(player, "place_redeem.success", ph);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player))
                languageManager.sendMessage(online, "place_redeem.broadcast", ph);
        }

        stateManager.getGemUuidToHolder().remove(gemId);
        SchedulerUtil.regionRun(plugin, placedLoc, () -> block.setType(Material.AIR), 1L, -1L);
        placementManager.randomPlaceGem(gemId);

        SchedulerUtil.entityRun(plugin, player, () -> {
            stateManager.removeGemItemFromInventory(player, gemId);
            try {
                player.updateInventory();
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to update player inventory: " + e.getMessage());
            }
        }, 1L, -1L);
    }

    private void applyRedeemRewards(Player player, GemDefinition def) {
        if (player == null || def == null)
            return;
        ExecuteConfig onRedeem = def.getOnRedeem();
        if (onRedeem != null) {
            Map<String, String> ph = Map.of("%player%", player.getName());
            effectUtils.executeCommands(onRedeem, ph);
            effectUtils.playLocalSound(player.getLocation(), onRedeem, 1.0f, 1.0f);
            effectUtils.playParticle(player.getLocation(), onRedeem);
        }
    }

    private void broadcastRedeemTitle(Player player, GemDefinition def, String targetKey) {
        if (!gameplayConfig.isBroadcastRedeemTitle())
            return;
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        ph.put("gem", def != null && def.getDisplayName() != null ? def.getDisplayName() : targetKey);
        List<String> title = def != null ? def.getRedeemTitle() : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (title != null && !title.isEmpty()) {
                sendTitle(p, title, ph);
            } else {
                languageManager.showTitle(p, "gems_scattered", Collections.singletonMap("count", "1"));
            }
        }
    }

    private void broadcastRedeemAllTitle(Player player, List<GemDefinition> defs) {
        boolean broadcast = gameplayConfig.getRedeemAllBroadcastOverride() != null
                ? gameplayConfig.getRedeemAllBroadcastOverride()
                : gameplayConfig.isBroadcastRedeemTitle();
        if (!broadcast)
            return;
        List<String> title = gameplayConfig.getRedeemAllTitle();
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (title != null && !title.isEmpty()) {
                sendTitle(p, title, ph);
            } else {
                languageManager.showTitle(p, "gems_recollected", ph);
            }
        }
    }

    private void sendTitle(Player p, List<String> title, Map<String, String> ph) {
        if (title == null || title.isEmpty()) {
            return;
        }
        if (title.size() == 1) {
            p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    languageManager.formatText(title.get(0), ph)), null, 10, 70, 20);
        } else {
            String l1 = languageManager.formatText(title.get(0), ph);
            String l2 = languageManager.formatText(title.get(1), ph);
            p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', l1),
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', l2), 10, 70, 20);
        }
    }

    private void revokePreviousFullSetOwner(UUID previousFull, List<GemDefinition> defs) {
        if (previousFull == null || previousFull.equals(permissionManager.getFullSetOwner()))
            return;
        Player prev = Bukkit.getPlayer(previousFull);
        if (prev != null && prev.isOnline()) {
            PowerStructureManager psm = plugin.getPowerStructureManager();
            for (GemDefinition d : defs) {
                if (psm != null && d.getPowerStructure() != null) {
                    psm.removeStructure(prev, d.getPowerStructure(), "gem_redeem", d.getGemKey());
                } else if (d.getPermissions() != null) {
                    permissionManager.revokeNodesAll(prev, d.getPermissions());
                }
                if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty()) {
                    plugin.getPermissionProvider().removeGroup(prev, d.getVaultGroup());
                }
            }
            prev.recalculatePermissions();
        } else {
            Set<String> allPerms = new HashSet<>();
            Set<String> allGroups = new HashSet<>();
            for (GemDefinition d : defs) {
                if (d.getPermissions() != null)
                    allPerms.addAll(d.getPermissions());
                if (d.getVaultGroup() != null && !d.getVaultGroup().isEmpty())
                    allGroups.add(d.getVaultGroup());
            }
            permissionManager.queueOfflineRevokes(previousFull, allPerms, allGroups);
        }
    }

    private void applyRedeemAllPower(Player player, List<GemDefinition> defs) {
        PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
        if (redeemAllPower == null || !redeemAllPower.hasAnyContent())
            return;
        PowerStructureManager psm = plugin.getPowerStructureManager();
        if (psm != null) {
            psm.applyStructure(player, redeemAllPower, "gem_redeem_all", "full_set", false);
        } else if (redeemAllPower.getPermissions() != null) {
            permissionManager.grantRedeemPermissions(player, redeemAllPower.getPermissions());
        }
        List<org.cubexmc.model.AllowedCommand> extraAllows = redeemAllPower.getAllowedCommands();
        if (extraAllows != null && !extraAllows.isEmpty()) {
            GemDefinition pseudoDef = new GemDefinition.Builder("ALL")
                    .material(Material.BEDROCK).displayName("ALL")
                    .powerStructure(redeemAllPower).build();
            allowanceManager.grantGlobalAllowedCommands(player, pseudoDef);
        }
        try {
            org.bukkit.Sound s = org.bukkit.Sound.valueOf(gameplayConfig.getRedeemAllSound());
            effectUtils.playGlobalSound(new ExecuteConfig(Collections.emptyList(), s.name(), null), 1.0f, 1.0f);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to play redeem-all sound: " + e.getMessage());
        }
    }

    // ====================================================================
    // 强制放置
    // ====================================================================

    public void forcePlaceGem(UUID gemId, Location target) {
        if (gemId == null || target == null)
            return;
        Player holder = stateManager.getGemHolder(gemId);
        // 如果持有者在线，先移除物品
        if (holder != null) {
            stateManager.getGemUuidToHolder().remove(gemId);
            stateManager.removeGemItemFromInventory(holder, gemId);
            recalculateGrants(holder);
        }
        placementManager.forcePlaceGem(gemId, target, holder);
    }

    // ====================================================================
    // 宝石状态查询（委托 stateManager）— 仅保留多模块共享的方法
    // ====================================================================

    public void gemStatus(CommandSender sender) {
        stateManager.gemStatus(sender);
    }

    public boolean isRuleGem(ItemStack item) {
        return stateManager.isRuleGem(item);
    }

    public UUID getGemUUID(ItemStack item) {
        return stateManager.getGemUUID(item);
    }

    public Location getGemLocation(UUID gemId) {
        return stateManager.getGemLocation(gemId);
    }

    public Player getGemHolder(UUID gemId) {
        return stateManager.getGemHolder(gemId);
    }

    public String getGemKey(UUID gemId) {
        return stateManager.getGemKey(gemId);
    }

    public Set<UUID> getAllGemUuids() {
        return stateManager.getAllGemUuids();
    }

    public UUID resolveGemIdentifier(String input) {
        return stateManager.resolveGemIdentifier(input);
    }

    public Material getGemMaterial(UUID gemId) {
        return stateManager.getGemMaterial(gemId);
    }

    public boolean isSupportRequired(Material mat) {
        return stateManager.isSupportRequired(mat);
    }

    public boolean hasBlockSupport(Location loc) {
        return stateManager.hasBlockSupport(loc);
    }

    public Map<UUID, Location> getAllGemLocations() {
        return stateManager.getAllGemLocations();
    }

    public GemDefinition findGemDefinitionByKey(String gemKey) {
        return stateManager.findGemDefinition(gemKey);
    }

    public String getCachedPlayerName(UUID uuid) {
        return stateManager.getCachedPlayerName(uuid);
    }

    // ====================================================================
    // 权限操作（委托 permissionManager）— 仅保留多模块共享的方法
    // ====================================================================

    public void recalculateGrants(Player player) {
        permissionManager.recalculateGrants(player);
    }

    public boolean revokeAllPlayerPermissions(Player player) {
        return permissionManager.revokeAllPlayerPermissions(player);
    }

    public Map<UUID, Set<String>> getCurrentRulers() {
        return permissionManager.getCurrentRulers();
    }

    public void queueOfflineRevokes(UUID user, java.util.Collection<String> perms,
            java.util.Collection<String> groups) {
        permissionManager.queueOfflineRevokes(user, perms, groups);
    }

    public void queueOfflineEffectRevokes(UUID user, List<org.cubexmc.model.EffectConfig> effects) {
        permissionManager.queueOfflineEffectRevokes(user, effects);
    }

    // ====================================================================
    // 放置操作（委托 placementManager）— 仅保留多模块共享的方法
    // ====================================================================

    public void startParticleEffectTask(Particle particle) {
        placementManager.startParticleEffectTask(particle);
    }

    public void checkPlayersNearRuleGems() {
        placementManager.checkPlayersNearRuleGems();
    }

    public void setGemAltarLocation(String gemKey, Location location) {
        placementManager.setGemAltarLocation(gemKey, location);
    }

    public void removeGemAltarLocation(String gemKey) {
        placementManager.removeGemAltarLocation(gemKey);
    }
}
