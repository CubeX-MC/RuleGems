package org.cubexmc.commands.sub

import org.bukkit.command.CommandSender
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.utils.ColorUtils

/**
 * /rulegems appointees [perm_set]
 */
class AppointeesSubCommand(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val appointFeature = plugin.featureManager.appointFeature
        if (appointFeature == null || !appointFeature.isEnabled) {
            languageManager.sendMessage(sender, "command.appoint.disabled")
            return true
        }

        if (args.isEmpty()) {
            showAll(sender, appointFeature)
        } else {
            showForPermSet(sender, appointFeature, args[0])
        }
        return true
    }

    private fun showAll(sender: CommandSender, appointFeature: AppointFeature) {
        languageManager.sendMessage(sender, "command.appointees.header")

        val definitions = appointFeature.getAppointDefinitions()
        if (definitions.isEmpty()) {
            languageManager.sendMessage(sender, "command.appointees.no_perm_sets")
            return
        }

        for (definition in definitions.values) {
            showPermSetBlock(sender, appointFeature, definition)
        }
    }

    private fun showForPermSet(sender: CommandSender, appointFeature: AppointFeature, rawKey: String) {
        var resolvedDefinition: AppointDefinition? = null
        for ((key, value) in appointFeature.getAppointDefinitions()) {
            if (key.equals(rawKey, ignoreCase = true)) {
                resolvedDefinition = value
                break
            }
        }

        if (resolvedDefinition == null) {
            val placeholders = HashMap<String, String>()
            placeholders["perm_set"] = rawKey
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", placeholders)
            return
        }
        showPermSetBlock(sender, appointFeature, resolvedDefinition)
    }

    private fun showPermSetBlock(sender: CommandSender, appointFeature: AppointFeature, definition: AppointDefinition) {
        val appointees = appointFeature.getAppointees(definition.key)
        val placeholders = HashMap<String, String>()
        placeholders["perm_set"] = ColorUtils.translateColorCodes(definition.displayName ?: "") ?: ""
        placeholders["count"] = appointees.size.toString()
        languageManager.sendMessage(sender, "command.appointees.set_header", placeholders)

        if (appointees.isEmpty()) {
            languageManager.sendMessage(sender, "command.appointees.none")
        } else {
            for (appointment in appointees) {
                val appointeeName = gemManager.getCachedPlayerName(appointment.appointeeUuid)
                val appointerName = if (appointment.appointerUuid != null) {
                    gemManager.getCachedPlayerName(appointment.appointerUuid)
                } else {
                    "System"
                }
                val linePlaceholders = HashMap<String, String>()
                linePlaceholders["appointee"] = appointeeName
                linePlaceholders["appointer"] = appointerName
                languageManager.sendMessage(sender, "command.appointees.entry", linePlaceholders)
            }
        }
    }
}
