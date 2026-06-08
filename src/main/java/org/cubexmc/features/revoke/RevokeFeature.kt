package org.cubexmc.features.revoke

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.RuleGems
import org.cubexmc.features.Feature
import org.cubexmc.manager.GemManager
import org.cubexmc.model.GemDefinition
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.LongSupplier

class RevokeFeature : Feature {
    private val gemManager: GemManager
    private val clock: LongSupplier
    private val rulesInternal: MutableMap<String, RevokeRule> = LinkedHashMap()
    private val confirmations: MutableMap<UUID, RevokeConfirmation> = ConcurrentHashMap()
    private val cooldownUntil: MutableMap<UUID, MutableMap<String, Long>> = ConcurrentHashMap()
    private var confirmTimeoutSeconds = 30
    private var dataFile: File? = null

    constructor(plugin: RuleGems, gemManager: GemManager) : this(plugin, gemManager, LongSupplier { System.currentTimeMillis() })

    constructor(plugin: RuleGems, gemManager: GemManager, clock: LongSupplier?) : super(plugin, "rulegems.revoke") {
        this.gemManager = gemManager
        this.clock = clock ?: LongSupplier { System.currentTimeMillis() }
    }

    override fun initialize() {
        reload()
    }

    override fun shutdown() {
        confirmations.clear()
        saveData()
    }

    override fun reload() {
        val configFile = File(plugin.dataFolder, "features/revoke.yml")
        if (!configFile.exists()) {
            plugin.saveResource("features/revoke.yml", false)
        }
        val config: FileConfiguration = YamlConfiguration.loadConfiguration(configFile)
        enabled = config.getBoolean("enabled", false)
        confirmTimeoutSeconds = maxOf(1, config.getInt("confirm_timeout", 30))
        loadRules(config.getConfigurationSection("rules"))

        dataFile = File(plugin.dataFolder, "data/revokes.yml")
        loadData()

        if (enabled) {
            plugin.logger.info("Revoke feature enabled with " + rulesInternal.size + " rules.")
        }
    }

    private fun loadRules(section: ConfigurationSection?) {
        rulesInternal.clear()
        if (section == null) {
            return
        }
        for (key in section.getKeys(false)) {
            val ruleSection = section.getConfigurationSection(key) ?: continue
            val triggerGem = ruleSection.getString("trigger_gem")
            val targetPowers = ruleSection.getStringList("target_powers")
            if (triggerGem.isNullOrBlank() || targetPowers.isEmpty()) {
                plugin.logger.warning("Skipping revoke rule '$key': trigger_gem and target_powers are required.")
                continue
            }
            val rule = RevokeRule(
                key,
                ruleSection.getString("display_name", key),
                triggerGem,
                targetPowers,
                ruleSection.getBoolean("require_held", true),
                ruleSection.getBoolean("consume_gem", false),
                ruleSection.getLong("cooldown", 0L),
                ruleSection.getBoolean("confirm_required", true),
                ruleSection.getBoolean("broadcast", true),
                ruleSection.getBoolean("allow_offline_target", false),
            )
            rulesInternal[normalize(key)] = rule
        }
    }

    val rules: Map<String, RevokeRule>
        get() = Collections.unmodifiableMap(rulesInternal)

    fun getAvailableRules(actor: Player?): List<RevokeRule> {
        if (!enabled || actor == null || !hasPermission(actor)) {
            return emptyList()
        }
        val available = ArrayList<RevokeRule>()
        for (rule in rulesInternal.values) {
            if (canActorUseRule(actor, rule)) {
                available.add(rule)
            }
        }
        return available
    }

