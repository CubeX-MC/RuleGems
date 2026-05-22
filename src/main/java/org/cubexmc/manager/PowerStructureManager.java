package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import org.cubexmc.RuleGems;
import org.cubexmc.features.rule.RuleGateFeature;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.PowerCondition;
import org.cubexmc.model.PowerStructure;

/**
 * 权力结构管理器 - 统一处理权限应用和撤销
 * 可用于宝石和任命系统
 */
public class PowerStructureManager {

    private final RuleGems plugin;

    // 权限附件缓存（按命名空间分组）
    private final Map<String, Map<UUID, PermissionAttachment>> attachmentsByNamespace = new HashMap<>();

    // 已应用的权力结构（用于追踪和撤销）
    private final Map<String, Map<UUID, Set<String>>> appliedStructures = new HashMap<>();

    // 权限与 Vault 组引用计数，避免多个来源共享同一能力时提前移除
    private final Map<String, Map<UUID, Map<String, Integer>>> permissionRefsByNamespace = new HashMap<>();
    private final Map<String, Map<UUID, Map<String, Integer>>> groupRefsByNamespace = new HashMap<>();

    // 已应用的药水效果（用于追踪和撤销）: namespace -> playerUuid -> sourceId -> List<EffectConfig>
    private final Map<String, Map<UUID, Map<String, List<EffectConfig>>>> appliedEffects = new HashMap<>();

    private RuleGateFeature ruleGateFeature;

    public PowerStructureManager(RuleGems plugin) {
        this.plugin = plugin;
    }

    public void setRuleGateFeature(RuleGateFeature feature) {
        this.ruleGateFeature = feature;
    }

    // ==================== 权限应用 ====================

    /**
     * 应用权力结构给玩家
     * 
     * @param player         目标玩家
     * @param structure      权力结构
     * @param namespace      命名空间（如 "gem", "appoint"）
     * @param sourceId       来源 ID（如宝石 key, 权限集 key）
     * @param checkCondition 是否检查条件
     * @return 是否成功应用
     */
    public boolean applyStructure(Player player, PowerStructure structure,
            String namespace, String sourceId, boolean checkCondition) {
        if (player == null || structure == null)
            return false;

        if (ruleGateFeature != null && !canUsePower(player, namespace, sourceId)) {
            return false;
        }

        // 检查条件
        if (checkCondition && structure.hasConditions()) {
            PowerCondition condition = structure.getCondition();
            if (!condition.checkConditions(player)) {
                return false;
            }
        }

        UUID playerId = player.getUniqueId();

        // 获取或创建权限附件
        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.computeIfAbsent(namespace,
                k -> new HashMap<>());
        PermissionAttachment attachment = namespaceAttachments.get(playerId);
        if (attachment == null) {
            attachment = player.addAttachment(plugin);
            namespaceAttachments.put(playerId, attachment);
        }

        // 应用权限
        applyPermissions(playerId, namespace, attachment, structure.getPermissions());

        // 应用 Vault 组
        applyVaultGroups(player, namespace, structure.getVaultGroups());

        // 应用药水效果
        applyEffects(player, structure.getEffects(), namespace, sourceId);

        // 记录已应用的结构
        Map<UUID, Set<String>> namespaceApplied = appliedStructures.computeIfAbsent(namespace, k -> new HashMap<>());
        namespaceApplied.computeIfAbsent(playerId, k -> new HashSet<>()).add(sourceId);

        player.recalculatePermissions();
        return true;
    }

    private boolean canUsePower(Player player, String namespace, String sourceId) {
        if (namespace != null && namespace.startsWith("gem")) {
            return ruleGateFeature.canUsePower(player, sourceId);
        }
        return ruleGateFeature.canUsePower(player);
    }

