package org.cubexmc.commands.sub

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager

class RevokeSubCommand(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            languageManager.sendMessage(sender, "command.revoke.usage")
            return true
        }
        val targetName = args[0]
        val targetPlayer = Bukkit.getPlayer(targetName)
        if (targetPlayer == null) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = targetName
            languageManager.sendMessage(sender, "command.revoke.player_not_found", placeholders)
            return true
        }
        val revoked = gemManager.revokeAllPlayerPermissions(targetPlayer)
        if (!revoked) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = targetPlayer.name
            languageManager.sendMessage(sender, "command.revoke.no_permissions", placeholders)
            return true
        }
        val placeholders = HashMap<String, String>()
        placeholders["player"] = targetPlayer.name
        languageManager.sendMessage(sender, "command.revoke.success", placeholders)
        languageManager.sendMessage(targetPlayer, "command.revoke.revoked_notice")
        for (online in Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("rulegems.admin")) {
                languageManager.sendMessage(online, "command.revoke.broadcast", placeholders)
            }
        }
        return true
    }
}
