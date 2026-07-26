package org.cubexmc.manager

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.model.GemDefinition
import java.util.Locale
import java.util.UUID

/**
 * Performs a side-effect-free validation pass before live gem state is torn
 * down. Storage syntax errors are handled by the provider; this validator
 * checks the persisted RuleGems schema and cross-section invariants.
 */
object GemDataValidator {
    class ValidationResult private constructor(
        val valid: Boolean,
        val errors: List<String>,
    ) {
        companion object {
            fun valid(): ValidationResult = ValidationResult(true, emptyList())

            fun invalid(errors: List<String>): ValidationResult =
                ValidationResult(false, errors.toList())
        }
    }

    fun validate(data: FileConfiguration, definitions: List<GemDefinition>?): ValidationResult {
        val errors = ArrayList<String>()
        val configuredKeys = definitions
            .orEmpty()
            .mapNotNull { it.gemKey?.trim()?.takeIf(String::isNotEmpty) }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val placedIds = LinkedHashSet<UUID>()
        val heldIds = LinkedHashSet<UUID>()

        validatePlacedSection(data, "placed-gems", configuredKeys, placedIds, errors)
        validatePlacedSection(data, "placed-gams", configuredKeys, placedIds, errors)
        validateHeldSection(data, configuredKeys, heldIds, errors)

        val duplicateIds = placedIds.intersect(heldIds)
        if (duplicateIds.isNotEmpty()) {
            errors.add("Gem UUIDs appear in both placed-gems and held-gems: ${duplicateIds.joinToString()}")
        }

        validateUuidKeyedListSection(data, "redeemed", errors)
        validateUuidValueMap(data, "redeem_owner_by_id", errors)
        validateOptionalUuid(data, "full_set_owner.uuid", errors)
        validateUuidKeyedListSection(data, "toggled_off_gems", errors)
        validateUuidKeyedListSection(data, "pending_revokes.permissions", errors)
        validateUuidKeyedListSection(data, "pending_revokes.groups", errors)
        validateUuidKeyedListSection(data, "pending_revokes.keys", errors)
        validateUuidKeyedListSection(data, "pending_revokes.effects", errors)
        validatePlayerNames(data, errors)
        validateAllowances(data, errors)

        return if (errors.isEmpty()) ValidationResult.valid() else ValidationResult.invalid(errors)
    }

    private fun validatePlacedSection(
        data: FileConfiguration,
        path: String,
        configuredKeys: Set<String>,
        ids: MutableSet<UUID>,
        errors: MutableList<String>,
    ) {
        val section = sectionOrError(data, path, errors) ?: return
        for (rawId in section.getKeys(false)) {
            val entryPath = "$path.$rawId"
            val id = parseUuid(rawId, entryPath, errors)
            if (id != null && !ids.add(id)) {
                errors.add("Duplicate placed gem UUID: $id")
            }
            val world = section.getString("$rawId.world")
            if (world.isNullOrBlank()) {
                errors.add("$entryPath.world must be a non-empty world name")
            }
            validateCoordinate(section, "$rawId.x", "$entryPath.x", errors)
            validateCoordinate(section, "$rawId.y", "$entryPath.y", errors)
            validateCoordinate(section, "$rawId.z", "$entryPath.z", errors)
            validateGemKey(section.getString("$rawId.gem_key"), "$entryPath.gem_key", configuredKeys, errors)
        }
    }

    private fun validateHeldSection(
        data: FileConfiguration,
        configuredKeys: Set<String>,
        ids: MutableSet<UUID>,
        errors: MutableList<String>,
    ) {
        val path = "held-gems"
        val section = sectionOrError(data, path, errors) ?: return
        for (rawId in section.getKeys(false)) {
            val entryPath = "$path.$rawId"
            val id = parseUuid(rawId, entryPath, errors)
            if (id != null && !ids.add(id)) {
                errors.add("Duplicate held gem UUID: $id")
            }
            parseUuid(section.getString("$rawId.player_uuid"), "$entryPath.player_uuid", errors)
            validateGemKey(section.getString("$rawId.gem_key"), "$entryPath.gem_key", configuredKeys, errors)
        }
    }

    private fun validateGemKey(
        rawKey: String?,
        path: String,
        configuredKeys: Set<String>,
        errors: MutableList<String>,
    ) {
        if (rawKey.isNullOrBlank()) {
            errors.add("$path must be a non-empty configured gem key")
            return
        }
        if (configuredKeys.isNotEmpty() && rawKey.lowercase(Locale.ROOT) !in configuredKeys) {
            errors.add("$path references unknown configured gem key '$rawKey'")
        }
    }

    private fun validateCoordinate(
        section: ConfigurationSection,
        relativePath: String,
        displayPath: String,
        errors: MutableList<String>,
    ) {
        val value = section.get(relativePath)
        if (value !is Number || !value.toDouble().isFinite()) {
            errors.add("$displayPath must be a finite number")
        }
    }

