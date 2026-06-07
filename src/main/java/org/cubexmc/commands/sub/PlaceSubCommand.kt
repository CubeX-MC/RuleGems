package org.cubexmc.commands.sub

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager

class PlaceSubCommand(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as Player
        // subArgs: <gemId> [<x|~> <y|~> <z|~>]
        if (args.size != 1 && args.size != 4) {
            languageManager.sendMessage(player, "command.place.usage")
            return true
        }
        val world = player.world
        val gemIdentifier = args[0]

        val playerLocation = player.location
        val sx = if (args.size == 1 || args[1] == "~") playerLocation.x.toString() else args[1]
        val sy = if (args.size == 1 || args[2] == "~") playerLocation.y.toString() else args[2]
        val sz = if (args.size == 1 || args[3] == "~") playerLocation.z.toString() else args[3]

        val x: Double
        val y: Double
        val z: Double
        try {
            x = sx.toDouble()
            y = sy.toDouble()
            z = sz.toDouble()
        } catch (_: NumberFormatException) {
            languageManager.sendMessage(player, "command.place.invalid_coordinates")
            return true
        }

        val gemId = gemManager.resolveGemIdentifier(gemIdentifier)
        if (gemId == null) {
            languageManager.sendMessage(player, "command.place.invalid_gem")
            return true
        }

        val location = Location(world, x, y, z)
        if (!location.chunk.isLoaded) {
            location.chunk.load()
        }

        val targetBlock = location.block
        val currentType = targetBlock.type
        val currentGemLocation = gemManager.getGemLocation(gemId)
        if (!isAir(currentType) && !isSameBlock(currentGemLocation, location)) {
            languageManager.sendMessage(player, "command.place.failed_occupied")
            return true
        }

        val material = gemManager.getGemMaterial(gemId)
        if (gemManager.isSupportRequired(material) && !gemManager.hasBlockSupport(location)) {
            languageManager.sendMessage(player, "command.place.failed_unsupported")
            return true
        }
        gemManager.forcePlaceGem(gemId, location)

        val placeholders = HashMap<String, String>()
        placeholders["x"] = x.toString()
        placeholders["y"] = y.toString()
        placeholders["z"] = z.toString()
        languageManager.sendMessage(player, "command.place.success", placeholders)
        return true
    }

    private fun isAir(material: Material): Boolean =
        material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR

    private fun isSameBlock(first: Location?, second: Location?): Boolean {
        if (first == null || second == null || first.world == null || second.world == null) {
            return false
        }
        return first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ
    }
}
