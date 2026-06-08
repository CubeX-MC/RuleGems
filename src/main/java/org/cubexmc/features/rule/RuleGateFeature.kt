package org.cubexmc.features.rule

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.features.Feature
import org.cubexmc.features.FeatureManager
import org.cubexmc.manager.GemManager
import java.io.File
import java.util.Locale

class RuleGateFeature(
    plugin: RuleGems,
    private val gemManager: GemManager,
) : Feature(plugin, "rulegems.rule") {
    private var requiredPermission: String? = null
    private var requiredPowerSet: String? = null
    private var requiredGem: String? = null
    private var permissionGateEnabled = false

    override fun initialize() {
        reload()
    }

    override fun shutdown() {
    }

    override fun reload() {
        val configFile = File(plugin.dataFolder, "features/rule.yml")
        if (!configFile.exists()) {
            plugin.saveResource("features/rule.yml", false)
        }
        val config = YamlConfiguration.loadConfiguration(configFile)
        enabled = config.getBoolean("enabled", false)
        permissionGateEnabled = config.getBoolean("permission_gate.enabled", true)
        requiredPermission = config.getString("required_permission")
        requiredPowerSet = config.getString("required_power_set")
        requiredGem = normalizeGemKey(config.getString("required_gem"))

        if (enabled) {
            plugin.logger.info(
                "RuleGate feature enabled: " +
                    "permissionGateEnabled=" + permissionGateEnabled +
                    ", requiredPermission=" + requiredPermission +
                    ", requiredPowerSet=" + requiredPowerSet +
                    ", requiredGem=" + requiredGem,
            )
        }
    }

    fun canUsePower(player: Player?): Boolean {
        return canUsePower(player, null)
    }

    fun canUsePower(player: Player?, gemKey: String?): Boolean {
        if (!enabled) {
            return true
        }
        if (player == null) {
            return false
        }
        if (player.hasPermission("rulegems.admin")) {
            return true
        }

        if (permissionGateEnabled && hasRulePermission(player, gemKey)) {
            return true
        }

        return matchesConfiguredGate(player)
    }

    private fun hasRulePermission(player: Player, gemKey: String?): Boolean {
        if (player.hasPermission("rulegems.rule") || player.hasPermission("rulegems.rule.*")) {
            return true
        }
        val normalized = normalizeGemKey(gemKey)
        return normalized != null && player.hasPermission("rulegems.rule.$normalized")
    }

    private fun matchesConfiguredGate(player: Player): Boolean {
        var hasConfiguredGate = false

        val permission = requiredPermission
        if (!permission.isNullOrBlank()) {
            hasConfiguredGate = true
            if (player.hasPermission(permission)) {
                return true
            }
        }

        val powerSet = requiredPowerSet
        if (!powerSet.isNullOrBlank()) {
            hasConfiguredGate = true
            val appointFeature = featureManagerOrNull()?.appointFeature
            if (appointFeature != null && appointFeature.isEnabled && appointFeature.isAppointed(player.uniqueId, powerSet)) {
                return true
            }
        }

        val gem = requiredGem
        if (!gem.isNullOrBlank()) {
            hasConfiguredGate = true
            val playerId = player.uniqueId
            val redeemed = gemManager.permissionManager.playerUuidToRedeemedKeys
                .getOrDefault(playerId, emptySet())
                .contains(gem)
            val held = gemManager.permissionManager.ownerKeyCount
                .getOrDefault(playerId, emptyMap())
                .getOrDefault(gem, 0) > 0
            if (redeemed || held) {
                return true
            }
        }

        return !hasConfiguredGate && !permissionGateEnabled
    }

    private fun featureManagerOrNull(): FeatureManager? = try {
        plugin.featureManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    private fun normalizeGemKey(gemKey: String?): String? {
        if (gemKey.isNullOrBlank() ||
            gemKey.equals("full_set", ignoreCase = true) ||
            gemKey.equals("all", ignoreCase = true)
        ) {
            return null
        }
        return gemKey.trim().lowercase(Locale.ROOT)
    }
}
