package org.cubexmc.commands.registrar;

import org.bukkit.entity.Player;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.commands.sub.RedeemSubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.LanguageManager;
import org.incendo.cloud.CommandManager;

public class RedeemCommandsRegistrar implements CommandRegistrar {

    private final GemManager gemManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;

    public RedeemCommandsRegistrar(GemManager gemManager, GameplayConfig gameplayConfig,
            LanguageManager languageManager) {
        this.gemManager = gemManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
    }

    @Override
    public void register(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("redeem")
                .permission("rulegems.redeem")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    if (!gameplayConfig.isRedeemEnabled()) {
                        languageManager.sendMessage(player, "command.redeem.disabled");
                        return;
                    }
                    new RedeemSubCommand(gemManager, languageManager).execute(player, new String[0]);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("redeemall")
                .permission("rulegems.redeemall")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
                        languageManager.sendMessage(player, "command.redeemall.disabled");
                        return;
                    }
                    boolean ok = gemManager.redeemAll(player);
                    languageManager.sendMessage(player, ok ? "command.redeemall.success" : "command.redeemall.failed");
                }));
    }

    private Player requirePlayer(RuleGemsCommandActor actor) {
        Player player = actor.player();
        if (player == null) {
            languageManager.sendMessage(actor.sender(), "command.player_only");
        }
        return player;
    }
}
