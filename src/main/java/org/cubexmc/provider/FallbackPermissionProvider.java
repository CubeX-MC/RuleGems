package org.cubexmc.provider;

/**
 * Backward-compatible alias for the Bukkit baseline provider.
 */
public class FallbackPermissionProvider extends BukkitPermissionProvider {

    @Override
    public String getName() {
        return "Bukkit";
    }
}
