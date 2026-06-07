package org.cubexmc.commands.sub

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.utils.ColorUtils

/**
 * /rulegems appoint &lt;perm_set&gt; &lt;player&gt;
 */
class AppointSubCommand(
    private val plugin: RuleGems,
    @Suppress("unused") private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            languageManager.sendMessage(sender, "command.appoint.usage")
            return true
        }

        val appointFeature = plugin.featureManager.appointFeature
        if (appointFeature == null || !appointFeature.isEnabled) {
            languageManager.sendMessage(sender, "command.appoint.disabled")
            return true
        }

        val rawKey = args[0]
        val targetName = args[1]

        var resolvedKey: String? = null
        var definition: AppointDefinition? = null
        for ((key, value) in appointFeature.appointDefinitions) {
            if (key.equals(rawKey, ignoreCase = true)) {
                resolvedKey = key
                definition = value
                break
            }
        }

        if (resolvedKey == null || definition == null) {
            val placeholders = HashMap<String, String>()
            placeholders["perm_set"] = rawKey
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", placeholders)
            return true
        }
        val permSetKey = resolvedKey

        val appointer = sender as Player
        if (!appointer.hasPermission("rulegems.appoint.$permSetKey") && !appointer.hasPermission("rulegems.admin")) {
            languageManager.sendMessage(sender, "command.no_permission")
            return true
        }

        val target = Bukkit.getPlayer(targetName)
        if (target == null) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = targetName
            languageManager.sendMessage(sender, "command.appoint.player_not_found", placeholders)
            return true
        }

        if (target == appointer) {
            languageManager.sendMessage(sender, "command.appoint.cannot_self")
            return true
        }

        if (appointFeature.isAppointed(target.uniqueId, permSetKey)) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = target.name
            placeholders["perm_set"] = ColorUtils.translateColorCodes(definition.displayName ?: "") ?: ""
            languageManager.sendMessage(sender, "command.appoint.already_appointed", placeholders)
            return true
        }

        if (definition.maxAppointments > 0) {
            val currentCount = appointFeature.getAppointmentCountBy(appointer.uniqueId, permSetKey)
            if (currentCount >= definition.maxAppointments) {
                val placeholders = HashMap<String, String>()
                placeholders["max"] = definition.maxAppointments.toString()
                languageManager.sendMessage(sender, "command.appoint.max_reached", placeholders)
                return true
            }
        }

        val success = appointFeature.appoint(appointer, target, permSetKey)
        if (success) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = target.name
            placeholders["perm_set"] = ColorUtils.translateColorCodes(definition.displayName ?: "") ?: ""
            languageManager.sendMessage(sender, "command.appoint.success", placeholders)
        } else {
            languageManager.sendMessage(sender, "command.appoint.failed")
        }
        return true
    }
}
