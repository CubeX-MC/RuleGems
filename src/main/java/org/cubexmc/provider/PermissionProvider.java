package org.cubexmc.provider;

import java.util.Map;

import org.bukkit.entity.Player;

/**
 * Interface for assigning and revoking permissions and groups on a player.
 */
public interface PermissionProvider {

    /**
     * @return whether this provider can be used in the current runtime.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * @return whether this provider can assign permission nodes with contextual
     *         metadata.
     */
    default boolean supportsContext() {
        return false;
    }

    /**
     * Adds a permission node to the player.
     * 
     * @param player     The target player
     * @param permission The permission node
     */
    void addPermission(Player player, String permission);

    /**
     * Removes a permission node from the player.
     * 
     * @param player     The target player
     * @param permission The permission node
     */
    void removePermission(Player player, String permission);

    /**
     * Adds the player to a permission group.
     * 
     * @param player The target player
     * @param group  The group name
     */
    void addGroup(Player player, String group);

    /**
     * Removes the player from a permission group.
     * 
     * @param player The target player
     * @param group  The group name
     */
    void removeGroup(Player player, String group);

    /**
     * Sets a permission node with optional backend-specific context.
     *
     * @param player     The target player
     * @param permission The permission node
     * @param context    Optional context key/value pairs
     * @param value      true to grant, false to revoke
     * @return true if the provider handled the request
     */
    default boolean setPermission(Player player, String permission, Map<String, String> context, boolean value) {
        if (context != null && !context.isEmpty()) {
            return false;
        }
        if (value) {
            addPermission(player, permission);
        } else {
            removePermission(player, permission);
        }
        return true;
    }

    /**
     * @return The internal name of this provider.
     */
    String getName();
}
