package org.cubexmc.provider;

import org.bukkit.entity.Player;

/**
 * A fallback permission provider that does not rely on third-party integrations
 * like Vault.
 * Since Bukkit natively supports permission attachments (managed by
 * GemPermissionManager),
 * this provider can simply act as a no-op for external ecosystem hooks.
 */
public class FallbackPermissionProvider implements PermissionProvider {

    @Override
    public void addPermission(Player player, String permission) {
        // Fallback natively relies on Bukkit's PermissionAttachment
    }

    @Override
    public void removePermission(Player player, String permission) {
        // Fallback natively relies on Bukkit's PermissionAttachment
    }

    @Override
    public void addGroup(Player player, String group) {
        // Vault groups are not supported natively by Bukkit
    }

    @Override
    public void removeGroup(Player player, String group) {
        // Vault groups are not supported natively by Bukkit
    }

    @Override
    public String getName() {
        return "None (Fallback)";
    }
}