    /**
     * 移除玩家的权力结构
     */
    public void removeStructure(Player player, PowerStructure structure,
            String namespace, String sourceId) {
        if (player == null || structure == null)
            return;

        UUID playerId = player.getUniqueId();

        // 获取权限附件
        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.get(namespace);
        if (namespaceAttachments != null) {
            PermissionAttachment attachment = namespaceAttachments.get(playerId);
            if (attachment != null) {
                // 移除权限
                removePermissions(playerId, namespace, attachment, structure.getPermissions());
            }
        }

        // 移除 Vault 组
        removeVaultGroups(player, namespace, structure.getVaultGroups());

        // 移除药水效果
        removeEffects(player, structure.getEffects(), namespace, sourceId);

        // 更新记录
        Map<UUID, Set<String>> namespaceApplied = appliedStructures.get(namespace);
        if (namespaceApplied != null) {
            Set<String> playerApplied = namespaceApplied.get(playerId);
            if (playerApplied != null) {
                playerApplied.remove(sourceId);
            }
        }

        player.recalculatePermissions();
    }

    /**
     * 清除玩家在某命名空间下的所有权限附件
     */
    public void clearNamespace(Player player, String namespace) {
        if (player == null)
            return;

        UUID playerId = player.getUniqueId();

        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.get(namespace);
        if (namespaceAttachments != null) {
            PermissionAttachment attachment = namespaceAttachments.remove(playerId);
            if (attachment != null) {
                try {
                    attachment.remove();
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to remove permission attachment: " + e.getMessage());
                }
            }
        }

        // 清除所有药水效果
        clearEffectsForPlayer(player, namespace);
        clearGroupsForPlayer(player, namespace);
        clearRefsForPlayer(namespace, playerId);

        Map<UUID, Set<String>> namespaceApplied = appliedStructures.get(namespace);
        if (namespaceApplied != null) {
            namespaceApplied.remove(playerId);
        }

        player.recalculatePermissions();
    }

    /**
     * 清除所有玩家在某命名空间下的权限附件
     */
    public void clearAllInNamespace(String namespace) {
        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.remove(namespace);
        if (namespaceAttachments != null) {
            for (PermissionAttachment attachment : namespaceAttachments.values()) {
                try {
                    attachment.remove();
                } catch (Exception e) {
                    plugin.getLogger()
                            .fine("Failed to remove permission attachment in namespace cleanup: " + e.getMessage());
                }
            }
        }

        // 清除所有药水效果
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.remove(namespace);
        if (namespaceEffects != null) {
            for (Map.Entry<UUID, Map<String, List<EffectConfig>>> playerEntry : namespaceEffects.entrySet()) {
                Player player = Bukkit.getPlayer(playerEntry.getKey());
                if (player != null && player.isOnline()) {
                    for (List<EffectConfig> effectList : playerEntry.getValue().values()) {
                        for (EffectConfig effect : effectList) {
                            effect.remove(player);
                        }
                    }
                }
            }
        }

        clearAllGroupsInNamespace(namespace);

        appliedStructures.remove(namespace);
        permissionRefsByNamespace.remove(namespace);
        groupRefsByNamespace.remove(namespace);
    }

    // ==================== Vault 集成 ====================

    private void applyVaultGroups(Player player, String namespace, List<String> groups) {
        if (groups == null || groups.isEmpty())
            return;

        UUID playerId = player.getUniqueId();
        for (String group : groups) {
            if (group != null && !group.trim().isEmpty()) {
                if (incrementRef(groupRefsByNamespace, namespace, playerId, group.trim()) == 1) {
                    plugin.getPermissionProvider().addGroup(player, group.trim());
                }
            }
        }
    }

    private void removeVaultGroups(Player player, String namespace, List<String> groups) {
        if (groups == null || groups.isEmpty())
            return;

        UUID playerId = player.getUniqueId();
        for (String group : groups) {
            if (group != null && !group.trim().isEmpty()) {
                if (decrementRef(groupRefsByNamespace, namespace, playerId, group.trim()) == 0) {
                    plugin.getPermissionProvider().removeGroup(player, group.trim());
                }
            }
        }
    }

