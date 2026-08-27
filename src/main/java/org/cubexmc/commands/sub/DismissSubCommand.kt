package org.cubexmc.commands.sub

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.core.CubexText
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
        for ((key, value) in appointFeature.getAppointDefinitions()) {
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

        val targetUuid = resolveTarget(appointFeature, permSetKey, targetName)

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
            placeholders["perm_set"] = CubexText.translateColorCodes(definition.displayName ?: "") ?: ""
            languageManager.sendMessage(sender, "command.dismiss.success", placeholders)
        } else {
            val key = if (appointFeature.storageFailure) {
                "command.appointment_storage_failed"
            } else {
                "command.dismiss.failed"
            }
            languageManager.sendMessage(sender, key)
        }
        return true
    }
    private fun resolveTarget(feature: AppointFeature, key: String, name: String): UUID? =
        Bukkit.getPlayer(name)?.uniqueId ?: feature.getAppointees(key).firstOrNull {
            gemManager.getCachedPlayerName(it.appointeeUuid).equals(name, ignoreCase = true)
        }?.appointeeUuid
}
