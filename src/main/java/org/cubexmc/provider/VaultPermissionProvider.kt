package org.cubexmc.provider

import net.milkbowl.vault.permission.Permission
import org.bukkit.entity.Player
import org.cubexmc.RuleGems

/**
 * A permission provider implementation that bridges Bukkit permission events
 * with the external Vault API.
 */
class VaultPermissionProvider(
    private val plugin: RuleGems,
    private val perms: Permission,
) : PermissionProvider {
    override fun addPermission(player: Player, permission: String) {
        try {
            perms.playerAdd(player, permission)
        } catch (e: Exception) {
            plugin.logger.warning(
                "Failed to add Vault permission '$permission' to player '${player.name}': ${e.message}",
            )
        }
    }

    override fun removePermission(player: Player, permission: String) {
        try {
            perms.playerRemove(player, permission)
        } catch (e: Exception) {
            plugin.logger.warning(
                "Failed to remove Vault permission '$permission' from player '${player.name}': ${e.message}",
            )
        }
    }

    override fun addGroup(player: Player, group: String) {
        try {
            perms.playerAddGroup(player, group)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to add Vault group '$group' to player '${player.name}': ${e.message}")
        }
    }

    override fun removeGroup(player: Player, group: String) {
        try {
            perms.playerRemoveGroup(player, group)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to remove Vault group '$group' from player '${player.name}': ${e.message}")
        }
    }

    override fun getName(): String = "Vault"
}
