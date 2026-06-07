package org.cubexmc.commands.sub

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.features.revoke.RevokeResult
import org.cubexmc.manager.LanguageManager

class RevokePowerSubCommand(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.revoke"

    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            languageManager.sendMessage(sender, "command.player_only")
            return true
        }
        if (!sender.hasPermission(getPermission())) {
            languageManager.sendMessage(sender, "command.no_permission")
            return true
        }
        val feature = plugin.featureManager?.revokeFeature
        if (feature == null) {
            languageManager.sendMessage(sender, "command.revoke_power.disabled")
            return true
        }
        val result = if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            languageManager.sendMessage(sender, "command.revoke_power.usage")
            return true
        } else if (args[0].equals("confirm", ignoreCase = true)) {
            feature.confirm(sender)
        } else if (args[0].equals("cancel", ignoreCase = true)) {
            feature.cancel(sender)
        } else if (args[0].equals("list", ignoreCase = true)) {
            feature.listRules()
        } else {
            if (args.size < 3) {
                languageManager.sendMessage(sender, "command.revoke_power.usage")
                return true
            }
            feature.requestRevoke(sender, args[0], args[1], args[2])
        }
        sendResult(sender, result)
        return true
    }

    private fun sendResult(actor: Player, result: RevokeResult) {
        val placeholders = HashMap(result.placeholders)
        when (result.status) {
            RevokeResult.Status.SUCCESS -> {
                languageManager.sendMessage(actor, "command.revoke_power.success", placeholders)
                if ("true".equals(placeholders["broadcast"], ignoreCase = true)) {
                    for (online in Bukkit.getOnlinePlayers()) {
                        if (online != actor) {
                            languageManager.sendMessage(online, "command.revoke_power.broadcast", placeholders)
                        }
                    }
                }
            }
            RevokeResult.Status.CONFIRMATION_REQUIRED -> {
                languageManager.sendMessage(actor, "command.revoke_power.confirm_prompt", placeholders)
                languageManager.sendMessage(actor, "command.revoke_power.confirm_hint", placeholders)
            }
            RevokeResult.Status.LIST -> {
                val rules = placeholders["rules"] ?: ""
                if (rules.isBlank()) {
                    languageManager.sendMessage(actor, "command.revoke_power.list_empty")
                } else {
                    languageManager.sendMessage(actor, "command.revoke_power.list_header")
                    for (line in rules.split("\\n".toRegex())) {
                        languageManager.sendMessage(actor, "command.revoke_power.list_line", mapOf("line" to line))
                    }
                }
            }
            RevokeResult.Status.DISABLED -> languageManager.sendMessage(actor, "command.revoke_power.disabled")
            RevokeResult.Status.RULE_NOT_FOUND -> languageManager.sendMessage(actor, "command.revoke_power.rule_not_found", placeholders)
            RevokeResult.Status.POWER_NOT_ALLOWED -> languageManager.sendMessage(actor, "command.revoke_power.power_not_allowed", placeholders)
            RevokeResult.Status.TARGET_NOT_FOUND -> languageManager.sendMessage(actor, "command.revoke_power.target_not_found", placeholders)
            RevokeResult.Status.TARGET_OFFLINE_NOT_ALLOWED -> languageManager.sendMessage(actor, "command.revoke_power.target_offline_not_allowed", placeholders)
            RevokeResult.Status.TARGET_HAS_NO_POWER -> languageManager.sendMessage(actor, "command.revoke_power.target_has_no_power", placeholders)
            RevokeResult.Status.MISSING_TRIGGER -> languageManager.sendMessage(actor, "command.revoke_power.missing_trigger", placeholders)
            RevokeResult.Status.COOLDOWN -> languageManager.sendMessage(actor, "command.revoke_power.cooldown", placeholders)
            RevokeResult.Status.NO_PENDING_CONFIRMATION -> languageManager.sendMessage(actor, "command.revoke_power.no_pending_confirmation")
            RevokeResult.Status.CANCELLED -> languageManager.sendMessage(actor, "command.revoke_power.cancelled")
            RevokeResult.Status.CONFIRMATION_EXPIRED -> languageManager.sendMessage(actor, "command.revoke_power.confirmation_expired")
            else -> languageManager.sendMessage(actor, "command.revoke_power.failed")
        }
    }
}
