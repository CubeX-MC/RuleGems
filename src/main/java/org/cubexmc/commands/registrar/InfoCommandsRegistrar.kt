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
        languageManager.sendMessage(sender, "command.help.header")
        languageManager.sendMessage(sender, "command.help.overview")

        val isPlayer = sender is Player
        val isAdmin = sender.hasPermission("rulegems.admin")
        var hasPlayerSection = false

        if (isPlayer) {
            languageManager.sendMessage(sender, "command.help.section_player")
            hasPlayerSection = true
            languageManager.sendMessage(sender, "command.help.gui")
        }
        if (sender.hasPermission("rulegems.gems")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.gems")
        }
        if (sender.hasPermission("rulegems.rulers")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.rulers")
        }
        if (isPlayer && sender.hasPermission("rulegems.profile")) {
            languageManager.sendMessage(sender, "command.help.profile")
        }
        if (gameplayConfig.isRedeemEnabled && sender.hasPermission("rulegems.redeem")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.redeem")
        }
        if (gameplayConfig.isFullSetGrantsAllEnabled && sender.hasPermission("rulegems.redeemall")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.redeemall")
        }
        if (gameplayConfig.isHoldToRedeemEnabled && gameplayConfig.isRedeemEnabled &&
            sender.hasPermission("rulegems.redeem")
        ) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(
                sender,
                if (gameplayConfig.isSneakToRedeem) "command.help.hold_redeem_sneak" else "command.help.hold_redeem_normal",
            )
        }
        if (gameplayConfig.isPlaceRedeemEnabled) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.place_redeem")
        }

        val navigator = plugin.featureManager?.getNavigator()
        if (navigator != null && navigator.isEnabled && sender.hasPermission("rulegems.navigate")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.navigate")
        }

        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature != null && appointFeature.isEnabled && hasAnyAppointPermission(sender, appointFeature)) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.cabinet")
            languageManager.sendMessage(sender, "command.help.appoint")
            languageManager.sendMessage(sender, "command.help.dismiss")
        }

        val revokeFeature = plugin.featureManager?.revokeFeature
        if (revokeFeature != null && revokeFeature.isEnabled && sender.hasPermission("rulegems.revoke")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player")
                hasPlayerSection = true
            }
            languageManager.sendMessage(sender, "command.help.revoke_power")
        }

        if (isAdmin) {
            languageManager.sendMessage(sender, "command.help.section_admin")
            languageManager.sendMessage(sender, "command.help.place")
            languageManager.sendMessage(sender, "command.help.tp")
            languageManager.sendMessage(sender, "command.help.revoke")
            languageManager.sendMessage(sender, "command.help.scatter")
            languageManager.sendMessage(sender, "command.help.history")
            languageManager.sendMessage(sender, "command.help.setaltar")
            languageManager.sendMessage(sender, "command.help.removealtar")
            languageManager.sendMessage(sender, "command.help.appointees")
            languageManager.sendMessage(sender, "command.help.doctor")
            languageManager.sendMessage(sender, "command.help.reload")
        }

        languageManager.sendMessage(sender, "command.help.help")
        languageManager.sendMessage(sender, "command.help.links", linkPlaceholders())
        languageManager.sendMessage(sender, "command.help.footer")
    }

    private fun linkPlaceholders(): Map<String, String> {
        val placeholders = HashMap<String, String>()
        placeholders["docs"] = plugin.config.getString("links.documentation", "https://github.com/angushushu/RuleGems") ?: ""
        placeholders["discord"] = plugin.config.getString("links.discord", "https://discord.com/invite/7tJeSZPZgv") ?: ""
        placeholders["qq"] = plugin.config.getString("links.qq", "https://pd.qq.com/s/1n3hpe4e7?b=9") ?: ""
        return placeholders
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
