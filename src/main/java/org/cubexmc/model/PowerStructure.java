package org.cubexmc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权力结构 - 统一封装权限、限次命令、Vault组、药水效果等
 * 用于宝石(Gem)和任命(Appoint)的权限管理
 */
public class PowerStructure {

    private List<String> permissions;
    private List<String> vaultGroups;
    private List<AllowedCommand> allowedCommands;
    private List<EffectConfig> effects;
    private Map<String, AppointDefinition> appoints; // 该权力可以委任的下级职位
    private PowerCondition condition;

    public PowerStructure() {
        this.permissions = new ArrayList<>();
        this.vaultGroups = new ArrayList<>();
        this.allowedCommands = new ArrayList<>();
        this.effects = new ArrayList<>();
        this.appoints = new HashMap<>();
        this.condition = new PowerCondition();
    }

    /**
     * 完整构造函数
     */
    public PowerStructure(List<String> permissions, List<String> vaultGroups, 
                          List<AllowedCommand> allowedCommands, List<EffectConfig> effects,
                          Map<String, AppointDefinition> appoints, PowerCondition condition) {
        this.permissions = permissions != null ? new ArrayList<>(permissions) : new ArrayList<>();
        this.vaultGroups = vaultGroups != null ? new ArrayList<>(vaultGroups) : new ArrayList<>();
        this.allowedCommands = allowedCommands != null ? new ArrayList<>(allowedCommands) : new ArrayList<>();
        this.effects = effects != null ? new ArrayList<>(effects) : new ArrayList<>();
        this.appoints = appoints != null ? new HashMap<>(appoints) : new HashMap<>();
        this.condition = condition != null ? condition : new PowerCondition();
    }

    /**
     * 向后兼容的构造函数
     */
    public PowerStructure(List<String> permissions, List<String> vaultGroups, 
                          List<AllowedCommand> allowedCommands, List<EffectConfig> effects,
                          PowerCondition condition) {
        this(permissions, vaultGroups, allowedCommands, effects, null, condition);
    }

    /**
     * 向后兼容的构造函数（无 effects）
     */
    public PowerStructure(List<String> permissions, List<String> vaultGroups, 
                          List<AllowedCommand> allowedCommands, PowerCondition condition) {
        this(permissions, vaultGroups, allowedCommands, null, null, condition);
    }

    // ==================== Getters & Setters ====================

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions != null ? permissions : new ArrayList<>();
    }

    public List<String> getVaultGroups() {
        return vaultGroups;
    }

    public void setVaultGroups(List<String> vaultGroups) {
        this.vaultGroups = vaultGroups != null ? vaultGroups : new ArrayList<>();
    }

    /**
     * 获取单个 Vault 组（兼容旧 API）
     */
    public String getVaultGroup() {
        return vaultGroups.isEmpty() ? null : vaultGroups.get(0);
    }

    /**
     * 设置单个 Vault 组（兼容旧 API）
     */
    public void setVaultGroup(String vaultGroup) {
        this.vaultGroups.clear();
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            this.vaultGroups.add(vaultGroup);
        }
    }

    public List<AllowedCommand> getAllowedCommands() {
        return allowedCommands;
    }

    public void setAllowedCommands(List<AllowedCommand> allowedCommands) {
        this.allowedCommands = allowedCommands != null ? allowedCommands : new ArrayList<>();
    }

    public List<EffectConfig> getEffects() {
        return effects;
    }

    public void setEffects(List<EffectConfig> effects) {
        this.effects = effects != null ? effects : new ArrayList<>();
    }

    public Map<String, AppointDefinition> getAppoints() {
        return appoints;
    }

    public void setAppoints(Map<String, AppointDefinition> appoints) {
        this.appoints = appoints != null ? appoints : new HashMap<>();
    }

    public PowerCondition getCondition() {
        return condition;
    }

    public void setCondition(PowerCondition condition) {
        this.condition = condition != null ? condition : new PowerCondition();
    }

    // ==================== 工具方法 ====================

    /**
     * 检查是否有任何权限内容
     */
    public boolean hasAnyContent() {
        return !permissions.isEmpty() || !vaultGroups.isEmpty() || !allowedCommands.isEmpty() || !effects.isEmpty() || !appoints.isEmpty();
    }

    /**
     * 检查是否有条件配置
     */
    public boolean hasConditions() {
        return condition != null && condition.hasAnyCondition();
    }

    /**
     * 合并另一个权力结构（用于继承）
     */
    public void merge(PowerStructure other) {
        if (other == null) return;
        
        for (String perm : other.permissions) {
            if (!this.permissions.contains(perm)) {
                this.permissions.add(perm);
            }
        }
        for (String group : other.vaultGroups) {
            if (!this.vaultGroups.contains(group)) {
                this.vaultGroups.add(group);
            }
        }
        // 限次命令按 label 合并（避免重复）
        for (AllowedCommand cmd : other.allowedCommands) {
            boolean exists = this.allowedCommands.stream()
                .anyMatch(c -> c.getLabel().equalsIgnoreCase(cmd.getLabel()));
            if (!exists) {
                this.allowedCommands.add(cmd);
            }
        }
        // 药水效果按类型合并（避免重复）
        for (EffectConfig effect : other.effects) {
            boolean exists = this.effects.stream()
                .anyMatch(e -> e.getEffectType() != null && 
                         e.getEffectType().equals(effect.getEffectType()));
            if (!exists) {
                this.effects.add(effect);
            }
        }
        // 委任定义合并
        if (other.appoints != null) {
            for (Map.Entry<String, AppointDefinition> entry : other.appoints.entrySet()) {
                if (!this.appoints.containsKey(entry.getKey())) {
                    this.appoints.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * 创建副本
     */
    public PowerStructure copy() {
        List<EffectConfig> effectsCopy = new ArrayList<>();
        for (EffectConfig effect : this.effects) {
            effectsCopy.add(effect.copy());
        }
        Map<String, AppointDefinition> appointsCopy = new HashMap<>(this.appoints);
        // AppointDefinition 暂不深拷贝，因为通常是静态配置
        
        return new PowerStructure(
            new ArrayList<>(this.permissions),
            new ArrayList<>(this.vaultGroups),
            new ArrayList<>(this.allowedCommands),
            effectsCopy,
            appointsCopy,
            this.condition != null ? this.condition.copy() : new PowerCondition()
        );
    }

    /**
     * 创建不可变视图
     */
    public PowerStructure immutableView() {
        PowerStructure view = new PowerStructure();
        view.permissions = Collections.unmodifiableList(this.permissions);
        view.vaultGroups = Collections.unmodifiableList(this.vaultGroups);
        view.allowedCommands = Collections.unmodifiableList(this.allowedCommands);
        view.effects = Collections.unmodifiableList(this.effects);
        view.appoints = Collections.unmodifiableMap(this.appoints);
        view.condition = this.condition;
        return view;
    }

    @Override
    public String toString() {
        return "PowerStructure{" +
                "permissions=" + permissions.size() +
                ", vaultGroups=" + vaultGroups.size() +
                ", allowedCommands=" + allowedCommands.size() +
                ", effects=" + effects.size() +
                ", appoints=" + appoints.size() +
                ", hasCondition=" + hasConditions() +
                '}';
    }
}
