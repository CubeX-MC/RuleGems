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
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.cubexmc.RuleGems;
import org.cubexmc.model.GemDefinition;

/**
 * 宝石权限管理器 - 负责权限的授予和撤销
 * 包括：Bukkit 权限附件、Vault 权限组、归属计数、离线撤销队列等
 */
public class GemPermissionManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    private final RuleGems plugin;
    private final ConfigManager configManager;
    private final GemStateManager stateManager;
    private HistoryLogger historyLogger;

    // 归属：按 gemId 记录当前兑换归属玩家
    private final Map<UUID, UUID> gemIdToRedeemer = new HashMap<>();
    // 玩家已兑换的 gemKey 集合
    private final Map<UUID, Set<String>> playerUuidToRedeemedKeys = new HashMap<>();
    // 玩家对每个 gemKey 的归属计数
    private final Map<UUID, Map<String, Integer>> ownerKeyCount = new HashMap<>();
    // 当前 inventory_grants 生效的 key
    private final Map<UUID, Set<String>> playerActiveHeldKeys = new HashMap<>();
    // 权限附件
    private final Map<UUID, PermissionAttachment> invAttachments = new HashMap<>();
    private final Map<UUID, PermissionAttachment> redeemAttachments = new HashMap<>();
    // 全套拥有者
    private UUID fullSetOwner = null;
    // 离线撤销队列
    private final Map<UUID, Set<String>> pendingPermRevokes = new HashMap<>();
    private final Map<UUID, Set<String>> pendingGroupRevokes = new HashMap<>();

    public GemPermissionManager(RuleGems plugin, ConfigManager configManager, GemStateManager stateManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.stateManager = stateManager;
    }

    public void setHistoryLogger(HistoryLogger historyLogger) {
        this.historyLogger = historyLogger;
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
        return pendingPermRevokes;
    }

    public Map<UUID, Set<String>> getPendingGroupRevokes() {
        return pendingGroupRevokes;
    }

    public UUID getFullSetOwner() {
        return fullSetOwner;
    }

    public void setFullSetOwner(UUID owner) {
        this.fullSetOwner = owner;
    }

    // ==================== 清理方法 ====================

    public void clearAll() {
        gemIdToRedeemer.clear();
        playerUuidToRedeemedKeys.clear();
        ownerKeyCount.clear();
        playerActiveHeldKeys.clear();
        pendingPermRevokes.clear();
        pendingGroupRevokes.clear();
        fullSetOwner = null;
    }

    // ==================== 权限操作 ====================

    /**
     * 授予权限（使用 redeemAttachments）
     */
    public void grantPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        PermissionAttachment attachment = redeemAttachments.computeIfAbsent(
            player.getUniqueId(), p -> player.addAttachment(plugin));
        for (String node : perms) {
            if (node == null || node.trim().isEmpty()) continue;
            attachment.setPermission(node, true);
        }
        player.recalculatePermissions();
    }

    /**
     * 兑换场景授予权限（同时写入 Vault）
     */
    public void grantRedeemPermissions(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        grantPermissions(player, perms);
        // 持久化到权限后端
        if (plugin.getVaultPerms() != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty()) continue;
                try {
                    plugin.getVaultPerms().playerAdd(player, node);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 撤销权限节点
     */
    public void revokeNodes(Player player, List<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        PermissionAttachment attachment = redeemAttachments.get(player.getUniqueId());
        if (attachment != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty()) continue;
                attachment.unsetPermission(node);
            }
        }
        // 从 Vault 移除
        if (plugin.getVaultPerms() != null) {
            for (String node : perms) {
                if (node == null || node.trim().isEmpty()) continue;
                try {
                    plugin.getVaultPerms().playerRemove(player, node);
                } catch (Exception ignored) {}
            }
        }
        player.recalculatePermissions();
    }

    /**
     * 队列离线撤销
     */
    public void queueOfflineRevokes(UUID playerId, List<String> perms, Set<String> groups) {
        if (perms != null && !perms.isEmpty()) {
            pendingPermRevokes.computeIfAbsent(playerId, k -> new HashSet<>()).addAll(perms);
        }
        if (groups != null && !groups.isEmpty()) {
            pendingGroupRevokes.computeIfAbsent(playerId, k -> new HashSet<>()).addAll(groups);
        }
    }

    /**
     * 应用待处理的离线撤销
     */
    public void applyPendingRevokes(Player player) {
        UUID uid = player.getUniqueId();
        Set<String> perms = pendingPermRevokes.remove(uid);
        Set<String> groups = pendingGroupRevokes.remove(uid);
        if (perms != null && !perms.isEmpty()) {
            revokeNodes(player, new java.util.ArrayList<>(perms));
        }
        if (groups != null && !groups.isEmpty() && plugin.getVaultPerms() != null) {
            for (String g : groups) {
                try {
                    plugin.getVaultPerms().playerRemoveGroup(player, g);
                } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 归属计数 ====================

    /**
     * 增加归属计数（0->1 时发放权限）
     */
    public void incrementOwnerKeyCount(UUID owner, String key, GemDefinition def) {
        if (owner == null || key == null) return;
        Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = before + 1;
        map.put(key, after);
        if (before == 0 && def != null) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                if (def.getPermissions() != null && !def.getPermissions().isEmpty()) {
                    grantRedeemPermissions(p, def.getPermissions());
                }
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                    try {
                        plugin.getVaultPerms().playerAddGroup(p, def.getVaultGroup());
                    } catch (Exception ignored) {}
                }
                try {
                    p.recalculatePermissions();
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 减少归属计数（1->0 时撤回权限）
     */
    public void decrementOwnerKeyCount(UUID owner, String key, GemDefinition def) {
        if (owner == null || key == null) return;
        Map<String, Integer> map = ownerKeyCount.computeIfAbsent(owner, k -> new HashMap<>());
        int before = map.getOrDefault(key, 0);
        int after = Math.max(0, before - 1);
        map.put(key, after);
        if (after == 0 && def != null) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                if (def.getPermissions() != null) revokeNodes(p, def.getPermissions());
                if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                    try {
                        plugin.getVaultPerms().playerRemoveGroup(p, def.getVaultGroup());
                    } catch (Exception ignored) {}
                }
                try {
                    p.recalculatePermissions();
                } catch (Throwable ignored) {}
                if (historyLogger != null) {
                    historyLogger.logPermissionRevoke(
                        owner.toString(), p.getName(), key, def.getDisplayName(),
                        def.getPermissions(), def.getVaultGroup(),
                        "归属切换：失去最后一件该类型宝石");
                }
            } else {
                queueOfflineRevokes(owner,
                    def.getPermissions() != null ? def.getPermissions() : Collections.emptyList(),
                    (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty()) 
                        ? Collections.singleton(def.getVaultGroup()) : Collections.emptySet());
                if (historyLogger != null) {
                    historyLogger.logPermissionRevoke(
                        owner.toString(), "未知(离线)", key, def.getDisplayName(),
                        def.getPermissions(), def.getVaultGroup(),
                        "归属切换：失去最后一件该类型宝石（离线撤销）");
                }
            }
            
            // 触发 AppointFeature 级联撤销检查
            triggerAppointCascadeRevoke(owner, def.getPermissions());
        }
    }
    
    /**
     * 触发 AppointFeature 的级联撤销检查
     * 检查被撤销的权限列表中是否包含 rulegems.appoint.* 权限
     */
    private void triggerAppointCascadeRevoke(UUID playerUuid, List<String> revokedPermissions) {
        if (revokedPermissions == null || revokedPermissions.isEmpty()) return;
        if (plugin.getFeatureManager() == null) return;
        
        org.cubexmc.features.appoint.AppointFeature appointFeature = 
            plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) return;
        
        // 检查是否撤销了 appoint 相关权限
        for (String perm : revokedPermissions) {
            if (perm != null && perm.startsWith("rulegems.appoint.")) {
                String permSetKey = perm.substring("rulegems.appoint.".length());
                // 延迟执行，确保权限已经完全撤销
                Bukkit.getScheduler().runTask(plugin, () -> {
                    appointFeature.onAppointerLostPermission(playerUuid, permSetKey);
                });
            }
        }
    }

    // ==================== 重算权限（inventory_grants 模式）====================

    /**
     * 重新计算玩家的背包权限（inventory_grants 模式）
     */
    public void recalculateGrants(Player player) {
        if (!configManager.isInventoryGrantsEnabled()) return;

        // 收集当前背包中的 key
        java.util.List<String> presentKeysOrdered = new java.util.ArrayList<>();
        Inventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (!stateManager.isRuleGem(item)) continue;
            UUID id = stateManager.getGemUUID(item);
            String key = stateManager.getGemUuidToKey().get(id);
            if (key == null) continue;
            String k = key.toLowerCase(ROOT_LOCALE);
            if (!presentKeysOrdered.contains(k)) presentKeysOrdered.add(k);
        }

        // 基于上次选中的 active 集合优先保留
        Set<String> previouslyActive = playerActiveHeldKeys.getOrDefault(player.getUniqueId(), Collections.emptySet());
        Set<String> selectedKeys = new java.util.LinkedHashSet<>();
        for (String k : presentKeysOrdered) {
            if (previouslyActive.contains(k)) selectedKeys.add(k);
        }
        for (String k : presentKeysOrdered) {
            if (selectedKeys.contains(k)) continue;
            if (!conflictsWithSelected(k, selectedKeys)) selectedKeys.add(k);
        }
        playerActiveHeldKeys.put(player.getUniqueId(), selectedKeys);

        // 聚合权限
        Set<String> shouldHave = new HashSet<>();
        for (String k : selectedKeys) {
            GemDefinition def = stateManager.findGemDefinition(k);
            if (def == null) continue;
            if (def.getPermissions() != null) {
                for (String node : def.getPermissions()) {
                    if (node != null && !node.trim().isEmpty()) shouldHave.add(node);
                }
            }
        }

        PermissionAttachment attachment = invAttachments.computeIfAbsent(
            player.getUniqueId(), p -> player.addAttachment(plugin));
        Set<String> current = new HashSet<>(attachment.getPermissions().keySet());

        for (String node : shouldHave) {
            if (!current.contains(node)) attachment.setPermission(node, true);
        }
        for (String node : current) {
            if (!shouldHave.contains(node)) attachment.unsetPermission(node);
        }
        player.recalculatePermissions();
    }

    /**
     * 检查候选 key 是否与已选择的 key 冲突
     */
    private boolean conflictsWithSelected(String candidateKey, Set<String> selectedKeys) {
        GemDefinition c = stateManager.findGemDefinition(candidateKey);
        Set<String> cm = new HashSet<>();
        if (c != null && c.getMutualExclusive() != null) {
            for (String x : c.getMutualExclusive()) {
                if (x != null) cm.add(x.toLowerCase(ROOT_LOCALE));
            }
        }
        for (String s : selectedKeys) {
            if (cm.contains(s.toLowerCase(ROOT_LOCALE))) return true;
            GemDefinition sd = stateManager.findGemDefinition(s);
            if (sd != null && sd.getMutualExclusive() != null) {
                for (String x : sd.getMutualExclusive()) {
                    if (x != null && x.equalsIgnoreCase(candidateKey)) return true;
                }
            }
        }
        return false;
    }

    // ==================== 撤销所有权限 ====================

    /**
     * 撤销玩家所有宝石相关权限
     */
    public boolean revokeAllPlayerPermissions(Player player) {
        UUID uid = player.getUniqueId();
        Set<String> redeemedKeys = playerUuidToRedeemedKeys.remove(uid);
        if (redeemedKeys == null || redeemedKeys.isEmpty()) {
            return false;
        }

        // 清理 gemIdToRedeemer
        gemIdToRedeemer.entrySet().removeIf(e -> uid.equals(e.getValue()));
        ownerKeyCount.remove(uid);

        // 撤销权限
        for (String key : redeemedKeys) {
            GemDefinition def = stateManager.findGemDefinition(key);
            if (def == null) continue;
            if (def.getPermissions() != null) {
                revokeNodes(player, def.getPermissions());
            }
            if (def.getVaultGroup() != null && !def.getVaultGroup().isEmpty() && plugin.getVaultPerms() != null) {
                try {
                    plugin.getVaultPerms().playerRemoveGroup(player, def.getVaultGroup());
                } catch (Exception ignored) {}
            }
        }

        // 清理附件
        PermissionAttachment ra = redeemAttachments.remove(uid);
        if (ra != null) {
            try { ra.remove(); } catch (Throwable ignored) {}
        }
        PermissionAttachment ia = invAttachments.remove(uid);
        if (ia != null) {
            try { ia.remove(); } catch (Throwable ignored) {}
        }

        // 清理 fullSetOwner
        if (uid.equals(fullSetOwner)) {
            fullSetOwner = null;
        }

        player.recalculatePermissions();
        return true;
    }

    /**
     * 标记宝石已兑换
     */
    public void markGemRedeemed(Player player, String gemKey) {
        if (player == null || gemKey == null || gemKey.isEmpty()) return;
        String normalizedKey = gemKey.toLowerCase(ROOT_LOCALE);
        playerUuidToRedeemedKeys.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>()).add(normalizedKey);
    }

    /**
     * 获取当前统治者列表
     */
    public Map<UUID, Set<String>> getCurrentRulers() {
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> e : playerUuidToRedeemedKeys.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                result.put(e.getKey(), new HashSet<>(e.getValue()));
            }
        }
        if (fullSetOwner != null) {
            result.computeIfAbsent(fullSetOwner, k -> new HashSet<>()).add("ALL");
        }
        return result;
    }
}

