package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.cubexmc.RuleGems;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PendingRevoke;
import org.cubexmc.model.PowerStructure;

/**
 * 宝石权限管理器 - 负责权限的授予和撤销
 * 包括：Bukkit 权限附件、Vault 权限组、归属计数、离线撤销队列、
 * PowerStructureManager 集成、inventory_grants 模式等
 */
public class GemPermissionManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    private final RuleGems plugin;
    private final GameplayConfig gameplayConfig;
    private final GemStateManager stateManager;
    private HistoryLogger historyLogger;
    private GemAllowanceManager allowanceManager;

    // 归属：按 gemId 记录当前兑换归属玩家
    private final Map<UUID, UUID> gemIdToRedeemer = new ConcurrentHashMap<>();
    // 玩家已兑换的 gemKey 集合
    private final Map<UUID, Set<String>> playerUuidToRedeemedKeys = new ConcurrentHashMap<>();
    // 玩家对每个 gemKey 的归属计数
    private final Map<UUID, Map<String, Integer>> ownerKeyCount = new ConcurrentHashMap<>();
    // 当前 inventory_grants 生效的 key
    private final Map<UUID, Set<String>> playerActiveHeldKeys = new ConcurrentHashMap<>();
    // 权限附件
    private final Map<UUID, PermissionAttachment> invAttachments = new HashMap<>();
    private final Map<UUID, PermissionAttachment> redeemAttachments = new HashMap<>();
    // 全套拥有者
    private UUID fullSetOwner = null;
    // 离线撤销队列（合并 permissions / groups / keys / effects）
    private final Map<UUID, PendingRevoke> pendingRevokes = new ConcurrentHashMap<>();
    // 玩家手动关闭能力的宝石集合 (UUID -> Set<gemKey>)
    private final Map<UUID, Set<String>> toggledOffGems = new ConcurrentHashMap<>();
    // 阶梯式收集组（UUID -> 已由 RuleGems 应用的组）
    private final Map<UUID, Set<String>> collectThresholdGroups = new ConcurrentHashMap<>();

    // 保存回调
    private Runnable saveCallback;

    public GemPermissionManager(RuleGems plugin, GameplayConfig gameplayConfig, GemStateManager stateManager) {
        this.plugin = plugin;
        this.gameplayConfig = gameplayConfig;
        this.stateManager = stateManager;
    }

    // ==================== 依赖注入 ====================

    public void setHistoryLogger(HistoryLogger historyLogger) {
        this.historyLogger = historyLogger;
    }

    public void setSaveCallback(Runnable callback) {
        this.saveCallback = callback;
    }

    public void setAllowanceManager(GemAllowanceManager allowanceManager) {
        this.allowanceManager = allowanceManager;
    }

    private void save() {
        if (saveCallback != null)
            saveCallback.run();
    }

    /**
     * 懒获取 PowerStructureManager（通过 plugin 引用避免循环依赖）。
     * 正常运行时该实例应始终存在；保留 null 兼容仅用于防御性启动阶段和隔离单测。
     */
    private PowerStructureManager getPSM() {
        return plugin.getPowerStructureManager();
    }

    // ==================== 状态访问器 ====================

    public Map<UUID, UUID> getGemIdToRedeemer() {
        return gemIdToRedeemer;
    }

    public Map<UUID, Set<String>> getPlayerUuidToRedeemedKeys() {
        return playerUuidToRedeemedKeys;
    }

    public Map<UUID, Map<String, Integer>> getOwnerKeyCount() {
        return ownerKeyCount;
    }

    public Map<UUID, Set<String>> getPlayerActiveHeldKeys() {
        return playerActiveHeldKeys;
    }

    public Map<UUID, Set<String>> getPendingPermRevokes() {
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Map.Entry<UUID, PendingRevoke> e : pendingRevokes.entrySet()) {
            if (!e.getValue().getPermissions().isEmpty())
                result.put(e.getKey(), e.getValue().getPermissions());
        }
        return result;
    }

    public Map<UUID, Set<String>> getPendingGroupRevokes() {
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Map.Entry<UUID, PendingRevoke> e : pendingRevokes.entrySet()) {
            if (!e.getValue().getGroups().isEmpty())
                result.put(e.getKey(), e.getValue().getGroups());
        }
        return result;
    }

    public Map<UUID, PermissionAttachment> getInvAttachments() {
        return invAttachments;
    }

    public Map<UUID, PermissionAttachment> getRedeemAttachments() {
        return redeemAttachments;
    }

    public Map<UUID, Set<String>> getPendingKeyRevokes() {
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Map.Entry<UUID, PendingRevoke> e : pendingRevokes.entrySet()) {
            if (!e.getValue().getKeys().isEmpty())
                result.put(e.getKey(), e.getValue().getKeys());
        }
        return result;
    }

    public Map<UUID, Set<String>> getPendingEffectRevokes() {
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Map.Entry<UUID, PendingRevoke> e : pendingRevokes.entrySet()) {
            if (!e.getValue().getEffects().isEmpty())
                result.put(e.getKey(), e.getValue().getEffects());
        }
        return result;
    }

    public UUID getFullSetOwner() {
        return fullSetOwner;
    }

    public void setFullSetOwner(UUID owner) {
        this.fullSetOwner = owner;
    }

    // ==================== 宝石能力开关 (Toggle) ====================

    /**
     * 检查某个玩家的某个宝石能力是否被手动关闭
     */
    public boolean isGemToggledOff(UUID playerUuid, String gemKey) {
        if (playerUuid == null || gemKey == null) return false;
        Set<String> toggledOff = toggledOffGems.get(playerUuid);
        return toggledOff != null && toggledOff.contains(gemKey.toLowerCase(ROOT_LOCALE));
    }

    /**
     * 手动开启或关闭玩家的某个宝石能力
     */
    public void toggleGemPower(Player player, String gemKey, boolean enabled) {
        if (player == null || gemKey == null) return;
        UUID uid = player.getUniqueId();
        String normalizedKey = gemKey.toLowerCase(ROOT_LOCALE);
        
        Set<String> toggledOff = toggledOffGems.computeIfAbsent(uid, k -> new HashSet<>());
        boolean currentlyOff = toggledOff.contains(normalizedKey);
        
        if (enabled && currentlyOff) {
            // 开启
            toggledOff.remove(normalizedKey);
            GemDefinition def = stateManager.findGemDefinition(gemKey);
            if (def != null && def.getPowerStructure() != null) {
                PowerStructureManager psm = getPSM();
                if (psm != null) {
                    psm.applyStructure(player, def.getPowerStructure(), "gem_redeem", gemKey, false);
                }
            }
            save();
        } else if (!enabled && !currentlyOff) {
            // 关闭
            toggledOff.add(normalizedKey);
            GemDefinition def = stateManager.findGemDefinition(gemKey);
            if (def != null && def.getPowerStructure() != null) {
                PowerStructureManager psm = getPSM();
                if (psm != null) {
                    psm.removeStructure(player, def.getPowerStructure(), "gem_redeem", gemKey);
                }
            }
            save();
        }
    }

    // ==================== 清理方法 ====================

    public void clearAll() {
        gemIdToRedeemer.clear();
        playerUuidToRedeemedKeys.clear();
        ownerKeyCount.clear();
        playerActiveHeldKeys.clear();
        pendingRevokes.clear();
        fullSetOwner = null;
        toggledOffGems.clear();
        collectThresholdGroups.clear();
    }

    /**
     * 清理所有运行时权限状态，用于 scatter/reload 前确保在线玩家不会保留旧权力。
     */
    public void clearRuntimeState() {
        PowerStructureManager psm = getPSM();
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearRuntimeState(player, psm);
        }
        invAttachments.clear();
        redeemAttachments.clear();
        clearAll();
    }

    private void clearRuntimeState(Player player, PowerStructureManager psm) {
        if (player == null) {
            return;
        }
        if (psm != null) {
            psm.clearNamespace(player, "gem_redeem");
            psm.clearNamespace(player, "gem_appoint");
            psm.clearNamespace(player, "gem_redeem_all");
            psm.clearNamespace(player, "gem_inv");
        }
        clearCollectThresholdGroups(player);

        PermissionAttachment invAtt = invAttachments.remove(player.getUniqueId());
        if (invAtt != null) {
            try {
                player.removeAttachment(invAtt);
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to remove inv permission attachment: " + e.getMessage());
            }
        }

        PermissionAttachment redAtt = redeemAttachments.remove(player.getUniqueId());
        if (redAtt != null) {
            try {
                player.removeAttachment(redAtt);
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to remove redeem permission attachment: " + e.getMessage());
            }
        }

        try {
            player.recalculatePermissions();
        } catch (Throwable e) {
            plugin.getLogger().fine("Failed to recalculate permissions during runtime state clear: " + e.getMessage());
        }
    }

    /**
     * 散落专用的状态重置。
     *
     * <p>{@link #clearRuntimeState()} 只会剥夺在线玩家的权限；离线统治者通过 redeem 获得的
     * 持久化权限/Vault 组(LuckPerms)不会被清除，散落后仍然保留，与"权力流转"主题冲突。
     * 本方法在清空前先按当前归属为离线统治者计算待撤销项，清空后重新入队并持久化，
     * 这些玩家下次登录时会被 {@link #applyPendingRevokesIfAny} 剥夺。</p>
     *
     * <p>注意：仅供 scatter 调用。reload/重启走 {@code clearRuntimeState()}，
     * 因为它们随后会立刻从磁盘重载归属并恢复权限，不应入队撤销。</p>
     */
    public void resetForScatter() {
        Map<UUID, PendingRevoke> offlinePending = computeOfflineRulerPending();
        clearRuntimeState(); // 在线玩家立即剥夺；clearAll() 会清空 pendingRevokes
        if (!offlinePending.isEmpty()) {
            // clearAll() 后 pendingRevokes 为空，直接放入即可。
            pendingRevokes.putAll(offlinePending);
            save();
        }
    }

    /**
     * 基于当前归属，为离线统治者构建待撤销项(权限/Vault 组/药水效果/阶梯组)。
     * 在线玩家由 clearRuntimeState 直接处理，这里跳过。
     */
    private Map<UUID, PendingRevoke> computeOfflineRulerPending() {
        Map<UUID, PendingRevoke> result = new HashMap<>();
        Map<Integer, String> thresholds = gameplayConfig.getGemCollectThresholdGroups();

        for (Map.Entry<UUID, Map<String, Integer>> entry : ownerKeyCount.entrySet()) {
            UUID owner = entry.getKey();
            if (owner == null) {
                continue;
            }
            Player online = Bukkit.getPlayer(owner);
            if (online != null && online.isOnline()) {
                continue; // 在线玩家已由 clearRuntimeState 处理
            }
            Map<String, Integer> counts = entry.getValue();
            if (counts == null) {
                continue;
            }
            for (Map.Entry<String, Integer> keyCount : counts.entrySet()) {
                if (keyCount.getValue() == null || keyCount.getValue() <= 0) {
                    continue;
                }
                GemDefinition def = stateManager.findGemDefinition(keyCount.getKey());
                if (def == null) {
                    continue;
                }
                PendingRevoke pr = result.computeIfAbsent(owner, k -> new PendingRevoke());
                if (def.getPermissions() != null) {
                    pr.getPermissions().addAll(def.getPermissions());
                }
                pr.getPermissions().addAll(getAppointPermissionNodes(def));
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty()) {
                    pr.getGroups().add(def.getVaultGroup());
                }
                if (def.getEffects() != null) {
                    for (EffectConfig ec : def.getEffects()) {
                        if (ec != null && ec.getEffectType() != null) {
                            pr.getEffects().add(ec.getEffectType().getName());
                        }
                    }
                }
                pr.getKeys().add(keyCount.getKey().toLowerCase(ROOT_LOCALE));
            }
            // 阶梯收集组也是持久化的 Vault 组；离线无法判断已应用哪些，撤销全部已配置项以求安全(移除未持有组为空操作)。
            if (result.containsKey(owner) && thresholds != null && !thresholds.isEmpty()) {
                result.get(owner).getGroups().addAll(thresholds.values());
            }
        }

        if (fullSetOwner != null) {
            Player online = Bukkit.getPlayer(fullSetOwner);
            if (online == null || !online.isOnline()) {
                PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
                if (redeemAllPower != null && redeemAllPower.hasAnyContent()) {
                    PendingRevoke pr = result.computeIfAbsent(fullSetOwner, k -> new PendingRevoke());
                    if (redeemAllPower.getPermissions() != null) {
                        pr.getPermissions().addAll(redeemAllPower.getPermissions());
                    }
                    if (redeemAllPower.getVaultGroups() != null) {
                        pr.getGroups().addAll(redeemAllPower.getVaultGroups());
                    }
                }
            }
        }
        return result;
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 从 gemsData 加载兑换归属、已兑换 key、全套拥有者和离线撤销队列。
     */
    public void loadData(FileConfiguration gemsData) {
        // 已兑换的 gem key 列表
        ConfigurationSection redeemedSection = gemsData.getConfigurationSection("redeemed");
        if (redeemedSection != null) {
            for (String playerUuidStr : redeemedSection.getKeys(false)) {
                UUID pu;
                try {
                    pu = UUID.fromString(playerUuidStr);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping corrupted player UUID in redeemed data: " + playerUuidStr);
                    continue;
                }
                List<String> list = redeemedSection.getStringList(playerUuidStr);
                if (list != null) {
                    playerUuidToRedeemedKeys.put(pu, new HashSet<>(list));
                }
            }
        }
        // 兑换归属（新结构按 gemId；旧结构按 key）
        ConfigurationSection ownerById = gemsData.getConfigurationSection("redeem_owner_by_id");
        if (ownerById != null) {
            for (String gid : ownerById.getKeys(false)) {
                try {
                    UUID gem = UUID.fromString(gid);
                    UUID player = UUID.fromString(ownerById.getString(gid));
                    gemIdToRedeemer.put(gem, player);
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Failed to parse redeem owner by gem id '" + gid + "': " + e.getMessage());
                }
            }
        } else {
            ConfigurationSection ownerSec = gemsData.getConfigurationSection("redeem_owner");
            if (ownerSec != null) {
                Map<UUID, String> gemUuidToKey = stateManager.getGemUuidToKey();
                for (String gemKey : ownerSec.getKeys(false)) {
                    String uuidStr = ownerSec.getString(gemKey);
                    try {
                        UUID player = UUID.fromString(uuidStr);
                        for (Map.Entry<UUID, String> e : gemUuidToKey.entrySet()) {
                            if (e.getValue() != null && e.getValue().equalsIgnoreCase(gemKey)) {
                                gemIdToRedeemer.put(e.getKey(), player);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning(
                                "Failed to parse legacy redeem owner for gem key '" + gemKey + "': " + e.getMessage());
                    }
                }
            }
        }
        // 全套拥有者
        ConfigurationSection fso = gemsData.getConfigurationSection("full_set_owner");
        if (fso != null) {
            String u = fso.getString("uuid");
            try {
                fullSetOwner = UUID.fromString(u);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse full set owner UUID '" + u + "': " + e.getMessage());
            }
        }
        
        // 已关闭能力的宝石列表
        ConfigurationSection toggledOffSection = gemsData.getConfigurationSection("toggled_off_gems");
        if (toggledOffSection != null) {
            for (String playerUuidStr : toggledOffSection.getKeys(false)) {
                try {
                    UUID pu = UUID.fromString(playerUuidStr);
                    List<String> list = toggledOffSection.getStringList(playerUuidStr);
                    if (list != null && !list.isEmpty()) {
                        toggledOffGems.put(pu, new HashSet<>(list));
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping corrupted player UUID in toggled_off_gems data: " + playerUuidStr);
                }
            }
        }

        // 离线撤销队列
        loadPendingRevokes(gemsData);
    }

    /**
     * 基于已保存的 gem 归属重建 ownerKeyCount。
     *
     * <p>ownerKeyCount 是运行时缓存，不会持久化；插件重启或重载后需要从
     * redeem_owner_by_id 和当前 gemId -> gemKey 映射重新计算。</p>
     */
    public void rebuildOwnerKeyCountFromOwnership() {
        ownerKeyCount.clear();

        for (Map.Entry<UUID, UUID> entry : gemIdToRedeemer.entrySet()) {
            UUID gemId = entry.getKey();
            UUID owner = entry.getValue();
            if (gemId == null || owner == null)
                continue;

            String key = stateManager.getGemUuidToKey().get(gemId);
            if (key == null || key.trim().isEmpty())
                continue;

            String normalizedKey = key.toLowerCase(ROOT_LOCALE);
            ownerKeyCount.computeIfAbsent(owner, unused -> new HashMap<>())
                    .merge(normalizedKey, 1, Integer::sum);
        }
    }

    /**
     * 为单个在线玩家恢复已兑换宝石、appoint 和 full-set 权限。
     */
    public void restoreRedeemedPermissions(Player player) {
        if (player == null)
            return;

        UUID playerId = player.getUniqueId();
        PowerStructureManager psm = getPSM();

        if (psm != null) {
            psm.clearNamespace(player, "gem_redeem");
            psm.clearNamespace(player, "gem_appoint");
            psm.clearNamespace(player, "gem_redeem_all");
        }
        clearCollectThresholdGroups(player);

        Map<String, Integer> ownedKeys = ownerKeyCount.getOrDefault(playerId, Collections.emptyMap());
        for (Map.Entry<String, Integer> entry : ownedKeys.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0)
                continue;

            String key = entry.getKey();
            GemDefinition def = stateManager.findGemDefinition(key);
            if (def == null)
                continue;

            if (psm != null && def.getPowerStructure() != null) {
                if (!isGemToggledOff(playerId, key)) {
                    psm.applyStructure(player, def.getPowerStructure(), "gem_redeem", key, false);
                }
            }
            grantAppointPermissions(player, def);
        }

        if (fullSetOwner != null && fullSetOwner.equals(playerId)) {
            PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
            if (redeemAllPower != null && redeemAllPower.hasAnyContent()) {
                if (psm != null) {
                    psm.applyStructure(player, redeemAllPower, "gem_redeem_all", "full_set", false);
                } else if (redeemAllPower.getPermissions() != null) {
                    grantRedeemPermissions(player, redeemAllPower.getPermissions());
                }
            }
        }

        reconcileCollectThresholdGroups(player);
        player.recalculatePermissions();
    }

    /**
     * 重建归属缓存，并为所有在线玩家恢复已兑换权限。
     */
    public void restoreRedeemedPermissionsForOnlinePlayers() {
        rebuildOwnerKeyCountFromOwnership();
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreRedeemedPermissions(player);
        }
    }

    /**
     * 从 gemsData 加载所有离线撤销队列到统一的 pendingRevokes 映射中。
     */
    private void loadPendingRevokes(FileConfiguration gemsData) {
        String[] categories = { "permissions", "groups", "keys", "effects" };
        for (String cat : categories) {
            ConfigurationSection section = gemsData.getConfigurationSection("pending_revokes." + cat);
            if (section == null)
                continue;
            for (String pid : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(pid);
                    List<String> list = section.getStringList(pid);
                    if (list == null || list.isEmpty())
                        continue;
                    PendingRevoke pr = pendingRevokes.computeIfAbsent(id, k -> new PendingRevoke());
                    Set<String> target = switch (cat) {
                        case "permissions" -> pr.getPermissions();
                        case "groups" -> pr.getGroups();
                        case "keys" -> pr.getKeys();
                        case "effects" -> pr.getEffects();
                        default -> null;
                    };
                    if (target != null)
                        target.addAll(list);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load pending revoke entry for '" + pid
                            + "' at path 'pending_revokes." + cat + "': " + e.getMessage());
                }
            }
        }
    }

    /**
     * 将将要保存的数据结构提取到快照中，用于线程安全的异步落盘
     */
    public void populateSaveSnapshot(Map<String, Object> snapshot) {
        for (Map.Entry<UUID, Set<String>> e : playerUuidToRedeemedKeys.entrySet()) {
            snapshot.put("redeemed." + e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<UUID, UUID> e : gemIdToRedeemer.entrySet()) {
            snapshot.put("redeem_owner_by_id." + e.getKey().toString(), e.getValue().toString());
        }
        if (fullSetOwner != null) {
            snapshot.put("full_set_owner.uuid", fullSetOwner.toString());
        }

        for (Map.Entry<UUID, Set<String>> e : toggledOffGems.entrySet()) {
            if (!e.getValue().isEmpty()) {
                snapshot.put("toggled_off_gems." + e.getKey().toString(), new ArrayList<>(e.getValue()));
            }
        }

        for (Map.Entry<UUID, PendingRevoke> e : pendingRevokes.entrySet()) {
            String uuid = e.getKey().toString();
            PendingRevoke pr = e.getValue();
            if (!pr.getPermissions().isEmpty())
                snapshot.put("pending_revokes.permissions." + uuid, new ArrayList<>(pr.getPermissions()));
            if (!pr.getGroups().isEmpty())
                snapshot.put("pending_revokes.groups." + uuid, new ArrayList<>(pr.getGroups()));
            if (!pr.getKeys().isEmpty())
                snapshot.put("pending_revokes.keys." + uuid, new ArrayList<>(pr.getKeys()));
            if (!pr.getEffects().isEmpty())
                snapshot.put("pending_revokes.effects." + uuid, new ArrayList<>(pr.getEffects()));
        }
    }

    // ==================== 基础权限操作 ====================

    /**
     * 授予权限（使用 redeemAttachments）
     */
    public void grantPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty())
            return;
        PermissionAttachment attachment = redeemAttachments.computeIfAbsent(
                player.getUniqueId(), p -> player.addAttachment(plugin));
        for (String node : perms) {
            if (node == null || node.trim().isEmpty())
                continue;
            attachment.setPermission(node, true);
        }
        player.recalculatePermissions();
    }

    /**
     * 兑换场景授予权限（同时写入 Vault）
     */
    public void grantRedeemPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty())
            return;
        grantPermissions(player, perms);
        for (String node : perms) {
            if (node == null || node.trim().isEmpty())
                continue;
            plugin.getPermissionProvider().addPermission(player, node);
        }
    }

    /**
     * 撤销权限节点（List 版本 - 仅 redeemAttachments + Vault）
     */
    public void revokeNodes(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty())
            return;
        PermissionAttachment attachment = redeemAttachments.get(player.getUniqueId());
        if (attachment != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty())
                    continue;
                attachment.unsetPermission(node);
            }
        }
        for (String node : perms) {
            if (node == null || node.trim().isEmpty())
                continue;
            plugin.getPermissionProvider().removePermission(player, node);
        }
        player.recalculatePermissions();
    }

    /**
     * 撤销权限节点（Collection 版本 - 同时清除 inv + redeem attachments + Vault）
     * 用于 applyPendingRevokesIfAny 和 revokeAllPlayerPermissions 等场景
     */
    public void revokeNodesAll(Player player, Collection<String> nodes) {
        if (nodes == null || nodes.isEmpty())
            return;
        PermissionAttachment i = invAttachments.get(player.getUniqueId());
        if (i != null) {
            for (String n : nodes) {
                try {
                    i.unsetPermission(n);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to unset inv permission '" + n + "': " + e.getMessage());
                }
            }
        }
        PermissionAttachment r = redeemAttachments.get(player.getUniqueId());
        if (r != null) {
            for (String n : nodes) {
                try {
                    r.unsetPermission(n);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to unset redeem permission '" + n + "': " + e.getMessage());
                }
            }
        }
        for (String n : nodes) {
            plugin.getPermissionProvider().removePermission(player, n);
        }
    }

    // ==================== 离线撤销队列 ====================

    /**
     * 队列离线权限/组撤销
     */
    public void queueOfflineRevokes(UUID user, Collection<String> perms, Collection<String> groups) {
        if (user == null)
            return;
        PendingRevoke pr = pendingRevokes.computeIfAbsent(user, k -> new PendingRevoke());
        if (perms != null && !perms.isEmpty()) {
            for (String p : perms) {
                if (p != null && !p.trim().isEmpty())
                    pr.getPermissions().add(p);
            }
        }
        if (groups != null && !groups.isEmpty()) {
            for (String g : groups) {
                if (g != null && !g.trim().isEmpty())
                    pr.getGroups().add(g);
            }
        }
        save();
    }

    /**
     * 将药水效果类型加入离线待撤销队列
     */
    public void queueOfflineEffectRevokes(UUID user, List<EffectConfig> effects) {
        if (user == null || effects == null || effects.isEmpty())
            return;
        PendingRevoke pr = pendingRevokes.computeIfAbsent(user, k -> new PendingRevoke());
        for (EffectConfig ec : effects) {
            if (ec != null && ec.getEffectType() != null) {
                pr.getEffects().add(ec.getEffectType().getName());
            }
        }
        save();
    }

    /**
     * 应用待处理的离线撤销（完整版：权限、Vault 组、药水效果、key revokes）
     */
    public void applyPendingRevokesIfAny(Player player) {
        if (player == null)
            return;
        UUID uid = player.getUniqueId();

        PendingRevoke pr = pendingRevokes.remove(uid);
        if (pr == null || pr.isEmpty())
            return;

        boolean changed = false;

        Set<String> perms = pr.getPermissions();
        if (!perms.isEmpty()) {
            revokeNodesAll(player, perms);
            changed = true;
        }

        if (!pr.getKeys().isEmpty()) {
            changed = true;
        }

        Set<String> groups = pr.getGroups();
        if (!groups.isEmpty()) {
            for (String g : groups) {
                plugin.getPermissionProvider().removeGroup(player, g);
            }
            changed = true;
        }

        // 移除离线期间待撤销的药水效果
        Set<String> effectTypes = pr.getEffects();
        if (!effectTypes.isEmpty()) {
            for (String typeName : effectTypes) {
                try {
                    org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(typeName);
                    if (type != null) {
                        player.removePotionEffect(type);
                    }
                } catch (Exception e) {
                    plugin.getLogger()
                            .fine("Failed to remove pending potion effect '" + typeName + "': " + e.getMessage());
                }
            }
            changed = true;
        }

        if (changed) {
            try {
                player.recalculatePermissions();
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to recalculate permissions after pending revokes: " + e.getMessage());
            }
            save();
        }
    }

    // ==================== 归属计数（PSM 集成）====================

    /**
     * 增加归属计数（0→1 时通过 PowerStructureManager 发放权限结构 + appoint 权限）
     */
    public void incrementOwnerKeyCount(UUID owner, String key, GemDefinition def) {
        if (owner == null || key == null)
            return;
        Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = before + 1;
        map.put(key, after);
        if (before == 0 && def != null) {
            // 0→1：首次拥有该类型宝石，发放权限
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                PowerStructureManager psm = getPSM();
                if (psm != null && def.getPowerStructure() != null) {
                    if (!isGemToggledOff(owner, key)) {
                        psm.applyStructure(p, def.getPowerStructure(), "gem_redeem", key, false);
                    }
                }
                grantAppointPermissions(p, def);
                reconcileCollectThresholdGroups(p);
                try {
                    p.recalculatePermissions();
                } catch (Throwable e) {
                    plugin.getLogger().fine(
                            "Failed to recalculate permissions after incrementing owner key count: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 减少归属计数（1→0 时通过 PowerStructureManager 撤销权限结构）
     * 处理在线/离线两种情况，包括级联撤销和额度清理
     */
    public void decrementOwnerKeyCount(UUID owner, String key, GemDefinition def) {
        if (owner == null || key == null)
            return;
        Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = Math.max(0, before - 1);
        map.put(key, after);
        if (after == 0 && def != null) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                // 在线：使用 PSM 统一撤销权限、Vault 组和效果
                PowerStructureManager psm = getPSM();
                if (psm != null && def.getPowerStructure() != null) {
                    psm.removeStructure(p, def.getPowerStructure(), "gem_redeem", key);
                }
                revokeAppointPermissions(p, def);
                try {
                    p.recalculatePermissions();
                } catch (Throwable e) {
                    plugin.getLogger().fine(
                            "Failed to recalculate permissions after decrementing owner key count: " + e.getMessage());
                }
                if (historyLogger != null) {
                    LanguageManager lm = plugin.getLanguageManager();
                    historyLogger.logPermissionRevoke(
                            owner.toString(), p.getName(), key, def.getDisplayName(),
                            def.getPermissions(), def.getVaultGroup(),
                            lm != null ? lm.getMessage("history_reason.ownership_lost")
                                    : "Ownership change: lost last gem of this type");
                }
            } else {
                // 离线：权限与 Vault 组放入 pending 队列
                List<String> permsToRevoke = new ArrayList<>();
                if (def.getPermissions() != null)
                    permsToRevoke.addAll(def.getPermissions());
                // appoint 权限节点也需要撤销
                permsToRevoke.addAll(getAppointPermissionNodes(def));
                // 记录待撤销的 gemKey 用于 rulers 显示过滤
                pendingRevokes.computeIfAbsent(owner, k -> new PendingRevoke()).getKeys().add(key);
                queueOfflineRevokes(owner,
                        permsToRevoke,
                        (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty())
                                ? Collections.singleton(def.getVaultGroup())
                                : Collections.emptySet());

                // 离线撤销：药水效果
                queueOfflineEffectRevokes(owner, def.getEffects());
                queueOfflineThresholdGroupRevokes(owner);

                // === 即时处理（不需要等待上线）===

                // 1. 触发 appointee 级联撤销
                if (plugin.getFeatureManager() != null) {
                    org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager()
                            .getAppointFeature();
                    if (appointFeature != null && appointFeature.isEnabled()) {
                        PowerStructure power = def.getPowerStructure();
                        if (power != null && power.getAppoints() != null) {
                            for (String appointKey : power.getAppoints().keySet()) {
                                appointFeature.onAppointerLostPermission(owner, appointKey);
                            }
                        }
                    }
                }

                // 2. 清除该宝石关联的 allowed_commands 额度
                if (allowanceManager != null) {
                    Map<UUID, Map<String, Integer>> perRed = allowanceManager.getPlayerGemRedeemUses().get(owner);
                    if (perRed != null) {
                        for (Map.Entry<UUID, UUID> entry : gemIdToRedeemer.entrySet()) {
                            if (owner.equals(entry.getValue())) {
                                UUID gemId = entry.getKey();
                                String gemKey = stateManager.getGemUuidToKey().get(gemId);
                                if (key.equalsIgnoreCase(gemKey)) {
                                    perRed.remove(gemId);
                                }
                            }
                        }
                        if (perRed.isEmpty())
                            allowanceManager.getPlayerGemRedeemUses().remove(owner);
                    }
                }

                if (historyLogger != null) {
                    LanguageManager lm = plugin.getLanguageManager();
                    historyLogger.logPermissionRevoke(
                            owner.toString(),
                            lm != null ? lm.getMessage("player.unknown_offline") : "Unknown (offline)", key,
                            def.getDisplayName(),
                            def.getPermissions(), def.getVaultGroup(),
                            lm != null ? lm.getMessage("history_reason.ownership_lost_offline")
                                    : "Ownership change: lost last gem of this type (offline revoke)");
                }
            }
            if (p != null && p.isOnline()) {
                reconcileCollectThresholdGroups(p);
            }
        }
    }

    private void reconcileCollectThresholdGroups(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (playerId == null) {
            return;
        }
        Set<String> desired = desiredCollectThresholdGroups(playerId);
        Set<String> applied = collectThresholdGroups.computeIfAbsent(playerId, unused -> ConcurrentHashMap.newKeySet());

        for (String group : new HashSet<>(applied)) {
            if (!desired.contains(group)) {
                plugin.getPermissionProvider().removeGroup(player, group);
                applied.remove(group);
            }
        }
        for (String group : desired) {
            if (applied.add(group)) {
                plugin.getPermissionProvider().addGroup(player, group);
            }
        }
        if (applied.isEmpty()) {
            collectThresholdGroups.remove(playerId);
        }
    }

    private Set<String> desiredCollectThresholdGroups(UUID playerId) {
        Map<Integer, String> thresholds = gameplayConfig.getGemCollectThresholdGroups();
        if (thresholds == null || thresholds.isEmpty()) {
            return Collections.emptySet();
        }
        int ownedTypes = countOwnedGemTypes(playerId);
        Set<String> desired = new LinkedHashSet<>();
        for (Map.Entry<Integer, String> entry : thresholds.entrySet()) {
            if (entry.getKey() != null && entry.getKey() <= ownedTypes
                    && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                desired.add(entry.getValue().trim());
            }
        }
        return desired;
    }

    private int countOwnedGemTypes(UUID playerId) {
        Map<String, Integer> counts = ownerKeyCount.get(playerId);
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        int ownedTypes = 0;
        for (Integer count : counts.values()) {
            if (count != null && count > 0) {
                ownedTypes++;
            }
        }
        return ownedTypes;
    }

    private void clearCollectThresholdGroups(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (playerId == null) {
            return;
        }
        Set<String> applied = collectThresholdGroups.remove(playerId);
        if (applied == null || applied.isEmpty()) {
            return;
        }
        for (String group : applied) {
            plugin.getPermissionProvider().removeGroup(player, group);
        }
    }

    private void queueOfflineThresholdGroupRevokes(UUID owner) {
        Map<Integer, String> thresholds = gameplayConfig.getGemCollectThresholdGroups();
        if (thresholds == null || thresholds.isEmpty()) {
            return;
        }
        queueOfflineRevokes(owner, Collections.emptyList(), thresholds.values());
    }

    // ==================== Appoint 权限管理（PSM 集成）====================

    /**
     * 获取宝石的 appoint 权限节点列表
     */
    public List<String> getAppointPermissionNodes(GemDefinition def) {
        List<String> nodes = new ArrayList<>();
        if (def == null || def.getPowerStructure() == null)
            return nodes;
        PowerStructure power = def.getPowerStructure();
        if (power.getAppoints() != null && !power.getAppoints().isEmpty()) {
            for (String appointKey : power.getAppoints().keySet()) {
                nodes.add("rulegems.appoint." + appointKey);
            }
        }
        return nodes;
    }

    /**
     * 授予宝石关联的 appoint 权限（通过 PSM）
     */
    public void grantAppointPermissions(Player player, GemDefinition def) {
        List<String> appointPerms = getAppointPermissionNodes(def);
        if (!appointPerms.isEmpty()) {
            PowerStructure appointPower = new PowerStructure();
            appointPower.setPermissions(appointPerms);
            PowerStructureManager psm = getPSM();
            if (psm != null) {
                psm.applyStructure(player, appointPower, "gem_appoint", def.getGemKey(), false);
            }
        }
    }

    /**
     * 撤销宝石关联的 appoint 权限（通过 PSM + 级联撤销）
     */
    public void revokeAppointPermissions(Player player, GemDefinition def) {
        List<String> appointPerms = getAppointPermissionNodes(def);
        if (!appointPerms.isEmpty()) {
            PowerStructure appointPower = new PowerStructure();
            appointPower.setPermissions(appointPerms);
            PowerStructureManager psm = getPSM();
            if (psm != null) {
                psm.removeStructure(player, appointPower, "gem_appoint", def.getGemKey());
            }
            // 触发级联撤销
            if (plugin.getFeatureManager() != null) {
                org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager()
                        .getAppointFeature();
                if (appointFeature != null && appointFeature.isEnabled()) {
                    for (String perm : appointPerms) {
                        String permSetKey = perm.substring("rulegems.appoint.".length());
                        appointFeature.onAppointerLostPermission(player.getUniqueId(), permSetKey);
                    }
                }
            }
        }
    }

    // ==================== 重算权限（inventory_grants 模式，PSM 集成）====================

    /**
     * 重新计算玩家的背包权限（inventory_grants 模式）
     * 使用 PowerStructureManager 管理权限的增量变化
     */
    public void recalculateGrants(Player player) {
        if (!gameplayConfig.isInventoryGrantsEnabled())
            return;

        PowerStructureManager psm = getPSM();

        // 收集当前背包中的 key
        List<String> presentKeysOrdered = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (!stateManager.isRuleGem(item))
                continue;
            UUID id = stateManager.getGemUUID(item);
            String key = stateManager.getGemUuidToKey().get(id);
            if (key == null)
                continue;
            String k = key.toLowerCase(ROOT_LOCALE);
            if (!presentKeysOrdered.contains(k))
                presentKeysOrdered.add(k);
        }

        // 基于上次选中的 active 集合优先保留，新增时再做互斥筛选
        Set<String> previouslyActive = playerActiveHeldKeys.getOrDefault(
                player.getUniqueId(), Collections.emptySet());
        Set<String> selectedKeys = new LinkedHashSet<>();
        for (String k : presentKeysOrdered) {
            if (previouslyActive.contains(k))
                selectedKeys.add(k);
        }
        boolean hasConflict = false;
        for (String k : presentKeysOrdered) {
            if (selectedKeys.contains(k))
                continue;
            if (!conflictsWithSelected(k, selectedKeys)) {
                selectedKeys.add(k);
            } else {
                hasConflict = true;
            }
        }

        if (hasConflict) {
            // 给玩家发送一个 Actionbar 提示，告知有互斥宝石
            LanguageManager lm = plugin.getLanguageManager();
            if (lm != null) {
                plugin.getEffectUtils().sendActionBar(player, lm.translateColorCodes(lm.getMessage("inventory.conflict")));
            }
        }

        // 找出需要移除/新增的 keys
        Set<String> keysToRemove = new HashSet<>(previouslyActive);
        keysToRemove.removeAll(selectedKeys);
        Set<String> keysToAdd = new HashSet<>(selectedKeys);
        keysToAdd.removeAll(previouslyActive);

        playerActiveHeldKeys.put(player.getUniqueId(), selectedKeys);

        if (psm != null) {
            // 使用 PSM 移除不再持有的宝石权限
            for (String k : keysToRemove) {
                GemDefinition def = stateManager.findGemDefinition(k);
                if (def != null && def.getPowerStructure() != null) {
                    psm.removeStructure(player, def.getPowerStructure(), "gem_inv", k);
                }
            }
            // 使用 PSM 添加新持有的宝石权限（inventory_grants 只应用权限，不应用 Vault 组和效果）
            for (String k : keysToAdd) {
                GemDefinition def = stateManager.findGemDefinition(k);
                if (def != null && def.getPowerStructure() != null) {
                    PowerStructure invPower = new PowerStructure();
                    invPower.setPermissions(def.getPermissions());
                    psm.applyStructure(player, invPower, "gem_inv", k, false);
                }
            }
        } else {
            // 回退到旧逻辑（兼容无 PSM 场景）
            Set<String> shouldHave = new HashSet<>();
            for (String k : selectedKeys) {
                GemDefinition def = stateManager.findGemDefinition(k);
                if (def == null)
                    continue;
                if (def.getPermissions() != null) {
                    for (String node : def.getPermissions()) {
                        if (node != null && !node.trim().isEmpty())
                            shouldHave.add(node);
                    }
                }
            }
            PermissionAttachment attachment = invAttachments.computeIfAbsent(
                    player.getUniqueId(), p -> player.addAttachment(plugin));
            Set<String> current = new HashSet<>(attachment.getPermissions().keySet());
            for (String node : shouldHave) {
                if (!current.contains(node))
                    attachment.setPermission(node, true);
            }
            for (String node : current) {
                if (!shouldHave.contains(node))
                    attachment.unsetPermission(node);
            }
        }
        player.recalculatePermissions();
    }

    /**
     * 检查候选 key 是否与已选择的 key 冲突
     */
    public boolean conflictsWithSelected(String candidateKey, Set<String> selectedKeys) {
        GemDefinition c = stateManager.findGemDefinition(candidateKey);
        Set<String> cm = new HashSet<>();
        if (c != null && c.getMutualExclusive() != null) {
            for (String x : c.getMutualExclusive()) {
                if (x != null)
                    cm.add(x.toLowerCase(ROOT_LOCALE));
            }
        }
        for (String s : selectedKeys) {
            if (cm.contains(s.toLowerCase(ROOT_LOCALE)))
                return true;
            GemDefinition sd = stateManager.findGemDefinition(s);
            if (sd != null && sd.getMutualExclusive() != null) {
                for (String x : sd.getMutualExclusive()) {
                    if (x != null && x.equalsIgnoreCase(candidateKey))
                        return true;
                }
            }
        }
        return false;
    }

    // ==================== 撤销所有权限（PSM 集成）====================

    /**
     * 强制撤销指定玩家的所有宝石权限、组和限次指令
     * 用于管理员干预玩家滥用权限的情况
     *
     * @param player 目标玩家
     * @return 是否成功撤销（true=该玩家有权限被撤销，false=该玩家没有任何宝石权限）
     */
    public boolean revokeAllPlayerPermissions(Player player) {
        if (player == null)
            return false;
        UUID uid = player.getUniqueId();
        boolean hadAny = false;

        PowerStructureManager psm = getPSM();

        // 1. 收集该玩家拥有的所有宝石类型并通过 PSM 撤销
        Map<String, Integer> counts = ownerKeyCount.get(uid);
        if (counts != null && !counts.isEmpty()) {
            hadAny = true;
            if (psm != null) {
                psm.clearNamespace(player, "gem_redeem");
                psm.clearNamespace(player, "gem_appoint");
                psm.clearNamespace(player, "gem_inv");
            }
            clearCollectThresholdGroups(player);
            counts.clear();
        }

        // 2. 如果该玩家是 full set owner，撤销额外权限
        if (uid.equals(fullSetOwner)) {
            hadAny = true;
            PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
            if (psm != null) {
                if (redeemAllPower != null && redeemAllPower.hasAnyContent()) {
                    psm.removeStructure(player, redeemAllPower, "gem_redeem_all", "full_set");
                }
            } else {
                if (redeemAllPower != null && redeemAllPower.getPermissions() != null) {
                    revokeNodesAll(player, redeemAllPower.getPermissions());
                }
            }
            fullSetOwner = null;
        }

        // 3. 清空限次额度
        if (allowanceManager != null) {
            allowanceManager.clearPlayerData(uid);
        }

        // 4. 清空兑换记录
        playerUuidToRedeemedKeys.remove(uid);
        playerActiveHeldKeys.remove(uid);
        gemIdToRedeemer.entrySet().removeIf(entry -> uid.equals(entry.getValue()));

        // 5. 清空权限附件
        PermissionAttachment invAtt = invAttachments.remove(uid);
        if (invAtt != null) {
            try {
                player.removeAttachment(invAtt);
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to remove inv permission attachment: " + e.getMessage());
            }
        }
        PermissionAttachment redAtt = redeemAttachments.remove(uid);
        if (redAtt != null) {
            try {
                player.removeAttachment(redAtt);
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to remove redeem permission attachment: " + e.getMessage());
            }
        }

        // 6. 重算权限
        try {
            player.recalculatePermissions();
        } catch (Throwable e) {
            plugin.getLogger().fine("Failed to recalculate permissions during revokeAll: " + e.getMessage());
        }

        // 7. 记录日志
        if (hadAny && historyLogger != null) {
            LanguageManager lm = plugin.getLanguageManager();
            historyLogger.logPermissionRevoke(
                    uid.toString(), player.getName(), "ALL",
                    lm != null ? lm.getMessage("history_reason.all_permissions") : "All gem permissions",
                    Collections.emptyList(), null,
                    lm != null ? lm.getMessage("history_reason.admin_revoke") : "Admin forced revoke");
        }

        // 8. 持久化
        save();

        return hadAny;
    }

    // ==================== 兑换跟踪 ====================

    /**
     * 标记宝石已兑换
     */
    public void markGemRedeemed(Player player, String gemKey) {
        if (player == null || gemKey == null || gemKey.isEmpty())
            return;
        String normalizedKey = gemKey.toLowerCase(ROOT_LOCALE);
        playerUuidToRedeemedKeys.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>()).add(normalizedKey);
    }

    /**
     * 获取当前统治者列表（考虑 pendingKeyRevokes 过滤）
     * - fullSetOwner（若存在）
     * - 由 gemIdToRedeemer/ownerKeyCount 汇总的当前持有者
     */
    public Map<UUID, Set<String>> getCurrentRulers() {
        Map<UUID, Set<String>> map = new HashMap<>();
        if (fullSetOwner != null) {
            PendingRevoke fsPr = pendingRevokes.get(fullSetOwner);
            Set<String> pendingKeys = (fsPr != null) ? fsPr.getKeys() : null;
            if (pendingKeys == null || !pendingKeys.contains("all")) {
                map.put(fullSetOwner, new HashSet<>(Collections.singleton("ALL")));
            }
        }
        for (Map.Entry<UUID, UUID> e : gemIdToRedeemer.entrySet()) {
            UUID u = e.getValue();
            if (u == null)
                continue;
            String k = stateManager.getGemUuidToKey().get(e.getKey());
            if (k == null)
                continue;
            String normalizedKey = k.toLowerCase(ROOT_LOCALE);
            // 跳过待离线撤销的组合
            PendingRevoke pr = pendingRevokes.get(u);
            Set<String> pendingKeys = (pr != null) ? pr.getKeys() : null;
            if (pendingKeys != null && pendingKeys.contains(normalizedKey))
                continue;
            map.computeIfAbsent(u, kk -> new HashSet<>()).add(normalizedKey);
        }
        return map;
    }

    // ==================== 背包持有归属切换 ====================

    /**
     * 处理背包持有归属切换（拾取宝石时调用）
     * 在 inventory_grants 模式下：切换 gemId 归属，并按计数增减管理权限
     */
    public void handleInventoryOwnershipOnPickup(Player player, UUID gemId) {
        if (player == null || gemId == null)
            return;
        if (!gameplayConfig.isInventoryGrantsEnabled())
            return;
        String gemKey = stateManager.getGemUuidToKey().get(gemId);
        if (gemKey == null)
            return;
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def == null)
            return;
        // 实例级额度：将该 gemId 的持有者额度转交给当前玩家
        if (allowanceManager != null) {
            allowanceManager.reassignHeldInstanceAllowance(gemId, player.getUniqueId(), def);
        }
        // 切换归属，维护类型计数
        UUID old = gemIdToRedeemer.put(gemId, player.getUniqueId());
        String key = gemKey.toLowerCase(ROOT_LOCALE);
        if (old != null && !old.equals(player.getUniqueId())) {
            decrementOwnerKeyCount(old, key, def);
        }
        incrementOwnerKeyCount(player.getUniqueId(), key, def);
    }
}
