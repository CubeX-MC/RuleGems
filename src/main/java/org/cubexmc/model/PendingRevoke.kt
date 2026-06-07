package org.cubexmc.model

class PendingRevoke {
    val permissions: MutableSet<String>
    val groups: MutableSet<String>
    val keys: MutableSet<String>
    val effects: MutableSet<String>

    constructor() {
        this.permissions = HashSet()
        this.groups = HashSet()
        this.keys = HashSet()
        this.effects = HashSet()
    }

    constructor(
        permissions: Set<String>?,
        groups: Set<String>?,
        keys: Set<String>?,
        effects: Set<String>?,
    ) {
        this.permissions = HashSet(permissions ?: emptySet())
        this.groups = HashSet(groups ?: emptySet())
        this.keys = HashSet(keys ?: emptySet())
        this.effects = HashSet(effects ?: emptySet())
    }

    fun isEmpty(): Boolean = permissions.isEmpty() && groups.isEmpty() && keys.isEmpty() && effects.isEmpty()
}
