package org.cubexmc.model

import java.util.Collections

class PowerStructure {
    private var permissionsInternal: MutableList<String> = ArrayList()
    private var vaultGroupsInternal: MutableList<String> = ArrayList()
    private var allowedCommandsInternal: MutableList<AllowedCommand> = ArrayList()
    private var effectsInternal: MutableList<EffectConfig> = ArrayList()
    private var appointsInternal: MutableMap<String, AppointDefinition> = HashMap()
    private var conditionInternal: PowerCondition = PowerCondition()

    val permissions: MutableList<String>
        get() = permissionsInternal
    val vaultGroups: MutableList<String>
        get() = vaultGroupsInternal
    val allowedCommands: MutableList<AllowedCommand>
        get() = allowedCommandsInternal
    val effects: MutableList<EffectConfig>
        get() = effectsInternal
    val appoints: MutableMap<String, AppointDefinition>
        get() = appointsInternal
    val condition: PowerCondition
        get() = conditionInternal

    var vaultGroup: String?
        get() = if (vaultGroupsInternal.isEmpty()) null else vaultGroupsInternal[0]
        set(value) {
            vaultGroupsInternal.clear()
            if (!value.isNullOrEmpty()) {
                vaultGroupsInternal.add(value)
            }
        }

    fun setPermissions(value: List<String>?) {
        permissionsInternal = if (value == null) ArrayList() else ArrayList(value)
    }

    fun setVaultGroups(value: List<String>?) {
        vaultGroupsInternal = if (value == null) ArrayList() else ArrayList(value)
    }

    fun setAllowedCommands(value: List<AllowedCommand>?) {
        allowedCommandsInternal = if (value == null) ArrayList() else ArrayList(value)
    }

    fun setEffects(value: List<EffectConfig>?) {
        effectsInternal = if (value == null) ArrayList() else ArrayList(value)
    }

    fun setAppoints(value: Map<String, AppointDefinition>?) {
        appointsInternal = if (value == null) HashMap() else HashMap(value)
    }

    fun setCondition(value: PowerCondition?) {
        conditionInternal = value ?: PowerCondition()
    }

    fun hasAnyContent(): Boolean =
        permissionsInternal.isNotEmpty() ||
            vaultGroupsInternal.isNotEmpty() ||
            allowedCommandsInternal.isNotEmpty() ||
            effectsInternal.isNotEmpty() ||
            appointsInternal.isNotEmpty()

    fun hasConditions(): Boolean = conditionInternal.hasAnyCondition()

    fun merge(other: PowerStructure?) {
        if (other == null) return
        for (permission in other.permissions) {
            if (!permissionsInternal.contains(permission)) {
                permissionsInternal.add(permission)
            }
        }
        for (group in other.vaultGroups) {
            if (!vaultGroupsInternal.contains(group)) {
                vaultGroupsInternal.add(group)
            }
        }
        for (command in other.allowedCommands) {
            val exists = allowedCommandsInternal.any { it.label.equals(command.label, ignoreCase = true) }
            if (!exists) allowedCommandsInternal.add(command)
        }
        for (effect in other.effects) {
            val exists = effectsInternal.any { it.effectType != null && it.effectType == effect.effectType }
            if (!exists) effectsInternal.add(effect)
        }
        for ((key, value) in other.appoints) {
            if (!appointsInternal.containsKey(key)) {
                appointsInternal[key] = value
            }
        }
    }

    fun copy(): PowerStructure {
        val effectsCopy = ArrayList<EffectConfig>()
        for (effect in effectsInternal) effectsCopy.add(effect.copy())
        val copy = PowerStructure()
        copy.setPermissions(permissionsInternal)
        copy.setVaultGroups(vaultGroupsInternal)
        copy.setAllowedCommands(allowedCommandsInternal)
        copy.setEffects(effectsCopy)
        copy.setAppoints(appointsInternal)
        copy.setCondition(conditionInternal.copy())
        return copy
    }

    fun immutableView(): PowerStructure {
        val view = PowerStructure()
        view.permissionsInternal = Collections.unmodifiableList(permissionsInternal)
        view.vaultGroupsInternal = Collections.unmodifiableList(vaultGroupsInternal)
        view.allowedCommandsInternal = Collections.unmodifiableList(allowedCommandsInternal)
        view.effectsInternal = Collections.unmodifiableList(effectsInternal)
        view.appointsInternal = Collections.unmodifiableMap(appointsInternal)
        view.conditionInternal = conditionInternal
        return view
    }

    override fun toString(): String =
        "PowerStructure{" +
            "permissions=${permissionsInternal.size}" +
            ", vaultGroups=${vaultGroupsInternal.size}" +
            ", allowedCommands=${allowedCommandsInternal.size}" +
            ", effects=${effectsInternal.size}" +
            ", appoints=${appointsInternal.size}" +
            ", hasCondition=${hasConditions()}" +
            '}'
}
