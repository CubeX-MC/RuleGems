package org.cubexmc.commands.registrar

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.RuleGemsCommandActor
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.gui.GUIManager
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.update.RuleGemsLinks
import org.incendo.cloud.CommandManager
import java.util.Locale
import java.util.UUID

class InfoCommandsRegistrar(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val guiManager: GUIManager?,
    private val gameplayConfig: GameplayConfig,
    private val languageManager: LanguageManager,
) : CommandRegistrar {
    override fun register(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .handler { ctx ->
                    val sender = ctx.sender().sender()
                    val player = ctx.sender().player()
                    if (player != null && guiManager != null) {
                        guiManager.openMainMenu(player, player.hasPermission("rulegems.admin"))
                        return@handler
                    }
                    sendHelp(sender)
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("help")
                .permission("rulegems.help")
                .handler { ctx -> sendHelp(ctx.sender().sender()) },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("gems")
                .permission("rulegems.gems")
                .handler { ctx ->
                    val sender = ctx.sender().sender()
                    val player = ctx.sender().player()
                    if (player != null && guiManager != null) {
                        guiManager.openGemsGUI(player, sender.hasPermission("rulegems.admin"))
                    } else {
                        gemManager.gemStatus(sender)
                    }
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("rulers")
                .permission("rulegems.rulers")
                .handler { ctx ->
                    val sender = ctx.sender().sender()
                    val player = ctx.sender().player()
                    if (player != null && guiManager != null) {
                        guiManager.openRulersGUI(player, sender.hasPermission("rulegems.admin"))
                    } else {
                        val holders: Map<UUID, Set<String>> = gemManager.currentRulers
                        if (holders.isEmpty()) {
                            languageManager.sendMessage(sender, "command.no_rulers")
                            return@handler
                        }
                        for ((uuid, keys) in holders) {
                            val name = gemManager.getCachedPlayerName(uuid)
                            val extra = if (keys.contains("ALL")) "ALL" else keys.joinToString(",")
                            val placeholders = HashMap<String, String>()
                            placeholders["player"] = "$name ($extra)"
                            languageManager.sendMessage(sender, "command.rulers_status", placeholders)
                        }
                    }
                },
        )
    }

    fun sendHelp(sender: CommandSender) {
        languageManager.sendMessage(sender, "command.help.title")
        languageManager.sendMessage(sender, "command.help.intro")
        languageManager.sendMessage(sender, "command.help.tip")

        val isPlayer = sender is Player
        val isAdmin = sender.hasPermission("rulegems.admin")
        var hasPlayerSection = false

        if (isPlayer) {
            languageManager.sendMessage(sender, "command.help.spacer")
            languageManager.sendMessage(sender, "command.help.section_player")
            hasPlayerSection = true
            sendHelpItem(sender, "gui")
        }
        if (sender.hasPermission("rulegems.gems")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "gems")
        }
        if (sender.hasPermission("rulegems.rulers")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "rulers")
        }
        if (isPlayer && sender.hasPermission("rulegems.profile")) {
            sendHelpItem(sender, "profile")
        }
        if (gameplayConfig.isRedeemEnabled && sender.hasPermission("rulegems.redeem")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "redeem")
        }
        if (gameplayConfig.isFullSetGrantsAllEnabled && sender.hasPermission("rulegems.redeemall")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "redeemall")
        }
        if (gameplayConfig.isHoldToRedeemEnabled && gameplayConfig.isRedeemEnabled &&
            sender.hasPermission("rulegems.redeem")
        ) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(
                sender,
                if (gameplayConfig.isSneakToRedeem) "hold_redeem_sneak" else "hold_redeem_normal",
            )
        }
        if (gameplayConfig.isPlaceRedeemEnabled) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "place_redeem")
        }

        val navigator = plugin.featureManager?.getNavigator()
        if (navigator != null && navigator.isEnabled && sender.hasPermission("rulegems.navigate")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "navigate")
        }

        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature != null && appointFeature.isEnabled && hasAnyAppointPermission(sender, appointFeature)) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "cabinet")
            sendHelpItem(sender, "appoint")
            sendHelpItem(sender, "dismiss")
        }

        val revokeFeature = plugin.featureManager?.revokeFeature
        if (revokeFeature != null && revokeFeature.isEnabled && sender.hasPermission("rulegems.revoke")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.spacer")
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            sendHelpItem(sender, "revoke_power")
        }

        if (isAdmin) {
            languageManager.sendMessage(sender, "command.help.spacer")
            languageManager.sendMessage(sender, "command.help.section_admin")
            sendHelpItem(sender, "place")
            sendHelpItem(sender, "tp")
            sendHelpItem(sender, "revoke")
            sendHelpItem(sender, "scatter")
            sendHelpItem(sender, "history")
            sendHelpItem(sender, "setaltar")
            sendHelpItem(sender, "removealtar")
            sendHelpItem(sender, "appointees")
            sendHelpItem(sender, "doctor")
            sendHelpItem(sender, "reload")
        }

        languageManager.sendMessage(sender, "command.help.spacer")
        languageManager.sendMessage(sender, "command.help.section_more")
        sendHelpItem(sender, "help")
        val links = RuleGemsLinks.placeholders(plugin.config)
        sendHelpItem(sender, "link_documentation", links)
        sendHelpItem(sender, "link_discord", links)
        sendHelpItem(sender, "link_qq", links)
        languageManager.sendMessage(sender, "command.help.footer")
    }

    private fun sendHelpItem(
        sender: CommandSender,
        key: String,
        placeholders: Map<String, String> = emptyMap(),
    ) {
        val marker = languageManager.formatMessage("messages.command.help.item_marker", emptyMap())
        val item = languageManager.formatMessage("messages.command.help.$key", placeholders)
        sender.sendMessage(languageManager.translateColorCodes(marker + item))
    }

    private fun hasAnyAppointPermission(sender: CommandSender?, feature: AppointFeature?): Boolean {
        if (sender == null || feature == null) {
            return false
        }
        if (sender.hasPermission("rulegems.admin")) {
            return true
        }
        for (key in feature.getAppointDefinitions().keys) {
            if (sender.hasPermission("rulegems.appoint." + key.lowercase(Locale.ROOT)) ||
                sender.hasPermission("rulegems.appoint.$key")
            ) {
                return true
            }
        }
        return false
    }
}
