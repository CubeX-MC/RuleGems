package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID

/**
 * Immutable identity for a block position. Runtime state can safely retain
 * this value without retaining a mutable Bukkit Location.
 */
data class BlockPosition(
    val worldId: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun toLocation(): Location? {
        val world = Bukkit.getWorld(worldId) ?: Bukkit.getWorld(worldName) ?: return null
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    companion object {
        @JvmStatic
        fun from(location: Location?): BlockPosition? {
            val world = location?.world ?: return null
            return BlockPosition(
                world.uid,
                world.name,
                location.blockX,
                location.blockY,
                location.blockZ,
            )
        }
    }
}
