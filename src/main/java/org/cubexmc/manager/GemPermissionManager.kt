package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import org.cubexmc.RuleGems
import org.cubexmc.features.FeatureManager
import org.cubexmc.model.EffectConfig
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PendingRevoke
import org.cubexmc.model.PowerStructure
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 宝石权限管理器 - 负责权限、Vault 组、归属计数和离线撤销队列。
 */
class GemPermissionManager(
    private val plugin: RuleGems,
    private val gameplayConfig: GameplayConfig,
    private val stateManager: GemStateManager,
) {
    private var historyLogger: HistoryLogger? = null
    private var allowanceManager: GemAllowanceManager? = null
    private var saveCallback: Runnable? = null

    val gemIdToRedeemer: MutableMap<UUID, UUID> = ConcurrentHashMap()
    val playerUuidToRedeemedKeys: MutableMap<UUID, MutableSet<String>> = ConcurrentHashMap()
    val ownerKeyCount: MutableMap<UUID, MutableMap<String, Int>> = ConcurrentHashMap()
    val playerActiveHeldKeys: MutableMap<UUID, MutableSet<String>> = ConcurrentHashMap()
    val invAttachments: MutableMap<UUID, PermissionAttachment> = HashMap()
    val redeemAttachments: MutableMap<UUID, PermissionAttachment> = HashMap()
    var fullSetOwner: UUID? = null

    private val pendingRevokes: MutableMap<UUID, PendingRevoke> = ConcurrentHashMap()
    private val toggledOffGems: MutableMap<UUID, MutableSet<String>> = ConcurrentHashMap()
    private val collectThresholdGroups: MutableMap<UUID, MutableSet<String>> = ConcurrentHashMap()

    val pendingPermRevokes: Map<UUID, Set<String>>
        get() = pendingRevokes
            .filterValues { it.permissions.isNotEmpty() }
            .mapValues { it.value.permissions }

    val pendingGroupRevokes: Map<UUID, Set<String>>
        get() = pendingRevokes
            .filterValues { it.groups.isNotEmpty() }
            .mapValues { it.value.groups }

    val pendingKeyRevokes: Map<UUID, Set<String>>
        get() = pendingRevokes
            .filterValues { it.keys.isNotEmpty() }
            .mapValues { it.value.keys }

    val pendingEffectRevokes: Map<UUID, Set<String>>
        get() = pendingRevokes
            .filterValues { it.effects.isNotEmpty() }
            .mapValues { it.value.effects }

    fun setHistoryLogger(historyLogger: HistoryLogger?) {
        this.historyLogger = historyLogger
    }

    fun setSaveCallback(callback: Runnable?) {
        saveCallback = callback
    }

    fun setAllowanceManager(allowanceManager: GemAllowanceManager?) {
        this.allowanceManager = allowanceManager
    }

    private fun save() {
        saveCallback?.run()
    }

    private fun getPSM(): PowerStructureManager? = try {
        plugin.powerStructureManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    private fun featureManagerOrNull(): FeatureManager? = try {
        plugin.featureManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    private fun languageManagerOrNull(): LanguageManager? = try {
        plugin.languageManager
    } catch (_: UninitializedPropertyAccessException) {
        null
    } catch (_: NullPointerException) {
        null
    }

    fun isGemToggledOff(playerUuid: UUID?, gemKey: String?): Boolean {
        if (playerUuid == null || gemKey == null) return false
        val toggledOff = toggledOffGems[playerUuid]
        return toggledOff != null && toggledOff.contains(gemKey.lowercase(ROOT_LOCALE))
    }

    fun toggleGemPower(player: Player?, gemKey: String?, enabled: Boolean) {
        if (player == null || gemKey == null) return
        val uid = player.uniqueId
        val normalizedKey = gemKey.lowercase(ROOT_LOCALE)
        val toggledOff = toggledOffGems.computeIfAbsent(uid) { HashSet() }
        val currentlyOff = toggledOff.contains(normalizedKey)

        if (enabled && currentlyOff) {
            toggledOff.remove(normalizedKey)
            val definition = stateManager.findGemDefinition(gemKey)
            val psm = getPSM()
            if (definition?.powerStructure != null && psm != null) {
                psm.applyStructure(player, definition.powerStructure, "gem_redeem", gemKey, false)
            }
            save()
        } else if (!enabled && !currentlyOff) {
            toggledOff.add(normalizedKey)
            val definition = stateManager.findGemDefinition(gemKey)
            val psm = getPSM()
            if (definition?.powerStructure != null && psm != null) {
                psm.removeStructure(player, definition.powerStructure, "gem_redeem", gemKey)
            }
            save()
        }
    }

    fun clearAll() {
        gemIdToRedeemer.clear()
        playerUuidToRedeemedKeys.clear()
        ownerKeyCount.clear()
        playerActiveHeldKeys.clear()
        pendingRevokes.clear()
        fullSetOwner = null
        toggledOffGems.clear()
        collectThresholdGroups.clear()
    }

    fun clearRuntimeState() {
        val psm = getPSM()
        for (player in Bukkit.getOnlinePlayers()) {
            clearRuntimeState(player, psm)
        }
        invAttachments.clear()
        redeemAttachments.clear()
        clearAll()
    }

    private fun clearRuntimeState(player: Player?, psm: PowerStructureManager?) {
        if (player == null) return
        if (psm != null) {
            psm.clearNamespace(player, "gem_redeem")
            psm.clearNamespace(player, "gem_appoint")
            psm.clearNamespace(player, "gem_redeem_all")
            psm.clearNamespace(player, "gem_inv")
        }
        clearCollectThresholdGroups(player)

        val invAtt = invAttachments.remove(player.uniqueId)
        if (invAtt != null) {
            try {
                player.removeAttachment(invAtt)
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to remove inv permission attachment: " + e.message)
            }
        }

        val redeemAtt = redeemAttachments.remove(player.uniqueId)
        if (redeemAtt != null) {
            try {
                player.removeAttachment(redeemAtt)
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to remove redeem permission attachment: " + e.message)
            }
        }

        try {
            player.recalculatePermissions()
        } catch (e: Throwable) {
            plugin.logger.fine("Failed to recalculate permissions during runtime state clear: " + e.message)
        }
    }

    fun resetForScatter() {
        val offlinePending = computeOfflineRulerPending()
        clearRuntimeState()
        if (offlinePending.isNotEmpty()) {
            pendingRevokes.putAll(offlinePending)
            save()
        }
    }

    private fun computeOfflineRulerPending(): Map<UUID, PendingRevoke> {
        val result: MutableMap<UUID, PendingRevoke> = HashMap()
        val thresholds = gameplayConfig.gemCollectThresholdGroups

        for ((owner, counts) in ownerKeyCount) {
            val online = Bukkit.getPlayer(owner)
            if (online != null && online.isOnline) continue
            for ((key, count) in counts) {
                if (count <= 0) continue
                val definition = stateManager.findGemDefinition(key) ?: continue
                val pending = result.computeIfAbsent(owner) { PendingRevoke() }
                pending.permissions.addAll(definition.permissions)
                pending.permissions.addAll(getAppointPermissionNodes(definition))
                val vaultGroup = definition.vaultGroup
                if (!vaultGroup.isNullOrEmpty()) pending.groups.add(vaultGroup)
                for (effect in definition.effects) {
                    val type = effect.effectType
                    if (type != null) pending.effects.add(type.name)
                }
                pending.keys.add(key.lowercase(ROOT_LOCALE))
            }
            if (result.containsKey(owner) && thresholds.isNotEmpty()) {
                result[owner]?.groups?.addAll(thresholds.values)
            }
        }

        val fullOwner = fullSetOwner
        if (fullOwner != null) {
            val online = Bukkit.getPlayer(fullOwner)
            if (online == null || !online.isOnline) {
                val redeemAllPower = gameplayConfig.redeemAllPowerStructure
                if (redeemAllPower.hasAnyContent()) {
                    val pending = result.computeIfAbsent(fullOwner) { PendingRevoke() }
                    pending.permissions.addAll(redeemAllPower.permissions)
                    pending.groups.addAll(redeemAllPower.vaultGroups)
                }
            }
        }
        return result
    }

    fun loadData(gemsData: FileConfiguration) {
        val redeemedSection = gemsData.getConfigurationSection("redeemed")
        if (redeemedSection != null) {
            for (playerUuidStr in redeemedSection.getKeys(false)) {
                try {
                    val playerId = UUID.fromString(playerUuidStr)
                    val list = redeemedSection.getStringList(playerUuidStr)
                    playerUuidToRedeemedKeys[playerId] = HashSet(list)
                } catch (_: IllegalArgumentException) {
                    plugin.logger.warning("Skipping corrupted player UUID in redeemed data: $playerUuidStr")
                }
            }
        }

        val ownerById = gemsData.getConfigurationSection("redeem_owner_by_id")
        if (ownerById != null) {
            for (gid in ownerById.getKeys(false)) {
                try {
                    val gem = UUID.fromString(gid)
                    val player = UUID.fromString(ownerById.getString(gid))
                    gemIdToRedeemer[gem] = player
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to parse redeem owner by gem id '$gid': " + e.message)
                }
            }
        } else {
            val ownerSec = gemsData.getConfigurationSection("redeem_owner")
            if (ownerSec != null) {
                val gemUuidToKey = stateManager.gemUuidToKey
                for (gemKey in ownerSec.getKeys(false)) {
                    val uuidStr = ownerSec.getString(gemKey)
                    try {
                        val player = UUID.fromString(uuidStr)
                        for ((gemId, key) in gemUuidToKey) {
                            if (key.equals(gemKey, ignoreCase = true)) {
                                gemIdToRedeemer[gemId] = player
                            }
                        }
                    } catch (e: Exception) {
                        plugin.logger.warning(
                            "Failed to parse legacy redeem owner for gem key '$gemKey': " + e.message,
                        )
                    }
                }
            }
        }

        val fullSetOwnerSection = gemsData.getConfigurationSection("full_set_owner")
        if (fullSetOwnerSection != null) {
            val rawUuid = fullSetOwnerSection.getString("uuid")
            try {
                fullSetOwner = UUID.fromString(rawUuid)
            } catch (e: Exception) {
                plugin.logger.warning("Failed to parse full set owner UUID '$rawUuid': " + e.message)
            }
        }

        val toggledOffSection = gemsData.getConfigurationSection("toggled_off_gems")
        if (toggledOffSection != null) {
            for (playerUuidStr in toggledOffSection.getKeys(false)) {
                try {
                    val playerId = UUID.fromString(playerUuidStr)
                    val list = toggledOffSection.getStringList(playerUuidStr)
                    if (list.isNotEmpty()) {
                        toggledOffGems[playerId] = HashSet(list)
                    }
                } catch (_: IllegalArgumentException) {
                    plugin.logger.warning("Skipping corrupted player UUID in toggled_off_gems data: $playerUuidStr")
                }
            }
        }

        loadPendingRevokes(gemsData)
    }

    fun rebuildOwnerKeyCountFromOwnership() {
        ownerKeyCount.clear()
        for ((gemId, owner) in gemIdToRedeemer) {
            val key = stateManager.gemUuidToKey[gemId]
            if (key.isNullOrBlank()) continue
            val normalizedKey = key.lowercase(ROOT_LOCALE)
            ownerKeyCount.computeIfAbsent(owner) { HashMap() }
                .merge(normalizedKey, 1) { first, second -> first + second }
        }
    }

    fun restoreRedeemedPermissions(player: Player?) {
        if (player == null) return
        val playerId = player.uniqueId
        val psm = getPSM()

        if (psm != null) {
            psm.clearNamespace(player, "gem_redeem")
            psm.clearNamespace(player, "gem_appoint")
            psm.clearNamespace(player, "gem_redeem_all")
        }
        clearCollectThresholdGroups(player)

        val ownedKeys = ownerKeyCount.getOrDefault(playerId, Collections.emptyMap())
        for ((key, count) in ownedKeys) {
            if (count <= 0) continue
            val definition = stateManager.findGemDefinition(key) ?: continue
            if (psm != null) {
                if (!isGemToggledOff(playerId, key)) {
                    psm.applyStructure(player, definition.powerStructure, "gem_redeem", key, false)
                }
            }
            grantAppointPermissions(player, definition)
        }

        val fullOwner = fullSetOwner
        if (fullOwner != null && fullOwner == playerId) {
            val redeemAllPower = gameplayConfig.redeemAllPowerStructure
            if (redeemAllPower.hasAnyContent()) {
                if (psm != null) {
                    psm.applyStructure(player, redeemAllPower, "gem_redeem_all", "full_set", false)
                } else {
                    grantRedeemPermissions(player, redeemAllPower.permissions)
                }
            }
        }

        reconcileCollectThresholdGroups(player)
        player.recalculatePermissions()
    }

    fun restoreRedeemedPermissionsForOnlinePlayers() {
        rebuildOwnerKeyCountFromOwnership()
        for (player in Bukkit.getOnlinePlayers()) {
            restoreRedeemedPermissions(player)
        }
    }

    private fun loadPendingRevokes(gemsData: FileConfiguration) {
        val categories = arrayOf("permissions", "groups", "keys", "effects")
        for (category in categories) {
            val section = gemsData.getConfigurationSection("pending_revokes.$category") ?: continue
            for (pid in section.getKeys(false)) {
                try {
                    val id = UUID.fromString(pid)
                    val list = section.getStringList(pid)
                    if (list.isEmpty()) continue
                    val pending = pendingRevokes.computeIfAbsent(id) { PendingRevoke() }
                    val target = when (category) {
                        "permissions" -> pending.permissions
                        "groups" -> pending.groups
                        "keys" -> pending.keys
                        "effects" -> pending.effects
                        else -> null
                    }
                    target?.addAll(list)
                } catch (e: Exception) {
                    plugin.logger.warning(
                        "Failed to load pending revoke entry for '$pid' at path 'pending_revokes.$category': " +
                            e.message,
                    )
                }
            }
        }
    }

    fun populateSaveSnapshot(snapshot: MutableMap<String, Any?>) {
        for ((playerId, keys) in playerUuidToRedeemedKeys) {
            snapshot["redeemed.$playerId"] = ArrayList(keys)
        }
        for ((gemId, owner) in gemIdToRedeemer) {
            snapshot["redeem_owner_by_id.$gemId"] = owner.toString()
        }
        val fullOwner = fullSetOwner
        if (fullOwner != null) {
            snapshot["full_set_owner.uuid"] = fullOwner.toString()
        }
        for ((playerId, keys) in toggledOffGems) {
            if (keys.isNotEmpty()) {
                snapshot["toggled_off_gems.$playerId"] = ArrayList(keys)
            }
        }
        for ((playerId, pending) in pendingRevokes) {
            val uuid = playerId.toString()
            if (pending.permissions.isNotEmpty()) snapshot["pending_revokes.permissions.$uuid"] = ArrayList(pending.permissions)
            if (pending.groups.isNotEmpty()) snapshot["pending_revokes.groups.$uuid"] = ArrayList(pending.groups)
            if (pending.keys.isNotEmpty()) snapshot["pending_revokes.keys.$uuid"] = ArrayList(pending.keys)
            if (pending.effects.isNotEmpty()) snapshot["pending_revokes.effects.$uuid"] = ArrayList(pending.effects)
        }
    }

    fun grantPermissions(player: Player?, perms: List<String>?) {
        if (player == null || perms.isNullOrEmpty()) return
        val attachment = redeemAttachments.computeIfAbsent(player.uniqueId) { player.addAttachment(plugin) }
        for (node in perms) {
            if (node.isBlank()) continue
            attachment.setPermission(node, true)
        }
        player.recalculatePermissions()
    }

    fun grantRedeemPermissions(player: Player?, perms: List<String>?) {
        if (player == null || perms.isNullOrEmpty()) return
        grantPermissions(player, perms)
        val provider = plugin.permissionProvider ?: return
        for (node in perms) {
            if (node.isBlank()) continue
            provider.addPermission(player, node)
        }
    }

    fun revokeNodes(player: Player?, perms: List<String>?) {
        if (player == null || perms.isNullOrEmpty()) return
        val attachment = redeemAttachments[player.uniqueId]
        if (attachment != null) {
            for (node in perms) {
                if (node.isBlank()) continue
                attachment.unsetPermission(node)
            }
        }
        val provider = plugin.permissionProvider
        if (provider != null) {
            for (node in perms) {
                if (node.isBlank()) continue
                provider.removePermission(player, node)
            }
        }
        player.recalculatePermissions()
    }

    fun revokeNodesAll(player: Player?, nodes: Collection<String>?) {
        if (player == null || nodes.isNullOrEmpty()) return
        val invAttachment = invAttachments[player.uniqueId]
        if (invAttachment != null) {
            for (node in nodes) {
                try {
                    invAttachment.unsetPermission(node)
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to unset inv permission '$node': " + e.message)
                }
            }
        }
        val redeemAttachment = redeemAttachments[player.uniqueId]
        if (redeemAttachment != null) {
            for (node in nodes) {
                try {
                    redeemAttachment.unsetPermission(node)
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to unset redeem permission '$node': " + e.message)
                }
            }
        }
        val provider = plugin.permissionProvider
        if (provider != null) {
            for (node in nodes) {
                provider.removePermission(player, node)
            }
        }
    }

    fun queueOfflineRevokes(user: UUID?, perms: Collection<String>?, groups: Collection<String>?) {
        if (user == null) return
        val pending = pendingRevokes.computeIfAbsent(user) { PendingRevoke() }
        if (!perms.isNullOrEmpty()) {
            for (permission in perms) {
                if (permission.isNotBlank()) pending.permissions.add(permission)
            }
        }
        if (!groups.isNullOrEmpty()) {
            for (group in groups) {
                if (group.isNotBlank()) pending.groups.add(group)
            }
        }
        save()
    }

    fun queueOfflineEffectRevokes(user: UUID?, effects: List<EffectConfig>?) {
        if (user == null || effects.isNullOrEmpty()) return
        val pending = pendingRevokes.computeIfAbsent(user) { PendingRevoke() }
        for (effect in effects) {
            val type = effect.effectType
            if (type != null) pending.effects.add(type.name)
        }
        save()
    }

    fun applyPendingRevokesIfAny(player: Player?) {
        if (player == null) return
        val uid = player.uniqueId
        val pending = pendingRevokes.remove(uid)
        if (pending == null || pending.isEmpty()) return

        var changed = false
        if (pending.permissions.isNotEmpty()) {
            revokeNodesAll(player, pending.permissions)
            changed = true
        }
        if (pending.keys.isNotEmpty()) {
            changed = true
        }
        val provider = plugin.permissionProvider
        if (pending.groups.isNotEmpty()) {
            if (provider != null) {
                for (group in pending.groups) {
                    provider.removeGroup(player, group)
                }
            }
            changed = true
        }
        if (pending.effects.isNotEmpty()) {
            for (typeName in pending.effects) {
                try {
                    val type = org.bukkit.potion.PotionEffectType.getByName(typeName)
                    if (type != null) player.removePotionEffect(type)
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to remove pending potion effect '$typeName': " + e.message)
                }
            }
            changed = true
        }

        if (changed) {
            try {
                player.recalculatePermissions()
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to recalculate permissions after pending revokes: " + e.message)
            }
            save()
        }
    }

    fun incrementOwnerKeyCount(owner: UUID?, key: String?, definition: GemDefinition?) {
        if (owner == null || key == null) return
        val map = ownerKeyCount.computeIfAbsent(owner) { HashMap() }
        val before = map.getOrDefault(key, 0)
        val after = before + 1
        map[key] = after
        if (before == 0 && definition != null) {
            val player = Bukkit.getPlayer(owner)
            if (player != null && player.isOnline) {
                val psm = getPSM()
                if (psm != null && !isGemToggledOff(owner, key)) {
                    psm.applyStructure(player, definition.powerStructure, "gem_redeem", key, false)
                }
                grantAppointPermissions(player, definition)
                reconcileCollectThresholdGroups(player)
                try {
                    player.recalculatePermissions()
                } catch (e: Throwable) {
                    plugin.logger.fine("Failed to recalculate permissions after incrementing owner key count: " + e.message)
                }
            }
        }
    }

    fun decrementOwnerKeyCount(owner: UUID?, key: String?, definition: GemDefinition?) {
        if (owner == null || key == null) return
        val map = ownerKeyCount.computeIfAbsent(owner) { HashMap() }
        val before = map.getOrDefault(key, 0)
        val after = maxOf(0, before - 1)
        map[key] = after
        if (after == 0 && definition != null) {
            val player = Bukkit.getPlayer(owner)
            if (player != null && player.isOnline) {
                val psm = getPSM()
                if (psm != null) {
                    psm.removeStructure(player, definition.powerStructure, "gem_redeem", key)
                }
                revokeAppointPermissions(player, definition)
                try {
                    player.recalculatePermissions()
                } catch (e: Throwable) {
                    plugin.logger.fine("Failed to recalculate permissions after decrementing owner key count: " + e.message)
                }
                val logger = historyLogger
                if (logger != null) {
                val language = languageManagerOrNull()
                logger.logPermissionRevoke(
                    owner.toString(),
                    player.name,
                    key,
                    definition.displayName,
                    definition.permissions,
                    definition.vaultGroup,
                    language?.getMessage("history_reason.ownership_lost") ?: "ownership_lost",
                )
                }
            } else {
                val permsToRevoke = ArrayList<String>()
                permsToRevoke.addAll(definition.permissions)
                permsToRevoke.addAll(getAppointPermissionNodes(definition))
                pendingRevokes.computeIfAbsent(owner) { PendingRevoke() }.keys.add(key)
                val vaultGroup = definition.vaultGroup
                queueOfflineRevokes(
                    owner,
                    permsToRevoke,
                    if (!vaultGroup.isNullOrEmpty()) Collections.singleton(vaultGroup) else emptySet(),
                )
                queueOfflineEffectRevokes(owner, definition.effects)
                queueOfflineThresholdGroupRevokes(owner)

                val appointFeature = featureManagerOrNull()?.appointFeature
                if (appointFeature != null && appointFeature.isEnabled) {
                    for (appointKey in definition.powerStructure.appoints.keys) {
                        appointFeature.onAppointerLostPermission(owner, appointKey)
                    }
                }

                val currentAllowanceManager = allowanceManager
                if (currentAllowanceManager != null) {
                    val perRedeemed = currentAllowanceManager.playerGemRedeemUses[owner]
                    if (perRedeemed != null) {
                        for ((gemId, redeemer) in gemIdToRedeemer) {
                            if (owner == redeemer) {
                                val gemKey = stateManager.gemUuidToKey[gemId]
                                if (key.equals(gemKey, ignoreCase = true)) {
                                    perRedeemed.remove(gemId)
                                }
                            }
                        }
                        if (perRedeemed.isEmpty()) currentAllowanceManager.playerGemRedeemUses.remove(owner)
                    }
                }

                val logger = historyLogger
                if (logger != null) {
                    val language = languageManagerOrNull()
                    logger.logPermissionRevoke(
                        owner.toString(),
                        language?.getMessage("player.unknown_offline") ?: "Unknown",
                        key,
                        definition.displayName,
                        definition.permissions,
                        definition.vaultGroup,
                        language?.getMessage("history_reason.ownership_lost_offline") ?: "ownership_lost_offline",
                    )
                }
            }
            if (player != null && player.isOnline) {
                reconcileCollectThresholdGroups(player)
            }
        }
    }

    private fun reconcileCollectThresholdGroups(player: Player?) {
        if (player == null) return
        val playerId = player.uniqueId ?: return
        val desired = desiredCollectThresholdGroups(playerId)
        val applied = collectThresholdGroups.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }
        val provider = plugin.permissionProvider

        for (group in HashSet(applied)) {
            if (!desired.contains(group)) {
                provider?.removeGroup(player, group)
                applied.remove(group)
            }
        }
        for (group in desired) {
            if (applied.add(group)) {
                provider?.addGroup(player, group)
            }
        }
        if (applied.isEmpty()) collectThresholdGroups.remove(playerId)
    }

    private fun desiredCollectThresholdGroups(playerId: UUID): Set<String> {
        val thresholds: Map<Int, String>? = gameplayConfig.gemCollectThresholdGroups
        if (thresholds.isNullOrEmpty()) return emptySet()
        val ownedTypes = countOwnedGemTypes(playerId)
        val desired: MutableSet<String> = LinkedHashSet()
        for ((threshold, group) in thresholds) {
            if (threshold <= ownedTypes && group.isNotBlank()) {
                desired.add(group.trim())
            }
        }
        return desired
    }

    private fun countOwnedGemTypes(playerId: UUID): Int {
        val counts = ownerKeyCount[playerId]
        if (counts.isNullOrEmpty()) return 0
        var ownedTypes = 0
        for (count in counts.values) {
            if (count > 0) ownedTypes++
        }
        return ownedTypes
    }

    private fun clearCollectThresholdGroups(player: Player?) {
        if (player == null) return
        val playerId = player.uniqueId
        val applied = collectThresholdGroups.remove(playerId)
        if (applied.isNullOrEmpty()) return
        val provider = plugin.permissionProvider
        if (provider != null) {
            for (group in applied) {
                provider.removeGroup(player, group)
            }
        }
    }

    private fun queueOfflineThresholdGroupRevokes(owner: UUID?) {
        val thresholds: Map<Int, String>? = gameplayConfig.gemCollectThresholdGroups
        if (thresholds.isNullOrEmpty()) return
        queueOfflineRevokes(owner, emptyList(), thresholds.values)
    }

    fun getAppointPermissionNodes(definition: GemDefinition?): List<String> {
        val nodes = ArrayList<String>()
        if (definition == null) return nodes
        for (appointKey in definition.powerStructure.appoints.keys) {
            nodes.add("rulegems.appoint.$appointKey")
        }
        return nodes
    }

    fun grantAppointPermissions(player: Player?, definition: GemDefinition?) {
        if (player == null || definition == null) return
        val appointPerms = getAppointPermissionNodes(definition)
        if (appointPerms.isNotEmpty()) {
            val appointPower = PowerStructure()
            appointPower.setPermissions(appointPerms)
            getPSM()?.applyStructure(player, appointPower, "gem_appoint", definition.gemKey, false)
        }
    }

    fun revokeAppointPermissions(player: Player?, definition: GemDefinition?) {
        if (player == null || definition == null) return
        val appointPerms = getAppointPermissionNodes(definition)
        if (appointPerms.isNotEmpty()) {
            val appointPower = PowerStructure()
            appointPower.setPermissions(appointPerms)
            getPSM()?.removeStructure(player, appointPower, "gem_appoint", definition.gemKey)

            val appointFeature = featureManagerOrNull()?.appointFeature
            if (appointFeature != null && appointFeature.isEnabled) {
                for (permission in appointPerms) {
                    val permSetKey = permission.substring("rulegems.appoint.".length)
                    appointFeature.onAppointerLostPermission(player.uniqueId, permSetKey)
                }
            }
        }
    }

    fun recalculateGrants(player: Player?) {
        if (player == null || !gameplayConfig.isInventoryGrantsEnabled) return
        val psm = getPSM()

        val presentKeysOrdered = ArrayList<String>()
        val inventory = player.inventory
        for (item: ItemStack? in inventory.contents) {
            if (!stateManager.isRuleGem(item)) continue
            val id = stateManager.getGemUUID(item)
            val key = stateManager.gemUuidToKey[id] ?: continue
            val normalized = key.lowercase(ROOT_LOCALE)
            if (!presentKeysOrdered.contains(normalized)) presentKeysOrdered.add(normalized)
        }

        val previouslyActive = playerActiveHeldKeys.getOrDefault(player.uniqueId, emptySet())
        val selectedKeys: MutableSet<String> = LinkedHashSet()
        for (key in presentKeysOrdered) {
            if (previouslyActive.contains(key)) selectedKeys.add(key)
        }
        var hasConflict = false
        for (key in presentKeysOrdered) {
            if (selectedKeys.contains(key)) continue
            if (!conflictsWithSelected(key, selectedKeys)) {
                selectedKeys.add(key)
            } else {
                hasConflict = true
            }
        }

        if (hasConflict) {
            val language = plugin.languageManager
            plugin.effectUtils.sendActionBar(player, language.translateColorCodes(language.getMessage("inventory.conflict")))
        }

        val keysToRemove: MutableSet<String> = HashSet(previouslyActive)
        keysToRemove.removeAll(selectedKeys)
        val keysToAdd: MutableSet<String> = HashSet(selectedKeys)
        keysToAdd.removeAll(previouslyActive)

        playerActiveHeldKeys[player.uniqueId] = selectedKeys

        if (psm != null) {
            for (key in keysToRemove) {
                val definition = stateManager.findGemDefinition(key)
                if (definition != null) {
                    psm.removeStructure(player, definition.powerStructure, "gem_inv", key)
                }
            }
            for (key in keysToAdd) {
                val definition = stateManager.findGemDefinition(key)
                if (definition != null) {
                    val invPower = PowerStructure()
                    invPower.setPermissions(definition.permissions)
                    psm.applyStructure(player, invPower, "gem_inv", key, false)
                }
            }
        } else {
            val shouldHave: MutableSet<String> = HashSet()
            for (key in selectedKeys) {
                val definition = stateManager.findGemDefinition(key) ?: continue
                for (node in definition.permissions) {
                    if (node.isNotBlank()) shouldHave.add(node)
                }
            }
            val attachment = invAttachments.computeIfAbsent(player.uniqueId) { player.addAttachment(plugin) }
            val current: Set<String> = HashSet(attachment.permissions.keys)
            for (node in shouldHave) {
                if (!current.contains(node)) attachment.setPermission(node, true)
            }
            for (node in current) {
                if (!shouldHave.contains(node)) attachment.unsetPermission(node)
            }
        }
        player.recalculatePermissions()
    }

    fun conflictsWithSelected(candidateKey: String?, selectedKeys: Set<String>?): Boolean {
        if (candidateKey == null || selectedKeys == null) return false
        val candidate = stateManager.findGemDefinition(candidateKey)
        val candidateMutex: MutableSet<String> = HashSet()
        if (candidate != null) {
            for (value in candidate.mutualExclusive) {
                candidateMutex.add(value.lowercase(ROOT_LOCALE))
            }
        }
        for (selected in selectedKeys) {
            if (candidateMutex.contains(selected.lowercase(ROOT_LOCALE))) return true
            val selectedDefinition = stateManager.findGemDefinition(selected)
            if (selectedDefinition != null) {
                for (value in selectedDefinition.mutualExclusive) {
                    if (value.equals(candidateKey, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    fun revokeAllPlayerPermissions(player: Player?): Boolean {
        if (player == null) return false
        val uid = player.uniqueId
        var hadAny = false
        val psm = getPSM()

        val counts = ownerKeyCount[uid]
        if (!counts.isNullOrEmpty()) {
            hadAny = true
            if (psm != null) {
                psm.clearNamespace(player, "gem_redeem")
                psm.clearNamespace(player, "gem_appoint")
                psm.clearNamespace(player, "gem_inv")
            }
            clearCollectThresholdGroups(player)
            counts.clear()
        }

        if (uid == fullSetOwner) {
            hadAny = true
            val redeemAllPower: PowerStructure? = gameplayConfig.redeemAllPowerStructure
            if (psm != null) {
                if (redeemAllPower != null && redeemAllPower.hasAnyContent()) {
                    psm.removeStructure(player, redeemAllPower, "gem_redeem_all", "full_set")
                }
            } else if (redeemAllPower != null) {
                revokeNodesAll(player, redeemAllPower.permissions)
            }
            fullSetOwner = null
        }

        allowanceManager?.clearPlayerData(uid)
        playerUuidToRedeemedKeys.remove(uid)
        playerActiveHeldKeys.remove(uid)
        gemIdToRedeemer.entries.removeIf { entry -> uid == entry.value }

        val invAtt = invAttachments.remove(uid)
        if (invAtt != null) {
            try {
                player.removeAttachment(invAtt)
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to remove inv permission attachment: " + e.message)
            }
        }
        val redeemAtt = redeemAttachments.remove(uid)
        if (redeemAtt != null) {
            try {
                player.removeAttachment(redeemAtt)
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to remove redeem permission attachment: " + e.message)
            }
        }

        try {
            player.recalculatePermissions()
        } catch (e: Throwable) {
            plugin.logger.fine("Failed to recalculate permissions during revokeAll: " + e.message)
        }

        val logger = historyLogger
        if (hadAny && logger != null) {
            val language = languageManagerOrNull()
            logger.logPermissionRevoke(
                uid.toString(),
                player.name,
                "ALL",
                language?.getMessage("history_reason.all_permissions") ?: "ALL",
                emptyList(),
                null,
                language?.getMessage("history_reason.admin_revoke") ?: "admin_revoke",
            )
        }

        save()
        return hadAny
    }

    fun markGemRedeemed(player: Player?, gemKey: String?) {
        if (player == null || gemKey.isNullOrEmpty()) return
        val normalizedKey = gemKey.lowercase(ROOT_LOCALE)
        playerUuidToRedeemedKeys.computeIfAbsent(player.uniqueId) { HashSet() }.add(normalizedKey)
    }

    fun getCurrentRulers(): Map<UUID, Set<String>> {
        val map: MutableMap<UUID, MutableSet<String>> = HashMap()
        val fullOwner = fullSetOwner
        if (fullOwner != null) {
            val pendingKeys = pendingRevokes[fullOwner]?.keys
            if (pendingKeys == null || !pendingKeys.contains("all")) {
                map[fullOwner] = HashSet(Collections.singleton("ALL"))
            }
        }
        for ((gemId, owner) in gemIdToRedeemer) {
            val key = stateManager.gemUuidToKey[gemId] ?: continue
            val normalizedKey = key.lowercase(ROOT_LOCALE)
            val pendingKeys = pendingRevokes[owner]?.keys
            if (pendingKeys != null && pendingKeys.contains(normalizedKey)) continue
            map.computeIfAbsent(owner) { HashSet() }.add(normalizedKey)
        }
        return map
    }

    fun handleInventoryOwnershipOnPickup(player: Player?, gemId: UUID?) {
        if (player == null || gemId == null) return
        if (!gameplayConfig.isInventoryGrantsEnabled) return
        val gemKey = stateManager.gemUuidToKey[gemId] ?: return
        val definition = stateManager.findGemDefinition(gemKey) ?: return
        allowanceManager?.reassignHeldInstanceAllowance(gemId, player.uniqueId, definition)
        val old = gemIdToRedeemer.put(gemId, player.uniqueId)
        val key = gemKey.lowercase(ROOT_LOCALE)
        if (old != null && old != player.uniqueId) {
            decrementOwnerKeyCount(old, key, definition)
        }
        incrementOwnerKeyCount(player.uniqueId, key, definition)
    }

    companion object {
        private val ROOT_LOCALE: Locale = Locale.ROOT
    }
}