    fun getRevokablePowers(actor: Player?, targetUuid: UUID?, candidatePowers: Iterable<String>?): List<String> {
        if (targetUuid == null || candidatePowers == null) {
            return emptyList()
        }
        val availableRules = getAvailableRules(actor)
        if (availableRules.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<String>()
        for (candidate in candidatePowers) {
            val normalized = normalize(candidate)
            if (normalized.isEmpty() || !targetHasPower(targetUuid, normalized)) {
                continue
            }
            for (rule in availableRules) {
                if (rule.canTargetPower(normalized)) {
                    result.add(normalized)
                    break
                }
            }
        }
        result.sortWith(String.CASE_INSENSITIVE_ORDER)
        return result
    }

    fun requestRevoke(actor: Player, ruleKey: String?, targetName: String?, powerKey: String?): RevokeResult {
        val context = validateRequest(actor, ruleKey, targetName, powerKey)
        val early = context.result
        if (early != null) {
            return early
        }
        val rule = context.rule ?: return RevokeResult.of(RevokeResult.Status.RULE_NOT_FOUND)
        val targetUuid = context.targetUuid ?: return RevokeResult.of(RevokeResult.Status.TARGET_NOT_FOUND)
        val targetNameValue = context.targetName ?: return RevokeResult.of(RevokeResult.Status.TARGET_NOT_FOUND)
        val power = context.powerKey ?: return RevokeResult.of(RevokeResult.Status.POWER_NOT_ALLOWED)
        if (rule.isConfirmRequired) {
            val confirmation = RevokeConfirmation(
                rule.key,
                targetUuid,
                targetNameValue,
                power,
                nowMillis() + confirmTimeoutSeconds * 1000L,
            )
            confirmations[actor.uniqueId] = confirmation
            return RevokeResult.of(
                RevokeResult.Status.CONFIRMATION_REQUIRED,
                placeholders(rule, targetNameValue, targetUuid, power, 0L),
            )
        }
        return executeRevoke(actor, context)
    }

    fun confirm(actor: Player): RevokeResult {
        val confirmation = confirmations.remove(actor.uniqueId)
            ?: return RevokeResult.of(RevokeResult.Status.NO_PENDING_CONFIRMATION)
        val now = nowMillis()
        if (confirmation.isExpired(now)) {
            return RevokeResult.of(RevokeResult.Status.CONFIRMATION_EXPIRED)
        }
        val context = validateRequest(actor, confirmation.ruleKey, confirmation.targetName, confirmation.powerKey)
        val early = context.result
        if (early != null) {
            return early
        }
        return executeRevoke(actor, context)
    }

    fun cancel(actor: Player): RevokeResult {
        val removed = confirmations.remove(actor.uniqueId)
        return if (removed == null) {
            RevokeResult.of(RevokeResult.Status.NO_PENDING_CONFIRMATION)
        } else {
            RevokeResult.of(RevokeResult.Status.CANCELLED)
        }
    }

    fun listRules(): RevokeResult {
        val placeholders = HashMap<String, String>()
        if (rulesInternal.isEmpty()) {
            placeholders["rules"] = ""
        } else {
            val lines = ArrayList<String>()
            for (rule in rulesInternal.values) {
                lines.add(rule.key + " -> " + rule.targetPowers.joinToString(", "))
            }
            placeholders["rules"] = lines.joinToString("\n")
        }
        return RevokeResult.of(RevokeResult.Status.LIST, placeholders)
    }

    private fun validateRequest(actor: Player, ruleKey: String?, targetName: String?, powerKey: String?): RevokeContext {
        val context = RevokeContext()
        if (!enabled) {
            context.result = RevokeResult.of(RevokeResult.Status.DISABLED)
            return context
        }
        val rule = rulesInternal[normalize(ruleKey)]
        if (rule == null) {
            context.result = RevokeResult.of(
                RevokeResult.Status.RULE_NOT_FOUND,
                mapOf("rule" to ruleKey.toString()),
            )
            return context
        }
        val normalizedPower = normalize(powerKey)
        if (!rule.canTargetPower(normalizedPower)) {
            context.result = RevokeResult.of(
                RevokeResult.Status.POWER_NOT_ALLOWED,
                mapOf("rule" to rule.key, "power" to powerKey.toString()),
            )
            return context
        }

        val target = resolveTarget(targetName, rule)
        val targetStatus = target.status
        if (targetStatus != null) {
            context.result = RevokeResult.of(targetStatus, mapOf("player" to targetName.toString()))
            return context
        }
        val targetUuid = target.uuid
        if (targetUuid == null || !targetHasPower(targetUuid, normalizedPower)) {
            context.result = RevokeResult.of(
                RevokeResult.Status.TARGET_HAS_NO_POWER,
                mapOf("player" to target.name, "power" to normalizedPower),
            )
            return context
        }

        val remaining = cooldownRemainingSeconds(actor.uniqueId, rule)
        if (remaining > 0L) {
            context.result = RevokeResult.of(
                RevokeResult.Status.COOLDOWN,
                mapOf("rule" to rule.key, "seconds" to remaining.toString()),
            )
            return context
        }

        val triggerGemId = findTriggerGem(actor, rule)
        if (triggerGemId == null && !hasRedeemedOrOwned(actor.uniqueId, rule.triggerGem)) {
            context.result = RevokeResult.of(
                RevokeResult.Status.MISSING_TRIGGER,
                mapOf("gem" to rule.triggerGem.toString(), "rule" to rule.key),
            )
            return context
        }
        if ((rule.isRequireHeld || rule.isConsumeGem) && triggerGemId == null) {
            context.result = RevokeResult.of(
                RevokeResult.Status.MISSING_TRIGGER,
                mapOf("gem" to rule.triggerGem.toString(), "rule" to rule.key),
            )
            return context
        }

        context.rule = rule
        context.targetUuid = targetUuid
        context.targetName = target.name
        context.powerKey = normalizedPower
        context.triggerGemId = triggerGemId
        return context
    }

    private fun executeRevoke(actor: Player, context: RevokeContext): RevokeResult {
        val targetUuid = context.targetUuid ?: return RevokeResult.of(RevokeResult.Status.TARGET_NOT_FOUND)
        val powerKey = context.powerKey ?: return RevokeResult.of(RevokeResult.Status.POWER_NOT_ALLOWED)
        val targetName = context.targetName ?: return RevokeResult.of(RevokeResult.Status.TARGET_NOT_FOUND)
        val rule = context.rule ?: return RevokeResult.of(RevokeResult.Status.RULE_NOT_FOUND)
        val revokedInstances = revokeTargetPower(targetUuid, powerKey)
        if (revokedInstances <= 0) {
            return RevokeResult.of(
                RevokeResult.Status.TARGET_HAS_NO_POWER,
                mapOf("player" to targetName, "power" to powerKey),
            )
        }
        val triggerGemId = context.triggerGemId
        if (rule.isConsumeGem && triggerGemId != null) {
            gemManager.stateManager.removeGemItemFromInventory(actor, triggerGemId)
            gemManager.stateManager.gemUuidToHolder.remove(triggerGemId)
            gemManager.placementManager.randomPlaceGem(triggerGemId)
            gemManager.recalculateGrants(actor)
        }
        setCooldown(actor.uniqueId, rule)
        gemManager.saveGems()

        val historyLogger = plugin.historyLogger
        if (historyLogger != null) {
            val def: GemDefinition? = gemManager.findGemDefinitionByKey(powerKey)
            historyLogger.logPermissionRevoke(
                targetUuid.toString(),
                targetName,
                powerKey,
                def?.displayName ?: powerKey,
                def?.permissions ?: emptyList(),
                def?.vaultGroup,
                "Revoke rule " + rule.key + " by " + actor.name,
            )
        }

        return RevokeResult.of(
            RevokeResult.Status.SUCCESS,
            placeholders(rule, targetName, targetUuid, powerKey, 0L),
        )
    }

    private fun revokeTargetPower(targetUuid: UUID, powerKey: String): Int {
        val def = gemManager.findGemDefinitionByKey(powerKey)
        val matchingGemIds = ArrayList<UUID>()
        for ((gemId, redeemer) in gemManager.permissionManager.gemIdToRedeemer) {
            if (targetUuid != redeemer) {
                continue
            }
            val gemKey = gemManager.stateManager.getGemKey(gemId)
            if (powerKey.equals(gemKey, ignoreCase = true)) {
                matchingGemIds.add(gemId)
            }
        }
        for (gemId in matchingGemIds) {
            gemManager.permissionManager.decrementOwnerKeyCount(targetUuid, powerKey, def)
            gemManager.permissionManager.gemIdToRedeemer.remove(gemId, targetUuid)
            gemManager.allowanceManager.removeRedeemInstanceAllowance(targetUuid, gemId)
        }
        val counts = gemManager.permissionManager.ownerKeyCount[targetUuid]
        if (counts == null || counts.getOrDefault(powerKey, 0) <= 0) {
            val redeemed = gemManager.permissionManager.playerUuidToRedeemedKeys[targetUuid]
            if (redeemed != null) {
                redeemed.remove(powerKey)
                if (redeemed.isEmpty()) {
                    gemManager.permissionManager.playerUuidToRedeemedKeys.remove(targetUuid)
                }
            }
        }
        return matchingGemIds.size
    }

    private fun resolveTarget(targetName: String?, rule: RevokeRule): Target {
        val online = if (targetName == null) null else Bukkit.getPlayer(targetName)
        if (online != null) {
            return Target(online.uniqueId, online.name, null)
        }
        if (!rule.isAllowOfflineTarget) {
            return Target(null, targetName.toString(), RevokeResult.Status.TARGET_OFFLINE_NOT_ALLOWED)
        }
        val offline: OfflinePlayer = Bukkit.getOfflinePlayer(targetName.toString())
        if (offline.uniqueId == null || (!offline.hasPlayedBefore() && offline.name == null)) {
            return Target(null, targetName.toString(), RevokeResult.Status.TARGET_NOT_FOUND)
        }
        return Target(offline.uniqueId, offline.name ?: targetName.toString(), null)
    }

    private fun targetHasPower(targetUuid: UUID, powerKey: String): Boolean {
        val counts = gemManager.permissionManager.ownerKeyCount.getOrDefault(targetUuid, emptyMap())
        if (counts.getOrDefault(powerKey, 0) > 0) {
            return true
        }
        return gemManager.permissionManager.playerUuidToRedeemedKeys
            .getOrDefault(targetUuid, emptySet())
            .contains(powerKey)
    }

    private fun findTriggerGem(player: Player, rule: RevokeRule): UUID? {
        val triggerKey = normalize(rule.triggerGem)
        val inventory = player.inventory ?: return null
        val contents = inventory.contents ?: emptyArray()
        for (item in contents) {
            val id = getMatchingHeldGemId(item, triggerKey)
            if (id != null) {
                return id
            }
        }
        return getMatchingHeldGemId(inventory.itemInOffHand, triggerKey)
    }

    private fun getMatchingHeldGemId(item: ItemStack?, triggerKey: String): UUID? {
        if (!gemManager.stateManager.isRuleGem(item)) {
            return null
        }
        val gemId = gemManager.stateManager.getGemUUID(item)
        val gemKey = gemManager.stateManager.getGemKey(gemId)
        return if (triggerKey == normalize(gemKey)) gemId else null
    }

    private fun hasRedeemedOrOwned(playerUuid: UUID, gemKey: String?): Boolean {
        val normalized = normalize(gemKey)
        if (gemManager.permissionManager.playerUuidToRedeemedKeys
                .getOrDefault(playerUuid, emptySet())
                .contains(normalized)
        ) {
            return true
        }
        return gemManager.permissionManager.ownerKeyCount
            .getOrDefault(playerUuid, emptyMap())
            .getOrDefault(normalized, 0) > 0
    }

    private fun canActorUseRule(actor: Player, rule: RevokeRule): Boolean {
        val triggerGemId = findTriggerGem(actor, rule)
        if (triggerGemId != null) {
            return true
        }
        return !rule.isRequireHeld && !rule.isConsumeGem &&
            hasRedeemedOrOwned(actor.uniqueId, rule.triggerGem)
    }

    private fun cooldownRemainingSeconds(playerUuid: UUID, rule: RevokeRule): Long {
        val until = cooldownUntil.getOrDefault(playerUuid, emptyMap()).getOrDefault(rule.key, 0L)
        val remainingMillis = until - nowMillis()
        return if (remainingMillis <= 0L) 0L else (remainingMillis + 999L) / 1000L
    }

    private fun setCooldown(playerUuid: UUID, rule: RevokeRule) {
        if (rule.cooldownSeconds <= 0L) {
            return
        }
        val until = nowMillis() + rule.cooldownSeconds * 1000L
        cooldownUntil.computeIfAbsent(playerUuid) { ConcurrentHashMap() }[rule.key] = until
        saveData()
    }

    private fun placeholders(
        rule: RevokeRule,
        targetName: String,
        targetUuid: UUID,
        powerKey: String,
        seconds: Long,
    ): Map<String, String> {
        val placeholders = HashMap<String, String>()
        placeholders["rule"] = rule.key
        placeholders["rule_name"] = rule.displayName
        placeholders["player"] = targetName
        placeholders["target"] = targetName
        placeholders["target_uuid"] = targetUuid.toString().substring(0, 8)
        placeholders["power"] = powerKey
        placeholders["consume"] = rule.isConsumeGem.toString()
        placeholders["broadcast"] = rule.isBroadcast.toString()
        placeholders["seconds"] = seconds.toString()
        return placeholders
    }

    private fun loadData() {
        cooldownUntil.clear()
        val file = dataFile
        if (file == null || !file.exists()) {
            return
        }
        val data: FileConfiguration = YamlConfiguration.loadConfiguration(file)
        val cooldowns = data.getConfigurationSection("cooldowns") ?: return
        for (uuidStr in cooldowns.getKeys(false)) {
            try {
                val uuid = UUID.fromString(uuidStr)
                val playerSection = cooldowns.getConfigurationSection(uuidStr) ?: continue
                val values: MutableMap<String, Long> = ConcurrentHashMap()
                for (rule in playerSection.getKeys(false)) {
                    values[rule] = playerSection.getLong(rule)
                }
                cooldownUntil[uuid] = values
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load revoke cooldown entry '$uuidStr': " + e.message)
            }
        }
    }

    private fun saveData() {
        val file = dataFile ?: return
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val data = YamlConfiguration()
        for ((playerId, byRule) in cooldownUntil) {
            for ((rule, cooldown) in byRule) {
                data["cooldowns.$playerId.$rule"] = cooldown
            }
        }
        try {
            data.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save revoke cooldown data: " + e.message)
        }
    }

    private fun normalize(value: String?): String {
        return value?.lowercase(ROOT_LOCALE) ?: ""
    }

    private fun nowMillis(): Long {
        return clock.asLong
    }

    private class RevokeContext {
        var result: RevokeResult? = null
        var rule: RevokeRule? = null
        var targetUuid: UUID? = null
        var targetName: String? = null
        var powerKey: String? = null
        var triggerGemId: UUID? = null
    }

    private class Target(
        val uuid: UUID?,
        val name: String,
        val status: RevokeResult.Status?,
    )

    companion object {
        private val ROOT_LOCALE: Locale = Locale.ROOT
    }
}
