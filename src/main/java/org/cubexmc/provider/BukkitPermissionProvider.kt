package org.cubexmc.provider

import org.bukkit.entity.Player

/**
 * Baseline permission provider for servers without an external permission
 * backend. Runtime permission attachments are managed by RuleGems managers.
 */
open class BukkitPermissionProvider : PermissionProvider {
    override fun addPermission(player: Player, permission: String) {
        // RuleGems applies direct Bukkit PermissionAttachment nodes in managers.
    }

    override fun removePermission(player: Player, permission: String) {
        // RuleGems removes direct Bukkit PermissionAttachment nodes in managers.
    }

    override fun addGroup(player: Player, group: String) {
        // Bukkit has no native persistent group model.
    }

    override fun removeGroup(player: Player, group: String) {
        // Bukkit has no native persistent group model.
    }

    override fun getName(): String = "Bukkit"
}
