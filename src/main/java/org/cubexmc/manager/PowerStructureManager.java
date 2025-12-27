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
// Removed unused import: AllowedCommand
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
    
    // 已应用的药水效果（用于追踪和撤销）: namespace -> playerUuid -> sourceId -> List<EffectConfig>
    private final Map<String, Map<UUID, Map<String, List<EffectConfig>>>> appliedEffects = new HashMap<>();

    public PowerStructureManager(RuleGems plugin) {
        this.plugin = plugin;
    }

    // ==================== 权限应用 ====================

    /**
     * 应用权力结构给玩家
     * 
     * @param player 目标玩家
     * @param structure 权力结构
     * @param namespace 命名空间（如 "gem", "appoint"）
     * @param sourceId 来源 ID（如宝石 key, 权限集 key）
     * @param checkCondition 是否检查条件
     * @return 是否成功应用
     */
    public boolean applyStructure(Player player, PowerStructure structure, 
                                   String namespace, String sourceId, boolean checkCondition) {
        if (player == null || structure == null) return false;
        
        // 检查条件
        if (checkCondition && structure.hasConditions()) {
            PowerCondition condition = structure.getCondition();
            if (!condition.checkConditions(player)) {
                return false;
            }
        }
        
        UUID playerId = player.getUniqueId();
        
        // 获取或创建权限附件
        Map<UUID, PermissionAttachment> namespaceAttachments = 
            attachmentsByNamespace.computeIfAbsent(namespace, k -> new HashMap<>());
        PermissionAttachment attachment = namespaceAttachments.get(playerId);
        if (attachment == null) {
            attachment = player.addAttachment(plugin);
            namespaceAttachments.put(playerId, attachment);
        }
        
        // 应用权限
        for (String perm : structure.getPermissions()) {
            if (perm != null && !perm.trim().isEmpty()) {
                attachment.setPermission(perm, true);
            }
        }
        
        // 应用 Vault 组
        applyVaultGroups(player, structure.getVaultGroups());
        
        // 应用药水效果
        applyEffects(player, structure.getEffects(), namespace, sourceId);
        
        // 记录已应用的结构
        Map<UUID, Set<String>> namespaceApplied = 
            appliedStructures.computeIfAbsent(namespace, k -> new HashMap<>());
        namespaceApplied.computeIfAbsent(playerId, k -> new HashSet<>()).add(sourceId);
        
        player.recalculatePermissions();
        return true;
    }

    /**
     * 移除玩家的权力结构
     */
    public void removeStructure(Player player, PowerStructure structure, 
                                 String namespace, String sourceId) {
        if (player == null || structure == null) return;
        
        UUID playerId = player.getUniqueId();
        
        // 获取权限附件
        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.get(namespace);
        if (namespaceAttachments != null) {
            PermissionAttachment attachment = namespaceAttachments.get(playerId);
            if (attachment != null) {
                // 移除权限
                for (String perm : structure.getPermissions()) {
                    if (perm != null && !perm.trim().isEmpty()) {
                        attachment.unsetPermission(perm);
                    }
                }
            }
        }
        
        // 移除 Vault 组
        removeVaultGroups(player, structure.getVaultGroups());
        
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
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        
        Map<UUID, PermissionAttachment> namespaceAttachments = attachmentsByNamespace.get(namespace);
        if (namespaceAttachments != null) {
            PermissionAttachment attachment = namespaceAttachments.remove(playerId);
            if (attachment != null) {
                try {
                    attachment.remove();
                } catch (Exception ignored) {}
            }
        }
        
        // 清除所有药水效果
        clearEffectsForPlayer(player, namespace);
        
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
                } catch (Exception ignored) {}
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
        
        appliedStructures.remove(namespace);
    }

    // ==================== Vault 集成 ====================

    private void applyVaultGroups(Player player, List<String> groups) {
        if (groups == null || groups.isEmpty()) return;
        if (plugin.getVaultPerms() == null) return;
        
        for (String group : groups) {
            if (group != null && !group.trim().isEmpty()) {
                try {
                    plugin.getVaultPerms().playerAddGroup(player, group);
                } catch (Exception ignored) {}
            }
        }
    }

    private void removeVaultGroups(Player player, List<String> groups) {
        if (groups == null || groups.isEmpty()) return;
        if (plugin.getVaultPerms() == null) return;
        
        for (String group : groups) {
            if (group != null && !group.trim().isEmpty()) {
                try {
                    plugin.getVaultPerms().playerRemoveGroup(player, group);
                } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 药水效果管理 ====================

    /**
     * 应用药水效果
     */
    private void applyEffects(Player player, List<EffectConfig> effects, String namespace, String sourceId) {
        if (effects == null || effects.isEmpty()) return;
        
        UUID playerId = player.getUniqueId();
        
        // 记录已应用的效果
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = 
            appliedEffects.computeIfAbsent(namespace, k -> new HashMap<>());
        Map<String, List<EffectConfig>> playerEffects = 
            namespaceEffects.computeIfAbsent(playerId, k -> new HashMap<>());
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
        if (effects == null || effects.isEmpty()) return;
        
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
    private boolean isEffectProvidedByOtherSource(UUID playerId, String namespace, String excludeSourceId, EffectConfig effect) {
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.get(namespace);
        if (namespaceEffects == null) return false;
        
        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.get(playerId);
        if (playerEffects == null) return false;
        
        for (Map.Entry<String, List<EffectConfig>> entry : playerEffects.entrySet()) {
            if (entry.getKey().equals(excludeSourceId)) continue;
            
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
        if (namespaceEffects == null) return;
        
        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.remove(playerId);
        if (playerEffects == null) return;
        
        // 移除所有效果
        for (List<EffectConfig> effectList : playerEffects.values()) {
            for (EffectConfig effect : effectList) {
                if (effect != null) {
                    effect.remove(player);
                }
            }
        }
    }

    /**
     * 刷新玩家的药水效果（根据条件重新应用）
     */
    public void refreshEffectsForPlayer(Player player, String namespace) {
        UUID playerId = player.getUniqueId();
        
        Map<UUID, Map<String, List<EffectConfig>>> namespaceEffects = appliedEffects.get(namespace);
        if (namespaceEffects == null) return;
        
        Map<String, List<EffectConfig>> playerEffects = namespaceEffects.get(playerId);
        if (playerEffects == null) return;
        
        // 重新应用所有效果（用于条件刷新后恢复）
        for (List<EffectConfig> effectList : playerEffects.values()) {
            for (EffectConfig effect : effectList) {
                if (effect != null) {
                    effect.apply(player);
                }
            }
        }
    }

    // ==================== 条件刷新 ====================

    /**
     * 刷新玩家的所有权力结构（根据条件重新应用）
     * 
     * @param player 玩家
     * @param namespace 命名空间
     * @param structureProvider 结构提供者（sourceId -> PowerStructure）
     */
    public void refreshStructures(Player player, String namespace, 
                                   java.util.function.Function<String, PowerStructure> structureProvider) {
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        
        // 获取当前已应用的结构
        Map<UUID, Set<String>> namespaceApplied = appliedStructures.get(namespace);
        if (namespaceApplied == null) return;
        
        Set<String> appliedIds = namespaceApplied.get(playerId);
        if (appliedIds == null || appliedIds.isEmpty()) return;
        
        // 清除当前附件
        clearNamespace(player, namespace);
        
        // 重新应用（会检查条件）
        for (String sourceId : new ArrayList<>(appliedIds)) {
            PowerStructure structure = structureProvider.apply(sourceId);
            if (structure != null) {
                applyStructure(player, structure, namespace, sourceId, true);
            }
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 检查玩家是否应用了某个权力结构
     */
    public boolean hasApplied(UUID playerId, String namespace, String sourceId) {
        Map<UUID, Set<String>> namespaceApplied = appliedStructures.get(namespace);
        if (namespaceApplied == null) return false;
        
        Set<String> playerApplied = namespaceApplied.get(playerId);
        return playerApplied != null && playerApplied.contains(sourceId);
    }

    /**
     * 获取玩家已应用的权力结构 ID 列表
     */
    public Set<String> getAppliedIds(UUID playerId, String namespace) {
        Map<UUID, Set<String>> namespaceApplied = appliedStructures.get(namespace);
        if (namespaceApplied == null) return new HashSet<>();
        
        Set<String> playerApplied = namespaceApplied.get(playerId);
        return playerApplied != null ? new HashSet<>(playerApplied) : new HashSet<>();
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
        
        for (Map<UUID, PermissionAttachment> namespaceAttachments : attachmentsByNamespace.values()) {
            for (PermissionAttachment attachment : namespaceAttachments.values()) {
                try {
                    attachment.remove();
                } catch (Exception ignored) {}
            }
        }
        attachmentsByNamespace.clear();
        appliedStructures.clear();
    }
}
