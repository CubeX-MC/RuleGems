package org.cubexmc.commands.sub

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import java.util.Locale

class SetAltarSubCommand(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as Player
        if (args.isEmpty()) {
            languageManager.sendMessage(player, "command.setaltar.usage")
            return true
        }
        val gemKey = args[0].lowercase(Locale.getDefault())
        val definition = gemManager.findGemDefinitionByKey(gemKey)
        if (definition == null) {
            val placeholders = HashMap<String, String>()
            placeholders["gem_key"] = args[0]
            languageManager.sendMessage(player, "command.setaltar.gem_not_found", placeholders)
            return true
        }
        val location = player.location.block.location
        gemManager.setGemAltarLocation(gemKey, location)

        val placeholders = HashMap<String, String>()
        placeholders["gem_key"] = gemKey
        placeholders["gem_name"] = definition.displayName ?: ""
        placeholders["x"] = location.blockX.toString()
        placeholders["y"] = location.blockY.toString()
        placeholders["z"] = location.blockZ.toString()
        placeholders["world"] = location.world?.name ?: "unknown"
        languageManager.sendMessage(player, "command.setaltar.success", placeholders)
        return true
    }
}
