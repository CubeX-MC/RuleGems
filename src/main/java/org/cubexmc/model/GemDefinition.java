package org.cubexmc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * GemDefinition 定义单颗宝石的展示与效果参数。
 * 使用 PowerStructure 统一管理权限结构。
 * 支持从全局默认值回退：若某字段未配置，则由调用者在构造时传入默认值。
 */
public class GemDefinition {
    private final String gemKey;
    private final Material material;
    private final String displayName;
    private final Particle particle;
    private final Sound sound;
    private final ExecuteConfig onPickup;
    private final ExecuteConfig onScatter;
    private final ExecuteConfig onRedeem;
    private final List<String> lore;
    private final List<String> redeemTitle; // 1-2 行标题文本
    private final boolean enchanted; // 是否为物品附魔发光（仅用于区分外观）
    private final List<String> mutualExclusive; // 互斥的 gemKey 列表
    private final int count; // 该类别宝石实例数量（默认 1）
    private final Location randomPlaceCorner1; // 随机生成范围角落1（可选，null则使用全局默认）
    private final Location randomPlaceCorner2; // 随机生成范围角落2（可选，null则使用全局默认）
    private Location altarLocation; // 放置兑换祭坛位置（可选，null则该宝石不支持祭坛兑换）

    // 使用 PowerStructure 统一管理权限结构
    private final PowerStructure powerStructure;

    /**
     * 完整构造函数（向后兼容）
     * 
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public GemDefinition(String gemKey,
            Material material,
            String displayName,
            Particle particle,
            Sound sound,
            ExecuteConfig onPickup,
            ExecuteConfig onScatter,
            ExecuteConfig onRedeem,
            List<String> permissions,
            String vaultGroup,
            List<String> lore,
            List<String> redeemTitle,
            boolean enchanted,
            List<AllowedCommand> allowedCommands,
            List<String> mutualExclusive,
            int count,
            Location randomPlaceCorner1,
            Location randomPlaceCorner2,
            Location altarLocation) {
        this.gemKey = gemKey;
        this.material = material;
        this.displayName = displayName;
        this.particle = particle;
        this.sound = sound;
        this.onPickup = onPickup;
        this.onScatter = onScatter;
        this.onRedeem = onRedeem;
        this.lore = lore;
        this.redeemTitle = redeemTitle;
        this.enchanted = enchanted;
        this.mutualExclusive = mutualExclusive == null ? Collections.emptyList() : mutualExclusive;
        this.count = Math.max(1, count);
        this.randomPlaceCorner1 = randomPlaceCorner1;
        this.randomPlaceCorner2 = randomPlaceCorner2;
        this.altarLocation = altarLocation;

        // 构建 PowerStructure
        this.powerStructure = new PowerStructure();
        this.powerStructure.setPermissions(permissions != null ? permissions : new ArrayList<>());
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            List<String> groups = new ArrayList<>();
            groups.add(vaultGroup);
            this.powerStructure.setVaultGroups(groups);
        }
        this.powerStructure.setAllowedCommands(allowedCommands != null ? allowedCommands : new ArrayList<>());
    }

    /**
     * 使用 PowerStructure 的构造函数（新 API）
     * 
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public GemDefinition(String gemKey,
            Material material,
            String displayName,
            Particle particle,
            Sound sound,
            ExecuteConfig onPickup,
            ExecuteConfig onScatter,
            ExecuteConfig onRedeem,
            PowerStructure powerStructure,
            List<String> lore,
            List<String> redeemTitle,
            boolean enchanted,
            List<String> mutualExclusive,
            int count,
            Location randomPlaceCorner1,
            Location randomPlaceCorner2,
            Location altarLocation) {
        this.gemKey = gemKey;
        this.material = material;
        this.displayName = displayName;
        this.particle = particle;
        this.sound = sound;
        this.onPickup = onPickup;
        this.onScatter = onScatter;
        this.onRedeem = onRedeem;
        this.powerStructure = powerStructure != null ? powerStructure : new PowerStructure();
        this.lore = lore;
        this.redeemTitle = redeemTitle;
        this.enchanted = enchanted;
        this.mutualExclusive = mutualExclusive == null ? Collections.emptyList() : mutualExclusive;
        this.count = Math.max(1, count);
        this.randomPlaceCorner1 = randomPlaceCorner1;
        this.randomPlaceCorner2 = randomPlaceCorner2;
        this.altarLocation = altarLocation;
    }

    // ==================== 基本属性 Getters ====================

    public String getGemKey() {
        return gemKey;
    }

    public Material getMaterial() {
        return material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Particle getParticle() {
        return particle;
    }

    public Sound getSound() {
        return sound;
    }

    public ExecuteConfig getOnPickup() {
        return onPickup;
    }

    public ExecuteConfig getOnScatter() {
        return onScatter;
    }

    public ExecuteConfig getOnRedeem() {
        return onRedeem;
    }

    public List<String> getLore() {
        return lore;
    }

    public List<String> getRedeemTitle() {
        return redeemTitle;
    }

    public boolean isEnchanted() {
        return enchanted;
    }

    public List<String> getMutualExclusive() {
        return mutualExclusive;
    }

    public int getCount() {
        return count;
    }

    public Location getRandomPlaceCorner1() {
        return randomPlaceCorner1;
    }

    public Location getRandomPlaceCorner2() {
        return randomPlaceCorner2;
    }

    public Location getAltarLocation() {
        return altarLocation;
    }

    public void setAltarLocation(Location altarLocation) {
        this.altarLocation = altarLocation;
    }

    // ==================== PowerStructure 委托方法 ====================

    /**
     * 获取底层的 PowerStructure
     */
    public PowerStructure getPowerStructure() {
        return powerStructure;
    }

