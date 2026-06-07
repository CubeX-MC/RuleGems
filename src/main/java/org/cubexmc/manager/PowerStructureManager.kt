package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionAttachment
import org.cubexmc.RuleGems
import org.cubexmc.features.rule.RuleGateFeature
import org.cubexmc.model.EffectConfig
import org.cubexmc.model.PowerStructure
import org.cubexmc.utils.SchedulerUtil
import java.util.UUID

class PowerStructureManager(private val plugin: RuleGems) {
    private val attachmentsByNamespace: MutableMap<String, MutableMap<UUID, PermissionAttachment>> = HashMap()
    private val appliedStructures: MutableMap<String, MutableMap<UUID, MutableSet<String>>> = HashMap()
    private val permissionRefsByNamespace: MutableMap<String, MutableMap<UUID, MutableMap<String, Int>>> = HashMap()
    private val groupRefsByNamespace: MutableMap<String, MutableMap<UUID, MutableMap<String, Int>>> = HashMap()
    private val appliedEffects: MutableMap<String, MutableMap<UUID, MutableMap<String, MutableList<EffectConfig>>>> = HashMap()
    private var ruleGateFeature: RuleGateFeature? = null
    private var effectRefreshTaskHandle: Any? = null

    fun setRuleGateFeature(feature: RuleGateFeature?) {
        ruleGateFeature = feature
    }

    fun applyStructure(
        player: Player?,
        structure: PowerStructure?,
        namespace: String,
        sourceId: String,
        checkCondition: Boolean,
    ): Boolean {
        if (player == null || structure == null) return false

        if (ruleGateFeature != null && !canUsePower(player, namespace, sourceId)) {
            return false
        }

        if (checkCondition && structure.hasConditions()) {
            val condition = structure.condition
            if (!condition.checkConditions(player)) {
                return false
            }
        }

        val playerId = player.uniqueId
        val namespaceAttachments = attachmentsByNamespace.computeIfAbsent(namespace) { HashMap() }
        val attachment = namespaceAttachments[playerId] ?: player.addAttachment(plugin).also {
            namespaceAttachments[playerId] = it
        }

        applyPermissions(playerId, namespace, attachment, structure.permissions)
        applyVaultGroups(player, namespace, structure.vaultGroups)
        applyEffects(player, structure.effects, namespace, sourceId)

        val namespaceApplied = appliedStructures.computeIfAbsent(namespace) { HashMap() }
        namespaceApplied.computeIfAbsent(playerId) { HashSet() }.add(sourceId)

        player.recalculatePermissions()
        return true
    }

    private fun canUsePower(player: Player, namespace: String?, sourceId: String?): Boolean {
        val gate = ruleGateFeature ?: return true
        return if (namespace != null && namespace.startsWith("gem")) {
            gate.canUsePower(player, sourceId)
        } else {
            gate.canUsePower(player)
        }
    }

    fun removeStructure(player: Player?, structure: PowerStructure?, namespace: String, sourceId: String) {
        if (player == null || structure == null) return

        val playerId = player.uniqueId
        val namespaceAttachments = attachmentsByNamespace[namespace]
        if (namespaceAttachments != null) {
            val attachment = namespaceAttachments[playerId]
            if (attachment != null) {
                removePermissions(playerId, namespace, attachment, structure.permissions)
            }
        }

        removeVaultGroups(player, namespace, structure.vaultGroups)
        removeEffects(player, structure.effects, namespace, sourceId)

        val namespaceApplied = appliedStructures[namespace]
        val playerApplied = namespaceApplied?.get(playerId)
        playerApplied?.remove(sourceId)

        player.recalculatePermissions()
    }

    fun clearNamespace(player: Player?, namespace: String) {
        if (player == null) return

        val playerId = player.uniqueId
        val namespaceAttachments = attachmentsByNamespace[namespace]
        if (namespaceAttachments != null) {
            val attachment = namespaceAttachments.remove(playerId)
            if (attachment != null) {
                try {
                    attachment.remove()
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to remove permission attachment: " + e.message)
                }
            }
        }

        clearEffectsForPlayer(player, namespace)
        clearGroupsForPlayer(player, namespace)
        clearRefsForPlayer(namespace, playerId)

        appliedStructures[namespace]?.remove(playerId)

        player.recalculatePermissions()
    }

