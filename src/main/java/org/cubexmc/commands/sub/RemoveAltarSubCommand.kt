package org.cubexmc.commands.sub

import org.bukkit.command.CommandSender
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import java.util.Locale

class RemoveAltarSubCommand(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            languageManager.sendMessage(sender, "command.removealtar.usage")
            return true
        }
        val gemKey = args[0].lowercase(Locale.getDefault())
        val definition = gemManager.findGemDefinitionByKey(gemKey)
        if (definition == null) {
            val placeholders = HashMap<String, String>()
            placeholders["gem_key"] = args[0]
            languageManager.sendMessage(sender, "command.removealtar.gem_not_found", placeholders)
            return true
        }
        if (definition.altarLocation == null) {
            val placeholders = HashMap<String, String>()
            placeholders["gem_key"] = gemKey
            placeholders["gem_name"] = definition.displayName ?: ""
            languageManager.sendMessage(sender, "command.removealtar.no_altar", placeholders)
            return true
        }
        gemManager.removeGemAltarLocation(gemKey)

        val placeholders = HashMap<String, String>()
        placeholders["gem_key"] = gemKey
        placeholders["gem_name"] = definition.displayName ?: ""
        languageManager.sendMessage(sender, "command.removealtar.success", placeholders)
        return true
    }
}
