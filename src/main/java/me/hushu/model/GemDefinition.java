package me.hushu.model;

import java.util.List;

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
                         List<String> redeemTitle) {
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
}


