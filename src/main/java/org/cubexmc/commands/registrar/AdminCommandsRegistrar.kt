package org.cubexmc.commands.registrar

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.RuleGemsCommandActor
import org.cubexmc.commands.sub.HistorySubCommand
import org.cubexmc.commands.sub.PlaceSubCommand
import org.cubexmc.commands.sub.RemoveAltarSubCommand
import org.cubexmc.commands.sub.RevokePowerSubCommand
import org.cubexmc.commands.sub.RevokeSubCommand
import org.cubexmc.commands.sub.SetAltarSubCommand
import org.cubexmc.commands.sub.TpSubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.manager.RuleGemsDoctor
import org.incendo.cloud.CommandManager
import org.incendo.cloud.parser.standard.IntegerParser
import org.incendo.cloud.parser.standard.StringParser

class AdminCommandsRegistrar(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
    private val suggestionProviders: SuggestionProviders,
) : CommandRegistrar {
    private val tpSubCommand = TpSubCommand(plugin, gemManager, languageManager)
    private val placeSubCommand = PlaceSubCommand(gemManager, languageManager)
    private val revokeSubCommand = RevokeSubCommand(gemManager, languageManager)
    private val revokePowerSubCommand = RevokePowerSubCommand(plugin, languageManager)
    private val historySubCommand = HistorySubCommand(plugin, languageManager)
    private val setAltarSubCommand = SetAltarSubCommand(gemManager, languageManager)
    private val removeAltarSubCommand = RemoveAltarSubCommand(gemManager, languageManager)

    override fun register(manager: CommandManager<RuleGemsCommandActor>) {
        registerReload(manager)
        registerDoctor(manager)
        registerTp(manager)
        registerScatter(manager)
        registerPlace(manager)
        registerRevoke(manager)
        registerRevokePower(manager)
        registerHistory(manager)
        registerSetAltar(manager)
        registerRemoveAltar(manager)
    }

    private fun registerReload(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("reload")
                .permission("rulegems.admin")
                .handler { ctx ->
                    gemManager.saveGemsSync()
                    plugin.loadPlugin()
                    plugin.refreshAllowedCommandProxies()
                    languageManager.sendMessage(ctx.sender().sender(), "command.reload_success")
                },
        )
    }

    private fun registerDoctor(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("doctor")
                .permission("rulegems.admin")
                .handler { ctx -> RuleGemsDoctor(plugin).sendReport(ctx.sender().sender()) },
        )
    }

    private fun registerTp(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("tp")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        tpSubCommand.execute(player, arrayOf(ctx.get<String>("gem_id")))
                    }
                },
        )
    }

    private fun registerScatter(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("scatter")
                .permission("rulegems.admin")
                .handler { ctx ->
                    gemManager.scatterGems()
                    languageManager.sendMessage(ctx.sender().sender(), "command.scatter_success")
                },
        )
    }

    private fun registerPlace(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("place")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    placeSubCommand.execute(player, arrayOf(ctx.get<String>("gem_id")))
                },
        )

        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("place")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .required("x", StringParser.stringParser())
                .required("y", StringParser.stringParser())
                .required("z", StringParser.stringParser())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    placeSubCommand.execute(
                        player,
                        arrayOf(
                            ctx.get<String>("gem_id"),
                            ctx.get<String>("x"),
                            ctx.get<String>("y"),
                            ctx.get<String>("z"),
                        ),
                    )
                },
        )
    }

    private fun registerRevoke(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("revoke")
                .permission("rulegems.admin")
                .required("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .optional("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val playerName = ctx.get<String>("player_name")
                    val gemKey = if (ctx.contains("gem_key")) ctx.get<String>("gem_key") else null
                    val args = if (gemKey != null) arrayOf(playerName, gemKey) else arrayOf(playerName)
                    revokeSubCommand.execute(ctx.sender().sender(), args)
                },
        )
    }

    private fun registerRevokePower(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("list")
                .permission("rulegems.revoke")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        revokePowerSubCommand.execute(player, arrayOf("list"))
                    }
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("confirm")
                .permission("rulegems.revoke")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        revokePowerSubCommand.execute(player, arrayOf("confirm"))
                    }
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("cancel")
                .permission("rulegems.revoke")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        revokePowerSubCommand.execute(player, arrayOf("cancel"))
                    }
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .permission("rulegems.revoke")
                .required("rule", StringParser.stringParser())
                .required("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .required("power", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    revokePowerSubCommand.execute(
                        player,
                        arrayOf(
                            ctx.get<String>("rule"),
                            ctx.get<String>("player_name"),
                            ctx.get<String>("power"),
                        ),
                    )
                },
        )
    }

    private fun registerHistory(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("history")
                .permission("rulegems.admin")
                .optional("page", IntegerParser.integerParser(1))
                .optional("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler { ctx ->
                    val page = if (ctx.contains("page")) ctx.get<Int>("page") else 1
                    val player = if (ctx.contains("player_name")) ctx.get<String>("player_name") else null
                    val args = if (player != null) arrayOf(page.toString(), player) else arrayOf(page.toString())
                    historySubCommand.execute(ctx.sender().sender(), args)
                },
        )
    }

    private fun registerSetAltar(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("setaltar")
                .permission("rulegems.admin")
                .required("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        setAltarSubCommand.execute(player, arrayOf(ctx.get<String>("gem_key")))
                    }
                },
        )
    }

    private fun registerRemoveAltar(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("removealtar")
                .permission("rulegems.admin")
                .required("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender())
                    if (player != null) {
                        removeAltarSubCommand.execute(player, arrayOf(ctx.get<String>("gem_key")))
                    }
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
