package org.cubexmc.provider;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;

import net.milkbowl.vault.permission.Permission;

/**
 * A permission provider implementation that bridges Bukkit permission events
 * with the external Vault API.
 */
public class VaultPermissionProvider implements PermissionProvider {

    private final RuleGems plugin;
    private final Permission perms;

    public VaultPermissionProvider(RuleGems plugin, Permission perms) {
        this.plugin = plugin;
        this.perms = perms;
    }

    @Override
    public void addPermission(Player player, String permission) {
        try {
            perms.playerAdd(player, permission);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add Vault permission '" + permission + "' to player '"
                    + player.getName() + "': " + e.getMessage());
        }
    }

    @Override
    public void removePermission(Player player, String permission) {
        try {
            perms.playerRemove(player, permission);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove Vault permission '" + permission + "' from player '"
                    + player.getName() + "': " + e.getMessage());
        }
    }

    @Override
    public void addGroup(Player player, String group) {
        try {
            perms.playerAddGroup(player, group);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add Vault group '" + group + "' to player '" + player.getName()
                    + "': " + e.getMessage());
        }
    }

    @Override
    public void removeGroup(Player player, String group) {
        try {
            perms.playerRemoveGroup(player, group);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove Vault group '" + group + "' from player '" + player.getName()
                    + "': " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "Vault";
    }
}
