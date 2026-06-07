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
import java.util.UUID

/**
 * /rulegems dismiss &lt;perm_set&gt; &lt;player&gt;
 */
class DismissSubCommand(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            languageManager.sendMessage(sender, "command.dismiss.usage")
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

        val target = Bukkit.getPlayer(targetName)
        var targetUuid: UUID? = null
        if (target != null) {
            targetUuid = target.uniqueId
        } else {
            for (appointment in appointFeature.getAppointees(permSetKey)) {
                val cachedName = gemManager.getCachedPlayerName(appointment.appointeeUuid)
                if (cachedName.equals(targetName, ignoreCase = true)) {
                    targetUuid = appointment.appointeeUuid
                    break
                }
            }
        }

        if (targetUuid == null) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = targetName
            languageManager.sendMessage(sender, "command.dismiss.not_appointed", placeholders)
            return true
        }

        val dismisser = sender as Player
        val success = appointFeature.dismiss(dismisser, targetUuid, permSetKey)
        if (success) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = targetName
            placeholders["perm_set"] = ColorUtils.translateColorCodes(definition.displayName ?: "") ?: ""
            languageManager.sendMessage(sender, "command.dismiss.success", placeholders)
        } else {
            languageManager.sendMessage(sender, "command.dismiss.failed")
        }
        return true
    }
}
