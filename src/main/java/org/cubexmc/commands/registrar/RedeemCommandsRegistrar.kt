package org.cubexmc.commands.registrar

import org.bukkit.entity.Player
import org.cubexmc.commands.RuleGemsCommandActor
import org.cubexmc.commands.sub.RedeemSubCommand
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.incendo.cloud.CommandManager

class RedeemCommandsRegistrar(
    private val gemManager: GemManager,
    private val gameplayConfig: GameplayConfig,
    private val languageManager: LanguageManager,
) : CommandRegistrar {
    override fun register(manager: CommandManager<RuleGemsCommandActor>) {
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("redeem")
                .permission("rulegems.redeem")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    if (!gameplayConfig.isRedeemEnabled) {
                        languageManager.sendMessage(player, "command.redeem.disabled")
                        return@handler
                    }
                    RedeemSubCommand(gemManager, languageManager).execute(player, emptyArray())
                },
        )
        manager.command(
            manager.commandBuilder("rulegems", "rg")
                .literal("redeemall")
                .permission("rulegems.redeemall")
                .handler { ctx ->
                    val player = requirePlayer(ctx.sender()) ?: return@handler
                    if (!gameplayConfig.isFullSetGrantsAllEnabled) {
                        languageManager.sendMessage(player, "command.redeemall.disabled")
                        return@handler
                    }
                    val ok = gemManager.redeemAll(player)
                    languageManager.sendMessage(player, if (ok) "command.redeemall.success" else "command.redeemall.failed")
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