    private fun validateUuidKeyedListSection(
        data: FileConfiguration,
        path: String,
        errors: MutableList<String>,
    ) {
        val section = sectionOrError(data, path, errors) ?: return
        for (rawId in section.getKeys(false)) {
            parseUuid(rawId, "$path.$rawId", errors)
            if (!section.isList(rawId)) {
                errors.add("$path.$rawId must be a list")
            }
        }
    }

    private fun validateUuidValueMap(
        data: FileConfiguration,
        path: String,
        errors: MutableList<String>,
    ) {
        val section = sectionOrError(data, path, errors) ?: return
        for (rawId in section.getKeys(false)) {
            parseUuid(rawId, "$path.$rawId", errors)
            parseUuid(section.getString(rawId), "$path.$rawId value", errors)
        }
    }

    private fun validateOptionalUuid(
        data: FileConfiguration,
        path: String,
        errors: MutableList<String>,
    ) {
        if (data.contains(path)) {
            parseUuid(data.getString(path), path, errors)
        }
    }

    private fun validatePlayerNames(data: FileConfiguration, errors: MutableList<String>) {
        val path = "player_names"
        val section = sectionOrError(data, path, errors) ?: return
        for (rawId in section.getKeys(false)) {
            parseUuid(rawId, "$path.$rawId", errors)
            if (section.getString(rawId).isNullOrBlank()) {
                errors.add("$path.$rawId must be a non-empty player name")
            }
        }
    }

    private fun validateAllowances(data: FileConfiguration, errors: MutableList<String>) {
        val rootPath = "allowed_uses"
        val root = sectionOrError(data, rootPath, errors) ?: return
        for (rawPlayerId in root.getKeys(false)) {
            parseUuid(rawPlayerId, "$rootPath.$rawPlayerId", errors)
            val player = root.getConfigurationSection(rawPlayerId)
            if (player == null) {
                errors.add("$rootPath.$rawPlayerId must be a section")
                continue
            }
            validateAllowanceInstances(player, "held_instances", "$rootPath.$rawPlayerId", errors)
            validateAllowanceInstances(player, "redeemed_instances", "$rootPath.$rawPlayerId", errors)
            validateAllowanceInstances(player, "instances", "$rootPath.$rawPlayerId", errors)
            validateAllowanceCounts(player, "global", "$rootPath.$rawPlayerId", errors)
            validateAllowanceSources(player, "appointments", "$rootPath.$rawPlayerId", errors)
        }
    }

    private fun validateAllowanceInstances(
        player: ConfigurationSection,
        relativePath: String,
        playerPath: String,
        errors: MutableList<String>,
    ) {
        if (!player.contains(relativePath)) return
        val section = player.getConfigurationSection(relativePath)
        if (section == null) {
            errors.add("$playerPath.$relativePath must be a section")
            return
        }
        for (rawGemId in section.getKeys(false)) {
            parseUuid(rawGemId, "$playerPath.$relativePath.$rawGemId", errors)
            validateAllowanceCounts(section, rawGemId, "$playerPath.$relativePath", errors)
        }
    }

    private fun validateAllowanceSources(
        player: ConfigurationSection,
        relativePath: String,
        playerPath: String,
        errors: MutableList<String>,
    ) {
        if (!player.contains(relativePath)) return
        val section = player.getConfigurationSection(relativePath)
        if (section == null) {
            errors.add("$playerPath.$relativePath must be a section")
            return
        }
        for (source in section.getKeys(false)) {
            validateAllowanceCounts(section, source, "$playerPath.$relativePath", errors)
        }
    }

    private fun validateAllowanceCounts(
        parent: ConfigurationSection,
        relativePath: String,
        parentPath: String,
        errors: MutableList<String>,
    ) {
        if (!parent.contains(relativePath)) return
        val section = parent.getConfigurationSection(relativePath)
        if (section == null) {
            errors.add("$parentPath.$relativePath must be a section")
            return
        }
        for (label in section.getKeys(false)) {
            val value = section.get(label)
            if (value !is Number || value.toInt() < 0) {
                errors.add("$parentPath.$relativePath.$label must be a non-negative integer")
            }
        }
    }

    private fun sectionOrError(
        data: FileConfiguration,
        path: String,
        errors: MutableList<String>,
    ): ConfigurationSection? {
        if (!data.contains(path)) return null
        return data.getConfigurationSection(path).also {
            if (it == null) errors.add("$path must be a section")
        }
    }

    private fun parseUuid(raw: String?, path: String, errors: MutableList<String>): UUID? {
        if (raw.isNullOrBlank()) {
            errors.add("$path must contain a UUID")
            return null
        }
        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            errors.add("$path contains invalid UUID '$raw'")
            null
        }
    }
}