    /**
     * 获取权限列表
     */
    public List<String> getPermissions() {
        return powerStructure.getPermissions();
    }

    /**
     * 获取 Vault 组（兼容旧 API，返回第一个组）
     */
    public String getVaultGroup() {
        return powerStructure.getVaultGroup();
    }

    /**
     * 获取 Vault 组列表（新 API）
     */
    public List<String> getVaultGroups() {
        return powerStructure.getVaultGroups();
    }

    /**
     * 获取限次命令列表
     */
    public List<AllowedCommand> getAllowedCommands() {
        return powerStructure.getAllowedCommands();
    }

    /**
     * 获取条件配置
     */
    public PowerCondition getCondition() {
        return powerStructure.getCondition();
    }

    /**
     * 设置条件配置
     */
    public void setCondition(PowerCondition condition) {
        powerStructure.setCondition(condition);
    }

    /**
     * 获取药水效果列表
     */
    public List<EffectConfig> getEffects() {
        return powerStructure.getEffects();
    }

    /**
     * 设置药水效果列表
     */
    public void setEffects(List<EffectConfig> effects) {
        powerStructure.setEffects(effects);
    }

    // ==================== Builder ====================

    /**
     * Private constructor used by the Builder.
     */
    private GemDefinition(Builder b) {
        this.gemKey = b.gemKey;
        this.material = b.material;
        this.displayName = b.displayName;
        this.particle = b.particle;
        this.sound = b.sound;
        this.onPickup = b.onPickup;
        this.onScatter = b.onScatter;
        this.onRedeem = b.onRedeem;
        this.powerStructure = b.powerStructure != null ? b.powerStructure : new PowerStructure();
        this.lore = b.lore;
        this.redeemTitle = b.redeemTitle;
        this.enchanted = b.enchanted;
        this.mutualExclusive = b.mutualExclusive == null ? Collections.emptyList() : b.mutualExclusive;
        this.count = Math.max(1, b.count);
        this.randomPlaceCorner1 = b.randomPlaceCorner1;
        this.randomPlaceCorner2 = b.randomPlaceCorner2;
        this.altarLocation = b.altarLocation;
    }

    /**
     * Fluent builder for {@link GemDefinition}.
     * Only {@code gemKey} is required; all other fields have sensible defaults.
     */
    public static class Builder {
        // required
        private final String gemKey;
        // optional with defaults
        private Material material = Material.RED_STAINED_GLASS;
        private String displayName;
        private Particle particle = Particle.FLAME;
        private Sound sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        private ExecuteConfig onPickup;
        private ExecuteConfig onScatter;
        private ExecuteConfig onRedeem;
        private PowerStructure powerStructure;
        private List<String> lore;
        private List<String> redeemTitle;
        private boolean enchanted;
        private List<String> mutualExclusive;
        private int count = 1;
        private Location randomPlaceCorner1;
        private Location randomPlaceCorner2;
        private Location altarLocation;

        public Builder(String gemKey) {
            this.gemKey = gemKey;
        }

        public Builder material(Material v) {
            this.material = v;
            return this;
        }

        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        public Builder particle(Particle v) {
            this.particle = v;
            return this;
        }

        public Builder sound(Sound v) {
            this.sound = v;
            return this;
        }

        public Builder onPickup(ExecuteConfig v) {
            this.onPickup = v;
            return this;
        }

        public Builder onScatter(ExecuteConfig v) {
            this.onScatter = v;
            return this;
        }

        public Builder onRedeem(ExecuteConfig v) {
            this.onRedeem = v;
            return this;
        }

        public Builder powerStructure(PowerStructure v) {
            this.powerStructure = v;
            return this;
        }

        public Builder lore(List<String> v) {
            this.lore = v;
            return this;
        }

        public Builder redeemTitle(List<String> v) {
            this.redeemTitle = v;
            return this;
        }

        public Builder enchanted(boolean v) {
            this.enchanted = v;
            return this;
        }

        public Builder mutualExclusive(List<String> v) {
            this.mutualExclusive = v;
            return this;
        }

        public Builder count(int v) {
            this.count = v;
            return this;
        }

        public Builder randomPlaceCorner1(Location v) {
            this.randomPlaceCorner1 = v;
            return this;
        }

        public Builder randomPlaceCorner2(Location v) {
            this.randomPlaceCorner2 = v;
            return this;
        }

        public Builder altarLocation(Location v) {
            this.altarLocation = v;
            return this;
        }

        public GemDefinition build() {
            return new GemDefinition(this);
        }
    }
}