    fun clearAllInNamespace(namespace: String) {
        val namespaceAttachments = attachmentsByNamespace.remove(namespace)
        if (namespaceAttachments != null) {
            for (attachment in namespaceAttachments.values) {
                try {
                    attachment.remove()
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to remove permission attachment in namespace cleanup: " + e.message)
                }
            }
        }

        val namespaceEffects = appliedEffects.remove(namespace)
        if (namespaceEffects != null) {
            for ((playerId, sourceEffects) in namespaceEffects) {
                val player = Bukkit.getPlayer(playerId)
                if (player != null && player.isOnline) {
                    for (effectList in sourceEffects.values) {
                        for (effect in effectList) {
                            effect.remove(player)
                        }
                    }
                }
            }
        }

        clearAllGroupsInNamespace(namespace)

        appliedStructures.remove(namespace)
        permissionRefsByNamespace.remove(namespace)
        groupRefsByNamespace.remove(namespace)
    }

    private fun applyVaultGroups(player: Player, namespace: String, groups: List<String>?) {
        if (groups.isNullOrEmpty()) return

        val playerId = player.uniqueId
        for (group in groups) {
            if (group.isNotBlank()) {
                val normalized = group.trim()
                if (incrementRef(groupRefsByNamespace, namespace, playerId, normalized) == 1) {
                    plugin.permissionProvider?.addGroup(player, normalized)
                }
            }
        }
    }

    private fun removeVaultGroups(player: Player, namespace: String, groups: List<String>?) {
        if (groups.isNullOrEmpty()) return

        val playerId = player.uniqueId
        for (group in groups) {
            if (group.isNotBlank()) {
                val normalized = group.trim()
                if (decrementRef(groupRefsByNamespace, namespace, playerId, normalized) == 0) {
                    plugin.permissionProvider?.removeGroup(player, normalized)
                }
            }
        }
    }

    private fun applyPermissions(playerId: UUID, namespace: String, attachment: PermissionAttachment?, permissions: List<String>?) {
        if (attachment == null || permissions.isNullOrEmpty()) {
            return
        }
        for (perm in permissions) {
            if (perm.isNotBlank()) {
                val normalized = perm.trim()
                if (incrementRef(permissionRefsByNamespace, namespace, playerId, normalized) == 1) {
                    attachment.setPermission(normalized, true)
                }
            }
        }
    }

    private fun removePermissions(playerId: UUID, namespace: String, attachment: PermissionAttachment?, permissions: List<String>?) {
        if (attachment == null || permissions.isNullOrEmpty()) {
            return
        }
        for (perm in permissions) {
            if (perm.isNotBlank()) {
                val normalized = perm.trim()
                if (decrementRef(permissionRefsByNamespace, namespace, playerId, normalized) == 0) {
                    attachment.unsetPermission(normalized)
                }
            }
        }
    }

    private fun incrementRef(
        refsByNamespace: MutableMap<String, MutableMap<UUID, MutableMap<String, Int>>>,
        namespace: String,
        playerId: UUID,
        key: String,
    ): Int {
        val namespaceRefs = refsByNamespace.computeIfAbsent(namespace) { HashMap() }
        val playerRefs = namespaceRefs.computeIfAbsent(playerId) { HashMap() }
        val next = playerRefs.getOrDefault(key, 0) + 1
        playerRefs[key] = next
        return next
    }

    private fun decrementRef(
        refsByNamespace: MutableMap<String, MutableMap<UUID, MutableMap<String, Int>>>,
        namespace: String,
        playerId: UUID,
        key: String,
    ): Int {
        val namespaceRefs = refsByNamespace[namespace] ?: return 0
        val playerRefs = namespaceRefs[playerId] ?: return 0
        val current = playerRefs.getOrDefault(key, 0)
        if (current <= 1) {
            playerRefs.remove(key)
            if (playerRefs.isEmpty()) {
                namespaceRefs.remove(playerId)
            }
            if (namespaceRefs.isEmpty()) {
                refsByNamespace.remove(namespace)
            }
            return 0
        }
        val next = current - 1
        playerRefs[key] = next
        return next
    }

    private fun clearRefsForPlayer(namespace: String, playerId: UUID) {
        clearRefMapForPlayer(permissionRefsByNamespace, namespace, playerId)
        clearRefMapForPlayer(groupRefsByNamespace, namespace, playerId)
    }

    private fun clearRefMapForPlayer(
        refsByNamespace: MutableMap<String, MutableMap<UUID, MutableMap<String, Int>>>,
        namespace: String,
        playerId: UUID,
    ) {
        val namespaceRefs = refsByNamespace[namespace] ?: return
        namespaceRefs.remove(playerId)
        if (namespaceRefs.isEmpty()) {
            refsByNamespace.remove(namespace)
        }
    }

    private fun clearGroupsForPlayer(player: Player?, namespace: String) {
        if (player == null) return
        val namespaceRefs = groupRefsByNamespace[namespace] ?: return
        val playerRefs = namespaceRefs[player.uniqueId] ?: return
        for (group in HashSet(playerRefs.keys)) {
            plugin.permissionProvider?.removeGroup(player, group)
        }
    }

