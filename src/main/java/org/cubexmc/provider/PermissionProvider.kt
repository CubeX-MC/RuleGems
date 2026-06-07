package org.cubexmc.provider

import org.bukkit.entity.Player

/**
 * Interface for assigning and revoking permissions and groups on a player.
 */
interface PermissionProvider {
    fun isAvailable(): Boolean = true

    fun supportsContext(): Boolean = false

    fun addPermission(player: Player, permission: String)

    fun removePermission(player: Player, permission: String)

    fun addGroup(player: Player, group: String)

    fun removeGroup(player: Player, group: String)

    fun setPermission(player: Player, permission: String, context: Map<String, String>?, value: Boolean): Boolean {
        if (!context.isNullOrEmpty()) {
            return false
        }
        if (value) {
            addPermission(player, permission)
        } else {
            removePermission(player, permission)
        }
        return true
    }

    fun getName(): String
}
