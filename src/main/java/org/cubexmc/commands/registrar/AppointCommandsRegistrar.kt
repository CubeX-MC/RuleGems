package org.cubexmc.commands.registrar

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.RuleGemsCommandActor
import org.cubexmc.commands.sub.AppointeesSubCommand
import org.cubexmc.commands.sub.AppointSubCommand
import org.cubexmc.commands.sub.DismissSubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.incendo.cloud.CommandManager
import org.incendo.cloud.parser.standard.StringParser

class AppointCommandsRegistrar(
    plugin: RuleGems,
    gemManager: GemManager,
    private val languageManager: LanguageManager,
    private val suggestionProviders: SuggestionProviders,
) : CommandRegistrar {
    private val appointSubCommand = AppointSubCommand(plugin, gemManager, languageManager)
    private val dismissSubCommand = DismissSubCommand(plugin, gemManager, languageManager)
    private val appointeesSubCommand = AppointeesSubCommand(plugin, gemManager, languageManager)

    override fun register(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("appoint")
                .required("perm_set", StringParser.stringParser(), suggestionProviders.permSetSuggestions())
                .required("player", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    val args = arrayOf(ctx.get<String>("perm_set"), ctx.get<String>("player"))
                    appointSubCommand.execute(player, args)
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("dismiss")
                .required("perm_set", StringParser.stringParser(), suggestionProviders.permSetSuggestions())
                .required("player", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    val args = arrayOf(ctx.get<String>("perm_set"), ctx.get<String>("player"))
                    dismissSubCommand.execute(player, args)
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("appointees")
                .permission("rulegems.admin")
                .optional("perm_set", StringParser.stringParser(), suggestionProviders.allPermSetSuggestions())
                .handler { ctx ->
                    val args = if (ctx.contains("perm_set")) arrayOf(ctx.get<String>("perm_set")) else emptyArray()
                    appointeesSubCommand.execute(ctx.sender().sender(), args)
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
