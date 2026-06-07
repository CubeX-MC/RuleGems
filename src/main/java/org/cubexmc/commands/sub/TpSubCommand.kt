package org.cubexmc.commands.sub

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.utils.SchedulerUtil

class TpSubCommand(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as Player
        if (args.isEmpty()) {
            languageManager.sendMessage(player, "command.tp.usage")
            return true
        }
        val gemId = gemManager.resolveGemIdentifier(args[0])
        if (gemId == null) {
            val placeholders = HashMap<String, String>()
            placeholders["input"] = args[0]
            languageManager.sendMessage(player, "command.tp.not_found", placeholders)
            return true
        }
        // Prefer teleporting to holder, otherwise to placed location
        val realHolder = gemManager.getGemHolder(gemId)
        if (realHolder != null && realHolder.isOnline) {
            SchedulerUtil.safeTeleport(plugin, player, realHolder.location)
            return true
        }
        val location = gemManager.getGemLocation(gemId)
        if (location != null) {
            SchedulerUtil.safeTeleport(plugin, player, location.clone().add(0.5, 1.0, 0.5))
            return true
        }
        languageManager.sendMessage(player, "command.tp.unavailable")
        return true
    }
}
