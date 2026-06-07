package org.cubexmc.commands.registrar

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.RuleGemsCommandActor
import org.cubexmc.gui.GUIManager
import org.cubexmc.manager.LanguageManager
import org.incendo.cloud.CommandManager

class GuiCommandsRegistrar(
    private val plugin: RuleGems,
    private val guiManager: GUIManager?,
    private val languageManager: LanguageManager,
) : CommandRegistrar {
    override fun register(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("gui")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    guiManager?.openMainMenu(player, player.hasPermission("rulegems.admin"))
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("menu")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    guiManager?.openMainMenu(player, player.hasPermission("rulegems.admin"))
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("profile")
                .permission("rulegems.profile")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    guiManager?.openProfileGUI(player)
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("status")
                .permission("rulegems.profile")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    guiManager?.openProfileGUI(player)
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("cabinet")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    val appointFeature = plugin.featureManager?.appointFeature
                    if (appointFeature == null || !appointFeature.isEnabled) {
                        languageManager.sendMessage(player, "command.appoint.disabled")
                        return@handler
                    }
                    if (guiManager == null || !guiManager.canOpenCabinet(player)) {
                        languageManager.sendMessage(player, "command.no_permission")
                        return@handler
                    }
                    guiManager.openCabinetGUI(player)
                },
        )
    }

    private fun requirePlayer(actor: RuleGemsCommandActor): Player? {
        val player = actor.player()
        if (player == null) {
            languageManager.sendMessage(actor.sender(), "command.player_only")
        }
        return player
    }
}
