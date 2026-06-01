package org.cubexmc.provider;

import org.bukkit.entity.Player;

/**
 * Baseline permission provider for servers without an external permission
 * backend. Runtime permission attachments are managed by RuleGems managers.
 */
public class BukkitPermissionProvider implements PermissionProvider {

    @Override
    public void addPermission(Player player, String permission) {
        // RuleGems applies direct Bukkit PermissionAttachment nodes in managers.
    }

    @Override
    public void removePermission(Player player, String permission) {
        // RuleGems removes direct Bukkit PermissionAttachment nodes in managers.
    }

    @Override
    public void addGroup(Player player, String group) {
        // Bukkit has no native persistent group model.
    }

    @Override
    public void removeGroup(Player player, String group) {
        // Bukkit has no native persistent group model.
    }

    @Override
    public String getName() {
        return "Bukkit";
    }
}
