package org.cubexmc.provider

/**
 * Backward-compatible alias for the Bukkit baseline provider.
 */
class FallbackPermissionProvider : BukkitPermissionProvider() {
    override fun getName(): String = "Bukkit"
}
