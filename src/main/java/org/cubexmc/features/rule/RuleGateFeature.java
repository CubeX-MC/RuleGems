package org.cubexmc.features.rule;

import java.io.File;
import java.util.Locale;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.features.Feature;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.GemManager;

public class RuleGateFeature extends Feature {

    private final GemManager gemManager;
    private String requiredPermission;
    private String requiredPowerSet;
    private String requiredGem;
    private boolean permissionGateEnabled;

    public RuleGateFeature(RuleGems plugin, GemManager gemManager) {
        super(plugin, "rulegems.rule");
        this.gemManager = gemManager;
    }

    @Override
    public void initialize() {
        reload();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void reload() {
        File configFile = new File(plugin.getDataFolder(), "features/rule.yml");
        if (!configFile.exists()) {
            plugin.saveResource("features/rule.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        this.enabled = config.getBoolean("enabled", false);
        this.permissionGateEnabled = config.getBoolean("permission_gate.enabled", true);
        this.requiredPermission = config.getString("required_permission");
        this.requiredPowerSet = config.getString("required_power_set");
        this.requiredGem = normalizeGemKey(config.getString("required_gem"));

        if (enabled) {
            plugin.getLogger().info("RuleGate feature enabled: "
                    + "permissionGateEnabled=" + permissionGateEnabled
                    + ", requiredPermission=" + requiredPermission
                    + ", requiredPowerSet=" + requiredPowerSet
                    + ", requiredGem=" + requiredGem);
        }
    }

    public boolean canUsePower(Player player) {
        return canUsePower(player, null);
    }

    public boolean canUsePower(Player player, String gemKey) {
        if (!enabled) {
            return true;
        }
        if (player == null) {
            return false;
        }
        if (player.hasPermission("rulegems.admin")) {
            return true;
        }

        if (permissionGateEnabled && hasRulePermission(player, gemKey)) {
            return true;
        }

        return matchesConfiguredGate(player);
    }

    private boolean hasRulePermission(Player player, String gemKey) {
        if (player.hasPermission("rulegems.rule") || player.hasPermission("rulegems.rule.*")) {
            return true;
        }
        String normalized = normalizeGemKey(gemKey);
        return normalized != null && player.hasPermission("rulegems.rule." + normalized);
    }

    private boolean matchesConfiguredGate(Player player) {
        boolean hasConfiguredGate = false;

        if (requiredPermission != null && !requiredPermission.isBlank()) {
            hasConfiguredGate = true;
            if (player.hasPermission(requiredPermission)) {
                return true;
            }
        }

        if (requiredPowerSet != null && !requiredPowerSet.isBlank()) {
            hasConfiguredGate = true;
            AppointFeature appointFeature = plugin.getFeatureManager() != null
                    ? plugin.getFeatureManager().getAppointFeature()
                    : null;
            if (appointFeature != null && appointFeature.isEnabled()
                    && appointFeature.isAppointed(player.getUniqueId(), requiredPowerSet)) {
                return true;
            }
        }

        if (requiredGem != null && !requiredGem.isBlank()) {
            hasConfiguredGate = true;
            boolean redeemed = gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys()
                    .getOrDefault(player.getUniqueId(), java.util.Collections.emptySet())
                    .contains(requiredGem);
            boolean held = gemManager.getPermissionManager().getOwnerKeyCount()
                    .getOrDefault(player.getUniqueId(), java.util.Collections.emptyMap())
                    .getOrDefault(requiredGem, 0) > 0;
            if (redeemed || held) {
                return true;
            }
        }

        return !hasConfiguredGate && !permissionGateEnabled;
    }

    private String normalizeGemKey(String gemKey) {
        if (gemKey == null || gemKey.isBlank()
                || "full_set".equalsIgnoreCase(gemKey)
                || "all".equalsIgnoreCase(gemKey)) {
            return null;
        }
        return gemKey.trim().toLowerCase(Locale.ROOT);
    }
}