    private fun clearAllGroupsInNamespace(namespace: String) {
        val namespaceRefs = groupRefsByNamespace[namespace] ?: return
        for ((playerId, refs) in namespaceRefs) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            for (group in HashSet(refs.keys)) {
                plugin.permissionProvider?.removeGroup(player, group)
            }
        }
    }

    private fun applyEffects(player: Player, effects: List<EffectConfig>?, namespace: String, sourceId: String) {
        if (effects.isNullOrEmpty()) return

        val playerId = player.uniqueId
        val namespaceEffects = appliedEffects.computeIfAbsent(namespace) { HashMap() }
        val playerEffects = namespaceEffects.computeIfAbsent(playerId) { HashMap() }
        playerEffects[sourceId] = ArrayList(effects)

        for (effect in effects) {
            effect.apply(player)
        }
    }

    private fun removeEffects(player: Player, effects: List<EffectConfig>?, namespace: String, sourceId: String) {
        if (effects.isNullOrEmpty()) return

        val playerId = player.uniqueId
        val namespaceEffects = appliedEffects[namespace]
        val playerEffects = namespaceEffects?.get(playerId)
        playerEffects?.remove(sourceId)

        for (effect in effects) {
            if (!isEffectProvidedByOtherSource(playerId, namespace, sourceId, effect)) {
                effect.remove(player)
            }
        }
    }

    private fun isEffectProvidedByOtherSource(playerId: UUID, namespace: String, excludeSourceId: String, effect: EffectConfig): Boolean {
        val namespaceEffects = appliedEffects[namespace] ?: return false
        val playerEffects = namespaceEffects[playerId] ?: return false

        for ((sourceId, effects) in playerEffects) {
            if (sourceId == excludeSourceId) continue

            for (otherEffect in effects) {
                if (otherEffect.effectType != null && otherEffect.effectType == effect.effectType) {
                    return true
                }
            }
        }
        return false
    }

    private fun clearEffectsForPlayer(player: Player, namespace: String) {
        val playerId = player.uniqueId
        val namespaceEffects = appliedEffects[namespace] ?: return
        val playerEffects = namespaceEffects.remove(playerId) ?: return

        for (effectList in playerEffects.values) {
            for (effect in effectList) {
                effect.remove(player)
            }
        }
    }

    fun startEffectRefreshTask() {
        if (effectRefreshTaskHandle != null) {
            return
        }
        val interval = EffectConfig.REFRESH_INTERVAL_TICKS.toLong()
        effectRefreshTaskHandle = SchedulerUtil.globalRun(
            plugin,
            {
                for (player in Bukkit.getOnlinePlayers()) {
                    SchedulerUtil.entityRun(
                        plugin,
                        player,
                        {
                            if (player.isOnline) {
                                refreshEffects(player)
                            }
                        },
                        0L,
                        -1L,
                    )
                }
            },
            interval,
            interval,
        )
    }

    fun stopEffectRefreshTask() {
        val handle = effectRefreshTaskHandle
        if (handle != null) {
            SchedulerUtil.cancelTask(handle)
            effectRefreshTaskHandle = null
        }
    }

    fun refreshEffects(player: Player?) {
        if (player == null) {
            return
        }
        val playerId = player.uniqueId
        for (namespace in ArrayList(appliedEffects.keys)) {
            val namespaceEffects = appliedEffects[namespace] ?: continue
            val playerEffects = namespaceEffects[playerId] ?: continue
            for (effectList in ArrayList(playerEffects.values)) {
                for (effect in effectList) {
                    effect.apply(player)
                }
            }
        }
    }

    fun clearAll() {
        for (namespaceEffects in appliedEffects.values) {
            for ((playerId, sourceEffects) in namespaceEffects) {
                val player = Bukkit.getPlayer(playerId)
                if (player != null && player.isOnline) {
                    for (effectList in sourceEffects.values) {
                        for (effect in effectList) {
                            effect.remove(player)
                        }
                    }
                }
            }
        }
        appliedEffects.clear()
        for (namespace in HashSet(groupRefsByNamespace.keys)) {
            clearAllGroupsInNamespace(namespace)
        }

        for (namespaceAttachments in attachmentsByNamespace.values) {
            for (attachment in namespaceAttachments.values) {
                try {
                    attachment.remove()
                } catch (e: Exception) {
                    plugin.logger.fine("Failed to remove permission attachment during cleanup: " + e.message)
                }
            }
        }
        attachmentsByNamespace.clear()
        appliedStructures.clear()
        permissionRefsByNamespace.clear()
        groupRefsByNamespace.clear()
    }
}
