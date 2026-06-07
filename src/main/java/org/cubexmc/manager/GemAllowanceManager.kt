package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.cubexmc.features.rule.RuleGateFeature
import org.cubexmc.model.AllowedCommand
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PowerStructure
import java.util.Locale
import java.util.UUID
import java.util.function.BiPredicate
import java.util.function.Function

/**
 * 宝石命令限次管理器 - 负责管理命令使用次数限制。
 */
class GemAllowanceManager(
    private val gemParser: GemDefinitionParser,
    private val gameplayConfig: GameplayConfig,
) {
    enum class AllowanceSourceType {
        HELD,
        REDEEMED,
        APPOINTMENT,
        GLOBAL,
    }

    class ResolvedAllowance(
        val playerId: UUID,
        val sourceType: AllowanceSourceType,
        val gemId: UUID?,
        val sourceKey: String?,
        val label: String,
        val command: AllowedCommand,
    ) {
        val cooldownKey: String
            get() {
                val source = gemId?.toString() ?: (sourceKey ?: "all")
                return sourceType.name.lowercase(Locale.ROOT) + ":" + source + ":" + label
            }
    }

    val playerGemHeldUses: MutableMap<UUID, MutableMap<UUID, MutableMap<String, Int>>> = HashMap()
    val playerGemRedeemUses: MutableMap<UUID, MutableMap<UUID, MutableMap<String, Int>>> = HashMap()
    val playerGlobalAllowedUses: MutableMap<UUID, MutableMap<String, Int>> = HashMap()
    val playerAppointmentAllowedUses: MutableMap<UUID, MutableMap<String, MutableMap<String, Int>>> = HashMap()

    @Volatile
    private var dirty = false

    private val labelIndexCache: MutableMap<UUID, Set<String>> = HashMap()
    private val labelIndexDirtyPlayers: MutableSet<UUID> = HashSet()

    private var saveCallback: Runnable? = null
    private var isToggledOffCheck: BiPredicate<UUID, UUID>? = null
    private var gemKeyLookup: Function<UUID, String?>? = null
    private var appointmentPowerLookup: Function<String, PowerStructure?>? = null
    private var ruleGateFeature: RuleGateFeature? = null

    fun setSaveCallback(callback: Runnable?) {
        saveCallback = callback
    }

    fun setIsToggledOffCheck(check: BiPredicate<UUID, UUID>?) {
        isToggledOffCheck = check
    }

    fun setGemKeyLookup(lookup: Function<UUID, String?>?) {
        gemKeyLookup = lookup
    }

    fun setAppointmentPowerLookup(lookup: Function<String, PowerStructure?>?) {
        appointmentPowerLookup = lookup
    }

    fun setRuleGateFeature(feature: RuleGateFeature?) {
        ruleGateFeature = feature
    }

    fun clearAll() {
        playerGemHeldUses.clear()
        playerGemRedeemUses.clear()
        playerGlobalAllowedUses.clear()
        playerAppointmentAllowedUses.clear()
        labelIndexCache.clear()
        labelIndexDirtyPlayers.clear()
    }

    fun loadData(gemsData: FileConfiguration) {
        val allowedUses = gemsData.getConfigurationSection("allowed_uses") ?: return
        for (playerId in allowedUses.getKeys(false)) {
            try {
                val uid = UUID.fromString(playerId)
                val playerSec = allowedUses.getConfigurationSection(playerId) ?: continue
                loadInstanceSection(playerSec, "held_instances", uid, playerGemHeldUses)
                loadInstanceSection(playerSec, "redeemed_instances", uid, playerGemRedeemUses)
                loadStringSourceSection(playerSec, "appointments", uid, playerAppointmentAllowedUses)
                if (!playerGemRedeemUses.containsKey(uid)) {
                    loadInstanceSection(playerSec, "instances", uid, playerGemRedeemUses)
                }
                val globalSection = playerSec.getConfigurationSection("global")
                if (globalSection != null) {
                    val map: MutableMap<String, Int> = HashMap()
                    for (label in globalSection.getKeys(false)) {
                        map[label.lowercase(ROOT_LOCALE)] = globalSection.getInt(label, 0)
                    }
                    if (map.isNotEmpty()) playerGlobalAllowedUses[uid] = map
                }
            } catch (e: Exception) {
                Bukkit.getLogger().warning("Failed to load allowance data for player: " + e.message)
            }
        }
    }

    private fun loadInstanceSection(
        playerSec: ConfigurationSection,
        key: String,
        uid: UUID,
        target: MutableMap<UUID, MutableMap<UUID, MutableMap<String, Int>>>,
    ) {
        val section = playerSec.getConfigurationSection(key)
        if (section == null || section.getKeys(false).isEmpty()) return
        val perInstance: MutableMap<UUID, MutableMap<String, Int>> = HashMap()
        for (gid in section.getKeys(false)) {
            try {
                val gem = UUID.fromString(gid)
                val labels = section.getConfigurationSection(gid)
                val map: MutableMap<String, Int> = HashMap()
                if (labels != null) {
                    for (label in labels.getKeys(false)) {
                        map[label.lowercase(ROOT_LOCALE)] = labels.getInt(label, 0)
                    }
                }
                perInstance[gem] = map
            } catch (e: Exception) {
                Bukkit.getLogger().warning("Failed to parse gem UUID in allowance data: " + e.message)
            }
        }
        if (perInstance.isNotEmpty()) target[uid] = perInstance
    }

    private fun loadStringSourceSection(
        playerSec: ConfigurationSection,
        key: String,
        uid: UUID,
        target: MutableMap<UUID, MutableMap<String, MutableMap<String, Int>>>,
    ) {
        val section = playerSec.getConfigurationSection(key)
        if (section == null || section.getKeys(false).isEmpty()) return
        val perSource: MutableMap<String, MutableMap<String, Int>> = HashMap()
        for (source in section.getKeys(false)) {
            val labels = section.getConfigurationSection(source)
            val map: MutableMap<String, Int> = HashMap()
            if (labels != null) {
                for (label in labels.getKeys(false)) {
                    map[normalizeLabel(label)] = labels.getInt(label, 0)
                }
            }
            if (map.isNotEmpty()) {
                perSource[normalizeSourceKey(source)] = map
            }
        }
        if (perSource.isNotEmpty()) target[uid] = perSource
    }

    fun populateSaveSnapshot(snapshot: MutableMap<String, Any?>) {
        for ((playerId, byGem) in playerGemHeldUses) {
            val base = "allowed_uses.$playerId"
            for ((gemId, byLabel) in byGem) {
                for ((label, value) in byLabel) {
                    snapshot["$base.held_instances.$gemId.$label"] = value
                }
            }
        }
        for ((playerId, byGem) in playerGemRedeemUses) {
            val base = "allowed_uses.$playerId"
            for ((gemId, byLabel) in byGem) {
                for ((label, value) in byLabel) {
                    snapshot["$base.redeemed_instances.$gemId.$label"] = value
                }
            }
        }
        for ((playerId, byLabel) in playerGlobalAllowedUses) {
            val base = "allowed_uses.$playerId"
            for ((label, value) in byLabel) {
                snapshot["$base.global.$label"] = value
            }
        }
        for ((playerId, bySource) in playerAppointmentAllowedUses) {
            val base = "allowed_uses.$playerId"
            for ((source, byLabel) in bySource) {
                for ((label, value) in byLabel) {
                    snapshot["$base.appointments.$source.$label"] = value
                }
            }
        }
    }

    fun clearPlayerData(uid: UUID?) {
        if (uid == null) return
        playerGemHeldUses.remove(uid)
        playerGemRedeemUses.remove(uid)
        playerGlobalAllowedUses.remove(uid)
        playerAppointmentAllowedUses.remove(uid)
        invalidateLabelIndex(uid)
    }

    fun removeRedeemInstanceAllowance(uid: UUID?, gemId: UUID?) {
        if (uid == null || gemId == null) return
        val byGem = playerGemRedeemUses[uid] ?: return
        byGem.remove(gemId)
        if (byGem.isEmpty()) {
            playerGemRedeemUses.remove(uid)
        }
        markDirty(uid)
    }

    fun hasAnyAllowed(uid: UUID?, label: String?): Boolean {
        if (uid == null || label == null) return false
        val normalized = normalizeLabel(label)

        val perHeld = playerGemHeldUses[uid]
        if (perHeld != null) {
            for ((gemId, byLabel) in perHeld) {
                if (isToggledOffCheck?.test(uid, gemId) == true) continue
                if (!canUseSource(uid, gemId)) continue
                if (hasRemaining(byLabel, normalized)) return true
            }
        }

        val perRedeemed = playerGemRedeemUses[uid]
        if (perRedeemed != null) {
            for ((gemId, byLabel) in perRedeemed) {
                if (isToggledOffCheck?.test(uid, gemId) == true) continue
                if (!canUseSource(uid, gemId)) continue
                if (hasRemaining(byLabel, normalized)) return true
            }
        }

        val perAppointment = playerAppointmentAllowedUses[uid]
        if (perAppointment != null && canUseAppointment(uid)) {
            for (byLabel in perAppointment.values) {
                if (hasRemaining(byLabel, normalized)) return true
            }
        }

        val global = playerGlobalAllowedUses[uid]
        return global != null && canUseGlobal(uid) && hasRemaining(global, normalized)
    }

    fun resolveAllowedCommand(uid: UUID?, label: String?): ResolvedAllowance? {
        if (uid == null || label == null) return null
        val normalized = normalizeLabel(label)

        val held = resolveFromGemMap(uid, normalized, playerGemHeldUses[uid], AllowanceSourceType.HELD)
        if (held != null) return held

        val redeemed = resolveFromGemMap(uid, normalized, playerGemRedeemUses[uid], AllowanceSourceType.REDEEMED)
        if (redeemed != null) return redeemed

        val appointment = resolveFromAppointments(uid, normalized)
        if (appointment != null) return appointment

        return resolveFromGlobal(uid, normalized)
    }

    fun tryConsumeAllowed(uid: UUID?, label: String?): Boolean {
        if (uid == null || label == null) return false
        val normalized = normalizeLabel(label)

        val perHeld = playerGemHeldUses[uid]
        if (!perHeld.isNullOrEmpty()) {
            val ids = ArrayList(perHeld.keys)
            ids.sortWith { first, second -> first.compareTo(second) }
            for (gid in ids) {
                if (isToggledOffCheck?.test(uid, gid) == true) continue
                if (!canUseSource(uid, gid)) continue
                if (consumeFromMap(uid, perHeld[gid], normalized)) return true
            }
        }

        val perRedeemed = playerGemRedeemUses[uid]
        if (!perRedeemed.isNullOrEmpty()) {
            val ids = ArrayList(perRedeemed.keys)
            ids.sortWith { first, second -> first.compareTo(second) }
            for (gid in ids) {
                if (isToggledOffCheck?.test(uid, gid) == true) continue
                if (!canUseSource(uid, gid)) continue
                if (consumeFromMap(uid, perRedeemed[gid], normalized)) return true
            }
        }

        val perAppointment = playerAppointmentAllowedUses[uid]
        if (!perAppointment.isNullOrEmpty() && canUseAppointment(uid)) {
            val ids = ArrayList(perAppointment.keys)
            ids.sortWith { first, second -> first.compareTo(second) }
            for (appointKey in ids) {
                if (consumeFromMap(uid, perAppointment[appointKey], normalized)) return true
            }
        }

        val global = playerGlobalAllowedUses[uid]
        if (global != null) {
            if (!canUseGlobal(uid)) return false
            return consumeFromMap(uid, global, normalized)
        }

        return false
    }

    fun tryConsumeAllowed(uid: UUID?, resolved: ResolvedAllowance?): Boolean {
        if (uid == null || resolved == null || uid != resolved.playerId) return false
        val source = getSourceMap(uid, resolved)
        return consumeFromMap(uid, source, resolved.label)
    }

    fun refundAllowed(uid: UUID?, label: String?) {
        if (uid == null || label == null) return
        val normalized = normalizeLabel(label)

        val perHeld = playerGemHeldUses[uid]
        if (perHeld != null) {
            for (byLabel in perHeld.values) {
                if (byLabel.containsKey(normalized)) {
                    val value = byLabel.getOrDefault(normalized, 0)
                    if (value < 0) return
                    byLabel[normalized] = value + 1
                    markDirty(uid)
                    return
                }
            }
        }

        val perRedeemed = playerGemRedeemUses[uid]
        if (perRedeemed != null) {
            for (byLabel in perRedeemed.values) {
                if (byLabel.containsKey(normalized)) {
                    val value = byLabel.getOrDefault(normalized, 0)
                    if (value < 0) return
                    byLabel[normalized] = value + 1
                    markDirty(uid)
                    return
                }
            }
        }

        val perAppointment = playerAppointmentAllowedUses[uid]
        if (perAppointment != null) {
            for (byLabel in perAppointment.values) {
                if (byLabel.containsKey(normalized)) {
                    val value = byLabel.getOrDefault(normalized, 0)
                    if (value < 0) return
                    byLabel[normalized] = value + 1
                    markDirty(uid)
                    return
                }
            }
        }

        val global = playerGlobalAllowedUses.computeIfAbsent(uid) { HashMap() }
        val value = global.getOrDefault(normalized, 0)
        if (value < 0) return
        global[normalized] = value + 1
        markDirty(uid)
    }

    fun refundAllowed(uid: UUID?, resolved: ResolvedAllowance?) {
        if (uid == null || resolved == null || uid != resolved.playerId) return
        val source = getOrCreateSourceMap(uid, resolved) ?: return
        val normalized = resolved.label
        val value = source.getOrDefault(normalized, 0)
        if (value < 0) return
        source[normalized] = value + 1
        markDirty(uid)
    }

    fun getRemainingAllowed(uid: UUID?, label: String?): Int {
        if (uid == null || label == null) return 0
        val normalized = normalizeLabel(label)
        var sum = 0

        val perHeld = playerGemHeldUses[uid]
        if (perHeld != null) {
            for ((gemId, byLabel) in perHeld) {
                if (isToggledOffCheck?.test(uid, gemId) == true) continue
                if (!canUseSource(uid, gemId)) continue
                val value = byLabel[normalized]
                if (value != null) {
                    if (value < 0) return -1
                    sum += value
                }
            }
        }

        val perRedeemed = playerGemRedeemUses[uid]
        if (perRedeemed != null) {
            for ((gemId, byLabel) in perRedeemed) {
                if (isToggledOffCheck?.test(uid, gemId) == true) continue
                if (!canUseSource(uid, gemId)) continue
                val value = byLabel[normalized]
                if (value != null) {
                    if (value < 0) return -1
                    sum += value
                }
            }
        }

        val perAppointment = playerAppointmentAllowedUses[uid]
        if (perAppointment != null && canUseAppointment(uid)) {
            for (byLabel in perAppointment.values) {
                val value = byLabel[normalized]
                if (value != null) {
                    if (value < 0) return -1
                    sum += value
                }
            }
        }

        val global = playerGlobalAllowedUses[uid]
        if (global != null && canUseGlobal(uid)) {
            val value = global[normalized]
            if (value != null) {
                if (value < 0) return -1
                sum += value
            }
        }

        return sum
    }

    fun getAllowedCommand(uid: UUID?, label: String?): AllowedCommand? {
        if (uid == null || label == null) return null
        val normalized = normalizeLabel(label)

        val resolved = resolveAllowedCommand(uid, normalized)
        if (resolved != null) {
            return resolved.command
        }

        for (definition in gemParser.gemDefinitions) {
            for (command in definition.allowedCommands) {
                if (normalizeLabel(command.label) == normalized) return command
            }
        }

        val appointments = playerAppointmentAllowedUses[uid]
        if (appointments != null) {
            val keys = ArrayList(appointments.keys)
            keys.sortWith { first, second -> first.compareTo(second) }
            for (appointKey in keys) {
                val command = findCommandForAppointment(appointKey, normalized)
                if (command != null) return command
            }
        }

        val redeemAllPower: PowerStructure? = gameplayConfig.redeemAllPowerStructure
        if (redeemAllPower != null) {
            for (command in redeemAllPower.allowedCommands) {
                if (normalizeLabel(command.label) == normalized) return command
            }
        }

        return null
    }

    fun grantGlobalAllowedCommands(player: Player?, definition: GemDefinition?) {
        if (player == null || definition == null) return
        val feature = ruleGateFeature
        if (feature != null && !feature.canUsePower(player, definition.gemKey)) return
        val allows = definition.allowedCommands
        if (allows.isEmpty()) return

        val uid = player.uniqueId
        val global = playerGlobalAllowedUses.computeIfAbsent(uid) { HashMap() }
        for (command in allows) {
            global[normalizeLabel(command.label)] = command.uses
        }
        markDirty(uid)
    }

    fun applyAppointmentAllowedCommands(player: Player?, appointKey: String?, power: PowerStructure?, reset: Boolean) {
        if (player == null || appointKey == null || power == null) return
        if (!canUseAppointment(player.uniqueId)) return

        val uid = player.uniqueId
        val sourceKey = normalizeSourceKey(appointKey)
        val defaults = buildAllowedMap(power)
        if (defaults.isEmpty()) {
            removeAppointmentAllowedCommands(uid, sourceKey)
            return
        }

        val byAppointment = playerAppointmentAllowedUses.computeIfAbsent(uid) { HashMap() }
        val current = byAppointment.computeIfAbsent(sourceKey) { HashMap() }
        if (reset) {
            current.clear()
        } else {
            current.keys.retainAll(defaults.keys)
        }
        for ((key, value) in defaults) {
            current.putIfAbsent(key, value)
        }
        markDirty(uid)
    }

    fun retainAppointmentAllowedCommands(uid: UUID?, activeAppointmentKeys: Set<String>?) {
        if (uid == null) return
        val byAppointment = playerAppointmentAllowedUses[uid]
        if (byAppointment.isNullOrEmpty()) return

        val active: MutableSet<String> = HashSet()
        if (activeAppointmentKeys != null) {
            for (key in activeAppointmentKeys) {
                if (key.isNotBlank()) {
                    active.add(normalizeSourceKey(key))
                }
            }
        }

        val changed = byAppointment.keys.removeIf { key -> !active.contains(key) }
        if (byAppointment.isEmpty()) {
            playerAppointmentAllowedUses.remove(uid)
        }
        if (changed) markDirty(uid)
    }

    fun removeAppointmentAllowedCommands(uid: UUID?, appointKey: String?) {
        if (uid == null || appointKey == null) return
        val byAppointment = playerAppointmentAllowedUses[uid] ?: return
        val sourceKey = normalizeSourceKey(appointKey)
        if (byAppointment.remove(sourceKey) != null) {
            if (byAppointment.isEmpty()) {
                playerAppointmentAllowedUses.remove(uid)
            }
            markDirty(uid)
        }
    }

    fun reassignHeldInstanceAllowance(gemId: UUID?, newOwner: UUID?, definition: GemDefinition?) {
        if (gemId == null || newOwner == null || definition == null) return
        val feature = ruleGateFeature
        if (feature != null) {
            val player = Bukkit.getPlayer(newOwner)
            if (player != null && !feature.canUsePower(player, definition.gemKey)) return
        }

        var oldOwner: UUID? = null
        for ((owner, byGem) in playerGemHeldUses) {
            if (byGem.containsKey(gemId)) {
                oldOwner = owner
                break
            }
        }

        if (newOwner == oldOwner) return

        var payload: MutableMap<String, Int>? = null
        if (oldOwner != null) {
            val map = playerGemHeldUses[oldOwner]
            if (map != null) payload = map.remove(gemId)
            if (map != null && map.isEmpty()) playerGemHeldUses.remove(oldOwner)
        }

        val destination = playerGemHeldUses.computeIfAbsent(newOwner) { HashMap() }
        if (payload == null) {
            if (!destination.containsKey(gemId)) destination[gemId] = buildAllowedMap(definition)
        } else {
            destination[gemId] = payload
        }
        markDirty(newOwner)
        if (oldOwner != null) invalidateLabelIndex(oldOwner)
    }

    fun reassignRedeemInstanceAllowance(
        gemId: UUID?,
        newOwner: UUID?,
        definition: GemDefinition?,
        resetEvenIfSameOwner: Boolean,
    ) {
        if (gemId == null || newOwner == null || definition == null) return
        val feature = ruleGateFeature
        if (feature != null) {
            val player = Bukkit.getPlayer(newOwner)
            if (player != null && !feature.canUsePower(player, definition.gemKey)) return
        }

        var oldOwner: UUID? = null
        for ((owner, byGem) in playerGemRedeemUses) {
            if (byGem.containsKey(gemId)) {
                oldOwner = owner
                break
            }
        }

        if (newOwner == oldOwner) {
            if (resetEvenIfSameOwner) {
                playerGemRedeemUses.computeIfAbsent(newOwner) { HashMap() }[gemId] = buildAllowedMap(definition)
                markDirty(newOwner)
            }
            return
        }

        var payload: MutableMap<String, Int>? = null
        if (oldOwner != null) {
            val map = playerGemRedeemUses[oldOwner]
            if (map != null) payload = map.remove(gemId)
            if (map != null && map.isEmpty()) playerGemRedeemUses.remove(oldOwner)
        }

        val destination = playerGemRedeemUses.computeIfAbsent(newOwner) { HashMap() }
        if (payload == null || resetEvenIfSameOwner) {
            destination[gemId] = buildAllowedMap(definition)
        } else {
            destination[gemId] = payload
        }
        markDirty(newOwner)
        if (oldOwner != null) invalidateLabelIndex(oldOwner)
    }

    private fun buildAllowedMap(definition: GemDefinition): MutableMap<String, Int> {
        val map: MutableMap<String, Int> = HashMap()
        for (command in definition.allowedCommands) {
            map[normalizeLabel(command.label)] = command.uses
        }
        return map
    }

    private fun buildAllowedMap(power: PowerStructure?): MutableMap<String, Int> {
        val map: MutableMap<String, Int> = HashMap()
        if (power == null) return map
        for (command in power.allowedCommands) {
            map[normalizeLabel(command.label)] = command.uses
        }
        return map
    }

    fun getAvailableCommandLabels(uid: UUID?): Set<String> {
        if (uid == null) return HashSet()
        if (labelIndexDirtyPlayers.contains(uid) || !labelIndexCache.containsKey(uid)) {
            val labels = rebuildLabelIndex(uid)
            labelIndexCache[uid] = labels
            labelIndexDirtyPlayers.remove(uid)
        }
        return HashSet(labelIndexCache[uid] ?: emptySet())
    }

    private fun rebuildLabelIndex(uid: UUID): Set<String> {
        val labels: MutableSet<String> = HashSet()
        collectActiveLabelsFromNestedMap(uid, labels, playerGemHeldUses[uid])
        collectActiveLabelsFromNestedMap(uid, labels, playerGemRedeemUses[uid])
        collectActiveLabelsFromStringNestedMap(uid, labels, playerAppointmentAllowedUses[uid])
        if (canUseGlobal(uid)) {
            collectActiveLabelsFromFlatMap(labels, playerGlobalAllowedUses[uid])
        }
        return labels
    }

    private fun invalidateLabelIndex(uid: UUID?) {
        if (uid != null) labelIndexDirtyPlayers.add(uid)
    }

    private fun collectActiveLabelsFromNestedMap(
        uid: UUID,
        labels: MutableSet<String>,
        nested: Map<UUID, Map<String, Int>>?,
    ) {
        if (nested.isNullOrEmpty()) return
        for ((gemId, byLabel) in nested) {
            if (isToggledOffCheck?.test(uid, gemId) == true) continue
            if (!canUseSource(uid, gemId)) continue
            collectActiveLabelsFromFlatMap(labels, byLabel)
        }
    }

    private fun collectActiveLabelsFromStringNestedMap(
        uid: UUID,
        labels: MutableSet<String>,
        nested: Map<String, Map<String, Int>>?,
    ) {
        if (nested.isNullOrEmpty() || !canUseAppointment(uid)) return
        for (byLabel in nested.values) {
            collectActiveLabelsFromFlatMap(labels, byLabel)
        }
    }

    private fun collectActiveLabelsFromFlatMap(labels: MutableSet<String>, map: Map<String, Int>?) {
        if (map.isNullOrEmpty()) return
        for ((key, remaining) in map) {
            if (key.isBlank()) continue
            if (remaining == 0) continue
            val base = key.split(" ")[0].lowercase(ROOT_LOCALE)
            if (base.isNotEmpty()) labels.add(base)
        }
    }

    private fun resolveFromGemMap(
        uid: UUID,
        label: String,
        perGem: Map<UUID, Map<String, Int>>?,
        sourceType: AllowanceSourceType,
    ): ResolvedAllowance? {
        if (perGem.isNullOrEmpty()) return null
        val ids = ArrayList(perGem.keys)
        ids.sortWith { first, second -> first.compareTo(second) }
        for (gid in ids) {
            if (isToggledOffCheck?.test(uid, gid) == true) continue
            if (!canUseSource(uid, gid)) continue
            if (!hasRemaining(perGem[gid], label)) continue
            val command = findCommandForGem(gid, label)
            if (command != null) {
                val sourceKey = gemKeyLookup?.apply(gid)
                return ResolvedAllowance(uid, sourceType, gid, sourceKey, label, command)
            }
        }
        return null
    }

    private fun resolveFromAppointments(uid: UUID, label: String): ResolvedAllowance? {
        val perAppointment = playerAppointmentAllowedUses[uid]
        if (perAppointment.isNullOrEmpty() || !canUseAppointment(uid)) return null
        val keys = ArrayList(perAppointment.keys)
        keys.sortWith { first, second -> first.compareTo(second) }
        for (appointKey in keys) {
            if (!hasRemaining(perAppointment[appointKey], label)) continue
            val command = findCommandForAppointment(appointKey, label)
            if (command != null) {
                return ResolvedAllowance(uid, AllowanceSourceType.APPOINTMENT, null, appointKey, label, command)
            }
        }
        return null
    }

    private fun resolveFromGlobal(uid: UUID, label: String): ResolvedAllowance? {
        val global = playerGlobalAllowedUses[uid]
        if (global == null || !canUseGlobal(uid) || !hasRemaining(global, label)) return null
        val command = findCommandForRedeemAll(label)
        return if (command != null) {
            ResolvedAllowance(uid, AllowanceSourceType.GLOBAL, null, "all", label, command)
        } else {
            null
        }
    }

    private fun hasRemaining(byLabel: Map<String, Int>?, label: String): Boolean {
        if (byLabel == null) return false
        val value = byLabel[label]
        return value != null && (value > 0 || value < 0)
    }

    private fun consumeFromMap(uid: UUID, byLabel: MutableMap<String, Int>?, label: String): Boolean {
        if (byLabel == null) return false
        val value = byLabel.getOrDefault(label, 0)
        if (value < 0) {
            markDirty(uid)
            return true
        }
        if (value > 0) {
            byLabel[label] = value - 1
            markDirty(uid)
            return true
        }
        return false
    }

    private fun getSourceMap(uid: UUID, resolved: ResolvedAllowance?): MutableMap<String, Int>? {
        if (resolved == null) return null
        return when (resolved.sourceType) {
            AllowanceSourceType.HELD -> getNestedGemMap(playerGemHeldUses[uid], resolved.gemId)
            AllowanceSourceType.REDEEMED -> getNestedGemMap(playerGemRedeemUses[uid], resolved.gemId)
            AllowanceSourceType.APPOINTMENT -> getNestedStringMap(playerAppointmentAllowedUses[uid], resolved.sourceKey)
            AllowanceSourceType.GLOBAL -> playerGlobalAllowedUses[uid]
        }
    }

    private fun getOrCreateSourceMap(uid: UUID, resolved: ResolvedAllowance?): MutableMap<String, Int>? {
        if (resolved == null) return null
        return when (resolved.sourceType) {
            AllowanceSourceType.HELD -> {
                val gemId = resolved.gemId ?: return null
                playerGemHeldUses.computeIfAbsent(uid) { HashMap() }.computeIfAbsent(gemId) { HashMap() }
            }
            AllowanceSourceType.REDEEMED -> {
                val gemId = resolved.gemId ?: return null
                playerGemRedeemUses.computeIfAbsent(uid) { HashMap() }.computeIfAbsent(gemId) { HashMap() }
            }
            AllowanceSourceType.APPOINTMENT -> {
                val sourceKey = resolved.sourceKey ?: return null
                playerAppointmentAllowedUses.computeIfAbsent(uid) { HashMap() }
                    .computeIfAbsent(normalizeSourceKey(sourceKey)) { HashMap() }
            }
            AllowanceSourceType.GLOBAL -> playerGlobalAllowedUses.computeIfAbsent(uid) { HashMap() }
        }
    }

    private fun getNestedGemMap(
        nested: MutableMap<UUID, MutableMap<String, Int>>?,
        gemId: UUID?,
    ): MutableMap<String, Int>? = if (nested != null && gemId != null) nested[gemId] else null

    private fun getNestedStringMap(
        nested: MutableMap<String, MutableMap<String, Int>>?,
        sourceKey: String?,
    ): MutableMap<String, Int>? = if (nested != null && sourceKey != null) nested[normalizeSourceKey(sourceKey)] else null

    private fun findCommandForGem(gemId: UUID, label: String): AllowedCommand? {
        val gemKey = gemKeyLookup?.apply(gemId)
        if (gemKey != null) {
            val definition = findGemDefinitionByKey(gemKey)
            val command = if (definition != null) findCommand(definition.allowedCommands, label) else null
            if (command != null) return command
        }
        return findFirstGemCommand(label)
    }

    private fun findGemDefinitionByKey(gemKey: String?): GemDefinition? {
        if (gemKey == null) return null
        for (definition in gemParser.gemDefinitions) {
            if (definition.gemKey.equals(gemKey, ignoreCase = true)) {
                return definition
            }
        }
        return null
    }

    private fun findFirstGemCommand(label: String): AllowedCommand? {
        for (definition in gemParser.gemDefinitions) {
            val command = findCommand(definition.allowedCommands, label)
            if (command != null) return command
        }
        return null
    }

    private fun findCommandForAppointment(appointKey: String?, label: String): AllowedCommand? {
        if (appointmentPowerLookup == null || appointKey == null) return null
        val power = appointmentPowerLookup?.apply(appointKey)
        return if (power != null) findCommand(power.allowedCommands, label) else null
    }

    private fun findCommandForRedeemAll(label: String): AllowedCommand? {
        val redeemAllPower = gameplayConfig.redeemAllPowerStructure
        return findCommand(redeemAllPower.allowedCommands, label)
    }

    private fun findCommand(commands: List<AllowedCommand>?, label: String): AllowedCommand? {
        if (commands == null) return null
        for (command in commands) {
            if (normalizeLabel(command.label) == label) return command
        }
        return null
    }

    private fun normalizeLabel(label: String?): String = label?.trim()?.lowercase(ROOT_LOCALE) ?: ""

    private fun normalizeSourceKey(sourceKey: String?): String = sourceKey?.trim()?.lowercase(ROOT_LOCALE) ?: ""

    private fun save() {
        saveCallback?.run()
        dirty = false
    }

    private fun markDirty(uid: UUID?) {
        dirty = true
        invalidateLabelIndex(uid)
    }

    fun flushIfDirty() {
        if (dirty) save()
    }

    fun isDirty(): Boolean = dirty

    private fun canUseSource(playerId: UUID, gemId: UUID): Boolean {
        val feature = ruleGateFeature ?: return true
        val player = Bukkit.getPlayer(playerId) ?: return true
        val gemKey = gemKeyLookup?.apply(gemId)
        return feature.canUsePower(player, gemKey)
    }

    private fun canUseGlobal(playerId: UUID): Boolean {
        val feature = ruleGateFeature ?: return true
        val player = Bukkit.getPlayer(playerId)
        return player == null || feature.canUsePower(player)
    }

    private fun canUseAppointment(playerId: UUID): Boolean = canUseGlobal(playerId)

    companion object {
        private val ROOT_LOCALE: Locale = Locale.ROOT
    }
}
