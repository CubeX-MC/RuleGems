package org.cubexmc.model;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * GemDefinition 定义单颗宝石的展示与效果参数。
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
    private final List<String> permissions;
    private final String vaultGroup;
    private final List<String> lore;
    private final List<String> redeemTitle; // 1-2 行标题文本
    private final boolean enchanted; // 是否为物品附魔发光（仅用于区分外观）
    private final java.util.List<AllowedCommand> allowedCommands; // 兑换后可用的受限指令
    private final java.util.List<String> mutualExclusive; // 互斥的 gemKey 列表
    private final int count; // 该类别宝石实例数量（默认 1）
    private final Location randomPlaceCorner1; // 随机生成范围角落1（可选，null则使用全局默认）
    private final Location randomPlaceCorner2; // 随机生成范围角落2（可选，null则使用全局默认）
    private Location altarLocation; // 放置兑换祭坛位置（可选，null则该宝石不支持祭坛兑换）

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
                         java.util.List<AllowedCommand> allowedCommands,
                         java.util.List<String> mutualExclusive,
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
        this.permissions = permissions;
        this.vaultGroup = vaultGroup;
        this.lore = lore;
        this.redeemTitle = redeemTitle;
        this.enchanted = enchanted;
        this.allowedCommands = allowedCommands == null ? java.util.Collections.emptyList() : allowedCommands;
        this.mutualExclusive = mutualExclusive == null ? java.util.Collections.emptyList() : mutualExclusive;
        this.count = Math.max(1, count);
        this.randomPlaceCorner1 = randomPlaceCorner1;
        this.randomPlaceCorner2 = randomPlaceCorner2;
        this.altarLocation = altarLocation;
    }

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

    public List<String> getPermissions() {
        return permissions;
    }

    public String getVaultGroup() {
        return vaultGroup;
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

    public java.util.List<AllowedCommand> getAllowedCommands() {
        return allowedCommands;
    }

    public java.util.List<String> getMutualExclusive() {
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
}




