package org.cubexmc.manager;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.event.GemPickupEvent;
import org.cubexmc.event.GemPlaceEvent;
import org.cubexmc.event.GemRedeemEvent;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.RedeemIngredient;
import org.cubexmc.model.RedeemRequirementResult;
import org.cubexmc.model.RedeemRecipe;
import org.cubexmc.model.RedeemRequirements;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.ColorUtils;
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
                effectUtils, languageManager, () -> {
                    permissionManager.clearRuntimeState();
                    allowanceManager.clearAll();
                }, this::saveGems);

        // 交叉引用 & 回调
        this.allowanceManager.setSaveCallback(this::saveGems);
        this.allowanceManager.setIsToggledOffCheck(this::isGemIdToggledOff);
        this.allowanceManager.setGemKeyLookup(stateManager::getGemKey);
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

    /**
     * 辅助桥接方法：判断某个宝石 ID 对应的能力是否被玩家手动关闭
     */
    public boolean isGemIdToggledOff(UUID playerId, UUID gemId) {
        if (playerId == null || gemId == null) return false;
        String gemKey = stateManager.getGemUuidToKey().get(gemId);
        if (gemKey == null) return false;
        return permissionManager.isGemToggledOff(playerId, gemKey);
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
        permissionManager.clearRuntimeState();
        allowanceManager.clearAll();

        // 先加载状态层，再恢复归属/额度数据。
        // 这样旧版基于 gemKey 的所有权数据也能在当前 gemId 映射上正确重建。
        stateManager.loadData(gemsData, placementManager::randomPlaceGem);
        permissionManager.loadData(gemsData);
        allowanceManager.loadData(gemsData);

        // 运行时权限附件不会持久化，重启/重载后需要按已保存归属重新恢复。
        permissionManager.restoreRedeemedPermissionsForOnlinePlayers();

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

    // ========== 保存键名常量 ==========
    public static final String KEY_PLACED_GEMS = "placed-gems";
    public static final String KEY_HELD_GEMS = "held-gems";
    public static final String KEY_REDEEMED = "redeemed";
    public static final String KEY_REDEEM_OWNER = "redeem_owner";
    public static final String KEY_REDEEM_OWNER_BY_ID = "redeem_owner_by_id";
    public static final String KEY_FULL_SET_OWNER = "full_set_owner";
    public static final String KEY_PENDING_REVOKES = "pending_revokes";
    public static final String KEY_ALLOWED_USES = "allowed_uses";
    public static final String KEY_PLAYER_NAMES = "player_names";

    public void saveGems() {
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
                    KEY_PLACED_GEMS, KEY_HELD_GEMS, KEY_REDEEMED, KEY_REDEEM_OWNER,
                    KEY_REDEEM_OWNER_BY_ID, KEY_FULL_SET_OWNER, KEY_PENDING_REVOKES,
                    KEY_ALLOWED_USES, KEY_PLAYER_NAMES }) {
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
    // 业务逻辑处理（由相应的 Listener 调用）
    // ====================================================================

    /** 宝石方块被敲击 → 允许一击破坏 */
    public void handleBlockDamage(BlockDamageEvent event) {
        stateManager.onGemDamage(event);
    }

    /** 宝石放置 */
    public void handleGemBlockPlace(Player placer, ItemStack inHand, Block block, BlockPlaceEvent event) {
        if (!stateManager.isRuleGem(inHand))
            return;

        UUID gemId = stateManager.getGemUUID(inHand);
        if (gemId == null)
            gemId = UUID.randomUUID();

        Location placedLoc = block.getLocation();

        // 祭坛模式检测
        String currentGemKey = stateManager.getGemKey(gemId);
        GemDefinition matchedDef = findMatchingAltarGem(placedLoc, currentGemKey);
        if (matchedDef != null) {
            event.setCancelled(true);
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
    public void handleGemBlockBreak(Player player, Block block, BlockBreakEvent event) {
        if (!stateManager.getLocationToGemUuid().containsKey(block.getLocation()))
            return;

        event.setDropItems(false);
        try {
            event.setExpToDrop(0);
        } catch (Throwable e) {
            plugin.getLogger().fine("Failed to set exp drop to zero: " + e.getMessage());
        }

        UUID gemId = stateManager.getLocationToGemUuid().get(block.getLocation());
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
    public void handlePlayerQuit(Player player) {
        com.google.common.base.Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread");
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
    public void handleGemDrop(Player player, Location loc, org.bukkit.entity.Item droppedItemEntity, ItemStack item) {
        if (!stateManager.isRuleGem(item))
            return;
        UUID gemId = stateManager.getGemUUID(item);
        droppedItemEntity.remove();
        stateManager.clearGemHolder(gemId);
        placementManager.triggerScatterEffects(gemId, loc, player.getName());
        placementManager.placeRuleGem(loc, gemId);
    }

    /** 玩家死亡 → 掉落列表中的宝石变为方块 */
    public void handlePlayerDeathDrops(Player player, Location deathLoc, List<ItemStack> drops) {
        com.google.common.base.Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread");
        java.util.Iterator<ItemStack> it = drops.iterator();
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
    public void handlePlayerJoin(Player player) {
        com.google.common.base.Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread");
        for (ItemStack item : player.getInventory().getContents()) {
            if (stateManager.isRuleGem(item)) {
                UUID gemId = stateManager.getGemUUID(item);
                if (!stateManager.getGemUuidToHolder().containsKey(gemId)) {
                    player.getInventory().remove(item);
                }
            }
        }
        permissionManager.restoreRedeemedPermissions(player);
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

        // 互斥检查
        Set<String> alreadyRedeemed = permissionManager.getPlayerUuidToRedeemedKeys().get(player.getUniqueId());
        if (alreadyRedeemed != null && permissionManager.conflictsWithSelected(targetKey, alreadyRedeemed)) {
            languageManager.sendMessage(player, "command.redeem.conflict");
            // Optionally play a sound or send action bar
            effectUtils.playLocalSound(player.getLocation(), "ENTITY_VILLAGER_NO", 1.0f, 1.0f);
            return true; // We handled the request, just rejected it
        }

        GemDefinition def = stateManager.findGemDefinition(targetKey);
        RedeemRequirementResult requirementResult = evaluateRedeemRequirements(player, def, matchedGemId,
                GemRedeemEvent.RedeemContext.HAND);
        if (!requirementResult.isAllowed()) {
            sendRedeemRequirementFailure(player, requirementResult);
            return false;
        }

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
        consumeRequirementGems(player, requirementResult);

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
        for (GemDefinition d : defs) {
            UUID gid = keyToGemId.get(d.getGemKey().toLowerCase(Locale.ROOT));
            RedeemRequirementResult requirementResult = evaluateRedeemRequirements(player, d, gid,
                    GemRedeemEvent.RedeemContext.FULL_SET);
            if (!requirementResult.isAllowed()) {
                sendRedeemRequirementFailure(player, requirementResult);
                return true;
            }
        }

        // Phase 1: validate every redeem event before mutating any internal state.
        for (GemDefinition d : defs) {
            String normalizedKey = d.getGemKey().toLowerCase(Locale.ROOT);
            UUID gid = keyToGemId.get(normalizedKey);
            GemRedeemEvent redeemEvent = new GemRedeemEvent(player, gid, d.getGemKey(),
                    GemRedeemEvent.RedeemContext.FULL_SET);
            Bukkit.getPluginManager().callEvent(redeemEvent);
            if (redeemEvent.isCancelled())
                return true;
        }

        // Phase 2: all events accepted, so the full-set redeem can commit.
        UUID previousFull = permissionManager.getFullSetOwner();
        permissionManager.setFullSetOwner(player.getUniqueId());
        for (GemDefinition d : defs) {
            String normalizedKey = d.getGemKey().toLowerCase(Locale.ROOT);
            UUID gid = keyToGemId.get(normalizedKey);

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

    private enum PlaceRedeemResult {
        SUCCESS,
        REJECTED,
        CANCELLED_BY_EVENT,
        INVALID
    }

    private PlaceRedeemResult handlePlaceRedeem(Player player, UUID gemId, Location placedLoc, Block block, GemDefinition def) {
        if (player == null || gemId == null || def == null)
            return PlaceRedeemResult.INVALID;
        String targetKey = def.getGemKey();

        // 互斥检查
        Set<String> alreadyRedeemed = permissionManager.getPlayerUuidToRedeemedKeys().get(player.getUniqueId());
        if (alreadyRedeemed != null && permissionManager.conflictsWithSelected(targetKey, alreadyRedeemed)) {
            languageManager.sendMessage(player, "command.redeem.conflict");
            effectUtils.playLocalSound(player.getLocation(), "ENTITY_VILLAGER_NO", 1.0f, 1.0f);
            return PlaceRedeemResult.REJECTED;
        }

        // Fire GemRedeemEvent (altar context) before modifying state
        RedeemRequirementResult requirementResult = evaluateRedeemRequirements(player, def, gemId,
                GemRedeemEvent.RedeemContext.ALTAR);
        if (!requirementResult.isAllowed()) {
            sendRedeemRequirementFailure(player, requirementResult);
            return PlaceRedeemResult.REJECTED;
        }

        GemRedeemEvent redeemEvent = new GemRedeemEvent(player, gemId, targetKey, GemRedeemEvent.RedeemContext.ALTAR);
        Bukkit.getPluginManager().callEvent(redeemEvent);
        if (redeemEvent.isCancelled()) {
            return PlaceRedeemResult.CANCELLED_BY_EVENT;
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
        consumeRequirementGems(player, requirementResult);

        SchedulerUtil.entityRun(plugin, player, () -> {
            stateManager.removeGemItemFromInventory(player, gemId);
            try {
                player.updateInventory();
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to update player inventory: " + e.getMessage());
            }
        }, 1L, -1L);
        return PlaceRedeemResult.SUCCESS;
    }

    private RedeemRequirementResult evaluateRedeemRequirements(Player player, GemDefinition def, UUID targetGemId,
            GemRedeemEvent.RedeemContext context) {
        if (player == null || def == null || def.getRedeemRequirements() == null) {
            return RedeemRequirementResult.ALLOWED;
        }
        RedeemRequirements requirements = def.getRedeemRequirements();
        if (!requirements.hasRequirements()) {
            return RedeemRequirementResult.ALLOWED;
        }
        if (context == GemRedeemEvent.RedeemContext.FULL_SET) {
            if (requirements.isAllowRedeemAll()) {
                return RedeemRequirementResult.ALLOWED;
            }
            return deniedRequirement(requirements, "command.redeem.requirements_redeemall_blocked", "gem",
                    def.getGemKey());
        }

        Map<String, List<UUID>> heldGemIds = collectHeldGemIds(player, targetGemId);
        Map<String, Integer> redeemedCounts = normalizedRedeemedCounts(player);
        RedeemRequirementResult lastDenied = null;

        for (RedeemRecipe recipe : requirements.getRecipes()) {
            RedeemRequirementResult result = evaluateRedeemRecipe(requirements, recipe, heldGemIds, redeemedCounts,
                    targetGemId);
            if (result.isAllowed()) {
                return result;
            }
            lastDenied = result;
        }
        return lastDenied != null ? lastDenied
                : deniedRequirement(requirements, "command.redeem.requirements_missing_held", "gem",
                        def.getGemKey());
    }

    private RedeemRequirementResult evaluateRedeemRecipe(RedeemRequirements requirements, RedeemRecipe recipe,
            Map<String, List<UUID>> heldGemIds, Map<String, Integer> redeemedCounts, UUID targetGemId) {
        Set<UUID> usedGemIds = new HashSet<>();
        for (RedeemIngredient ingredient : recipe.getRequiresHeld()) {
            if (!reserveHeldIngredient(heldGemIds, usedGemIds, ingredient)) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_held",
                        ingredientPlaceholders(ingredient));
            }
        }
        for (RedeemIngredient ingredient : recipe.getRequiresRedeemed()) {
            int owned = redeemedCounts.getOrDefault(normalizeKey(ingredient.getGemKey()), 0);
            if (owned < ingredient.getAmount()) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_redeemed",
                        ingredientPlaceholders(ingredient));
            }
        }
        if (!recipe.getRequiresAny().isEmpty()) {
            boolean matched = false;
            for (String candidate : recipe.getRequiresAny()) {
                String key = normalizeKey(candidate);
                if (heldGemIds.containsKey(key) || redeemedCounts.getOrDefault(key, 0) > 0) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_any", "gem",
                        String.join(", ", recipe.getRequiresAny()));
            }
        }
        if (recipe.getRequiresCount() > 0 && !recipe.getRequiresCountFrom().isEmpty()) {
            int matched = 0;
            for (String candidate : recipe.getRequiresCountFrom()) {
                String key = normalizeKey(candidate);
                if (heldGemIds.containsKey(key) || redeemedCounts.getOrDefault(key, 0) > 0) {
                    matched++;
                }
            }
            if (matched < recipe.getRequiresCount()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(recipe.getRequiresCount()));
                placeholders.put("gems", String.join(", ", recipe.getRequiresCountFrom()));
                return deniedRequirement(requirements, "command.redeem.requirements_missing_count", placeholders);
            }
        }

        List<UUID> consumedGemIds = new ArrayList<>();
        for (RedeemIngredient ingredient : recipe.getConsumes()) {
            List<UUID> selected = reserveIngredient(heldGemIds, usedGemIds, ingredient);
            if (selected.size() < ingredient.getAmount()) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_consumed",
                        ingredientPlaceholders(ingredient));
            }
            for (UUID consumedGemId : selected) {
                if (!consumedGemId.equals(targetGemId)) {
                    consumedGemIds.add(consumedGemId);
                }
            }
        }
        return RedeemRequirementResult.allowed(consumedGemIds, recipe);
    }

    private boolean reserveHeldIngredient(Map<String, List<UUID>> heldGemIds, Set<UUID> usedGemIds,
            RedeemIngredient ingredient) {
        return reserveIngredient(heldGemIds, usedGemIds, ingredient).size() == ingredient.getAmount();
    }

    private List<UUID> reserveIngredient(Map<String, List<UUID>> heldGemIds, Set<UUID> usedGemIds,
            RedeemIngredient ingredient) {
        List<UUID> ids = heldGemIds.getOrDefault(normalizeKey(ingredient.getGemKey()), Collections.emptyList());
        List<UUID> selected = new ArrayList<>();
        for (UUID id : ids) {
            if (usedGemIds.contains(id)) {
                continue;
            }
            selected.add(id);
            usedGemIds.add(id);
            if (selected.size() >= ingredient.getAmount()) {
                break;
            }
        }
        return selected;
    }

    private Map<String, String> ingredientPlaceholders(RedeemIngredient ingredient) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem", ingredient.getGemKey());
        placeholders.put("amount", String.valueOf(ingredient.getAmount()));
        return placeholders;
    }

    private RedeemRequirementResult deniedRequirement(RedeemRequirements requirements, String fallbackKey,
            String placeholderKey, String placeholderValue) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put(placeholderKey, placeholderValue);
        return deniedRequirement(requirements, fallbackKey, placeholders);
    }

    private RedeemRequirementResult deniedRequirement(RedeemRequirements requirements, String fallbackKey,
            Map<String, String> placeholders) {
        String custom = requirements.getFailureMessage();
        if (custom != null && !custom.trim().isEmpty()) {
            return RedeemRequirementResult.denied(custom, false, placeholders);
        }
        return RedeemRequirementResult.denied(fallbackKey, true, placeholders);
    }

    private void sendRedeemRequirementFailure(Player player, RedeemRequirementResult result) {
        if (player == null || result == null || result.isAllowed()) {
            return;
        }
        if (result.isMessageLanguageKey()) {
            languageManager.sendMessage(player, result.getMessage(), result.getPlaceholders());
        } else {
            Map<String, String> placeholders = new HashMap<>(result.getPlaceholders());
            placeholders.putIfAbsent("prefix", languageManager.getMessage("prefix"));
            player.sendMessage(ColorUtils.translateColorCodes(languageManager.formatText(result.getMessage(), placeholders)));
        }
    }

    private void consumeRequirementGems(Player player, RedeemRequirementResult result) {
        if (player == null || result == null || result.getConsumedGemIds().isEmpty()) {
            return;
        }
        Set<UUID> uniqueConsumed = new LinkedHashSet<>(result.getConsumedGemIds());
        for (UUID consumedGemId : uniqueConsumed) {
            stateManager.removeGemItemFromInventory(player, consumedGemId);
            stateManager.getGemUuidToHolder().remove(consumedGemId);
            placementManager.randomPlaceGem(consumedGemId);
        }
        recalculateGrants(player);
    }

    private Map<String, List<UUID>> collectHeldGemIds(Player player, UUID targetGemId) {
        Map<String, List<UUID>> result = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        if (player == null || player.getInventory() == null) {
            addHeldGemId(result, seen, targetGemId);
            return result;
        }
        collectHeldGemIdsFromItems(result, seen, player.getInventory().getContents());
        ItemStack offHand = player.getInventory().getItemInOffHand();
        collectHeldGemIdsFromItems(result, seen, new ItemStack[] { offHand });
        addHeldGemId(result, seen, targetGemId);
        return result;
    }

    private void collectHeldGemIdsFromItems(Map<String, List<UUID>> result, Set<UUID> seen, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (!stateManager.isRuleGem(item)) {
                continue;
            }
            UUID id = stateManager.getGemUUID(item);
            addHeldGemId(result, seen, id);
        }
    }

    private void addHeldGemId(Map<String, List<UUID>> result, Set<UUID> seen, UUID id) {
        if (id == null || !seen.add(id)) {
            return;
        }
        String key = stateManager.getGemKey(id);
        if (key == null || key.isEmpty()) {
            return;
        }
        result.computeIfAbsent(normalizeKey(key), ignored -> new ArrayList<>()).add(id);
    }

    private Map<String, Integer> normalizedRedeemedCounts(Player player) {
        Map<String, Integer> result = new HashMap<>();
        if (player == null) {
            return result;
        }
        Map<String, Integer> ownedCounts = permissionManager.getOwnerKeyCount().get(player.getUniqueId());
        if (ownedCounts != null) {
            for (Map.Entry<String, Integer> entry : ownedCounts.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    result.put(normalizeKey(entry.getKey()), entry.getValue());
                }
            }
        }
        Set<String> redeemed = permissionManager.getPlayerUuidToRedeemedKeys().get(player.getUniqueId());
        if (redeemed != null) {
            for (String key : redeemed) {
                result.putIfAbsent(normalizeKey(key), 1);
            }
        }
        return result;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
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
            p.sendTitle(org.cubexmc.utils.ColorUtils.translateColorCodes(languageManager.formatText(title.get(0), ph)), null, 10, 70, 20);
        } else {
            String l1 = languageManager.formatText(title.get(0), ph);
            String l2 = languageManager.formatText(title.get(1), ph);
            p.sendTitle(org.cubexmc.utils.ColorUtils.translateColorCodes(l1),
                    org.cubexmc.utils.ColorUtils.translateColorCodes(l2), 10, 70, 20);
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
        new org.cubexmc.view.GemStatusView(stateManager, languageManager)
            .sendStatus(sender, gemParser.getRequiredCount(), stateManager.getPlacedCount(), stateManager.getHeldCount());
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
