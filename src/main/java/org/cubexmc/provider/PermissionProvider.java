package org.cubexmc.provider;

import org.bukkit.entity.Player;

/**
 * Interface for assigning and revoking permissions and groups on a player.
 */
public interface PermissionProvider {

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
     * @return The internal name of this provider.
     */
    String getName();
}