    private void applyPermissions(UUID playerId, String namespace, PermissionAttachment attachment, List<String> permissions) {
        if (attachment == null || permissions == null || permissions.isEmpty()) {
            return;
        }
        for (String perm : permissions) {
            if (perm != null && !perm.trim().isEmpty()) {
                String normalized = perm.trim();
                if (incrementRef(permissionRefsByNamespace, namespace, playerId, normalized) == 1) {
                    attachment.setPermission(normalized, true);
                }
            }
        }
    }

    private void removePermissions(UUID playerId, String namespace, PermissionAttachment attachment, List<String> permissions) {
        if (attachment == null || permissions == null || permissions.isEmpty()) {
            return;
        }
        for (String perm : permissions) {
            if (perm != null && !perm.trim().isEmpty()) {
                String normalized = perm.trim();
                if (decrementRef(permissionRefsByNamespace, namespace, playerId, normalized) == 0) {
                    attachment.unsetPermission(normalized);
                }
            }
        }
    }

    private int incrementRef(Map<String, Map<UUID, Map<String, Integer>>> refsByNamespace,
            String namespace, UUID playerId, String key) {
        Map<UUID, Map<String, Integer>> namespaceRefs = refsByNamespace.computeIfAbsent(namespace, unused -> new HashMap<>());
        Map<String, Integer> playerRefs = namespaceRefs.computeIfAbsent(playerId, unused -> new HashMap<>());
        int next = playerRefs.getOrDefault(key, 0) + 1;
        playerRefs.put(key, next);
        return next;
    }

    private int decrementRef(Map<String, Map<UUID, Map<String, Integer>>> refsByNamespace,
            String namespace, UUID playerId, String key) {
        Map<UUID, Map<String, Integer>> namespaceRefs = refsByNamespace.get(namespace);
        if (namespaceRefs == null) {
            return 0;
        }
        Map<String, Integer> playerRefs = namespaceRefs.get(playerId);
        if (playerRefs == null) {
            return 0;
        }
        int current = playerRefs.getOrDefault(key, 0);
        if (current <= 1) {
            playerRefs.remove(key);
            if (playerRefs.isEmpty()) {
                namespaceRefs.remove(playerId);
            }
            if (namespaceRefs.isEmpty()) {
                refsByNamespace.remove(namespace);
            }
            return 0;
        }
        int next = current - 1;
        playerRefs.put(key, next);
        return next;
    }

    private void clearRefsForPlayer(String namespace, UUID playerId) {
        clearRefMapForPlayer(permissionRefsByNamespace, namespace, playerId);
        clearRefMapForPlayer(groupRefsByNamespace, namespace, playerId);
    }

    private void clearRefMapForPlayer(Map<String, Map<UUID, Map<String, Integer>>> refsByNamespace,
            String namespace, UUID playerId) {
        Map<UUID, Map<String, Integer>> namespaceRefs = refsByNamespace.get(namespace);
        if (namespaceRefs == null) {
            return;
        }
        namespaceRefs.remove(playerId);
        if (namespaceRefs.isEmpty()) {
            refsByNamespace.remove(namespace);
        }
    }

    private void clearGroupsForPlayer(Player player, String namespace) {
        if (player == null) {
            return;
        }
        Map<UUID, Map<String, Integer>> namespaceRefs = groupRefsByNamespace.get(namespace);
        if (namespaceRefs == null) {
            return;
        }
        Map<String, Integer> playerRefs = namespaceRefs.get(player.getUniqueId());
        if (playerRefs == null) {
            return;
        }
        for (String group : new HashSet<>(playerRefs.keySet())) {
            plugin.getPermissionProvider().removeGroup(player, group);
        }
    }

    private void clearAllGroupsInNamespace(String namespace) {
        Map<UUID, Map<String, Integer>> namespaceRefs = groupRefsByNamespace.get(namespace);
        if (namespaceRefs == null) {
            return;
        }
        for (Map.Entry<UUID, Map<String, Integer>> entry : namespaceRefs.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            for (String group : new HashSet<>(entry.getValue().keySet())) {
                plugin.getPermissionProvider().removeGroup(player, group);
            }
        }
    }

    // ==================== 药水效果管理 ====================

    /**
     * 应用药水效果
     */
    private void applyEffects(Player player, List<EffectConfig> effects, String namespace, String sourceId) {
        if (effects == null || effects.isEmpty())
            return;

        UUID playerId = player.getUniqueId();

        // 记录已应用的效果
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.computeIfAbsent(namespace,
                k -> new HashMap<>());
        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.computeIfAbsent(playerId,
                k -> new HashMap<>());
        playerEffects.put(sourceId, new ArrayList<>(effects));

        // 应用效果
        for (EffectConfig effect : effects) {
            if (effect != null) {
                effect.apply(player);
            }
        }
    }

    /**
     * 移除药水效果
     */
    private void removeEffects(Player player, List<EffectConfig> effects, String namespace, String sourceId) {
        if (effects == null || effects.isEmpty())
            return;

        UUID playerId = player.getUniqueId();

        // 更新记录
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.get(namespace);
        if (namespaceEffects != null) {
            Map<String, List<EffectConfig>> playerEffects = namespaceEffects.get(playerId);
            if (playerEffects != null) {
                playerEffects.remove(sourceId);
            }
        }

        // 检查是否还有其他来源提供相同效果
        for (EffectConfig effect : effects) {
            if (effect != null && !isEffectProvidedByOtherSource(playerId, namespace, sourceId, effect)) {
                effect.remove(player);
            }
        }
    }

    /**
     * 检查效果是否由其他来源提供
     */
    private boolean isEffectProvidedByOtherSource(UUID playerId, String namespace, String excludeSourceId,
            EffectConfig effect) {
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.get(namespace);
        if (namespaceEffects == null)
            return false;

        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.get(playerId);
        if (playerEffects == null)
            return false;

        for (Map.Entry<String, List<EffectConfig>> entry : playerEffects.entrySet()) {
            if (entry.getKey().equals(excludeSourceId))
                continue;

            for (EffectConfig otherEffect : entry.getValue()) {
                if (otherEffect.getEffectType() != null &&
                        otherEffect.getEffectType().equals(effect.getEffectType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 清除玩家在某命名空间下的所有效果
     */
    private void clearEffectsForPlayer(Player player, String namespace) {
        UUID playerId = player.getUniqueId();

        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.get(namespace);
        if (namespaceEffects == null)
            return;

        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.remove(playerId);
        if (playerEffects == null)
            return;

        // 移除所有效果
        for (List<EffectConfig> effectList : playerEffects.values()) {
            for (EffectConfig effect : effectList) {
                if (effect != null) {
                    effect.remove(player);
                }
            }
        }
    }

    // ==================== 清理 ====================

    /**
     * 清除所有缓存
     */
    public void clearAll() {
        // 移除所有药水效果
        for (Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects : appliedEffects.values()) {
            for (Map.Entry<UUID, Map<String, List<EffectConfig>>> playerEntry : namespaceEffects.entrySet()) {
                Player player = Bukkit.getPlayer(playerEntry.getKey());
                if (player != null && player.isOnline()) {
                    for (List<EffectConfig> effectList : playerEntry.getValue().values()) {
                        for (EffectConfig effect : effectList) {
                            effect.remove(player);
                        }
                    }
                }
            }
        }
        appliedEffects.clear();
        for (String namespace : new HashSet<>(groupRefsByNamespace.keySet())) {
            clearAllGroupsInNamespace(namespace);
        }

        for (Map<UUID, PermissionAttachment> namespaceAttachments : attachmentsByNamespace.values()) {
            for (PermissionAttachment attachment : namespaceAttachments.values()) {
                try {
                    attachment.remove();
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to remove permission attachment during cleanup: " + e.getMessage());
                }
            }
        }
        attachmentsByNamespace.clear();
        appliedStructures.clear();
        permissionRefsByNamespace.clear();
        groupRefsByNamespace.clear();
    }
}
