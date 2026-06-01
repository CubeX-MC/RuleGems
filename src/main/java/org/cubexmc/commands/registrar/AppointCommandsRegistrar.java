package org.cubexmc.commands.registrar;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.commands.sub.AppointeesSubCommand;
import org.cubexmc.commands.sub.AppointSubCommand;
import org.cubexmc.commands.sub.DismissSubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;

public class AppointCommandsRegistrar implements CommandRegistrar {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;
    private final SuggestionProviders suggestionProviders;
    private final AppointSubCommand appointSubCommand;
    private final DismissSubCommand dismissSubCommand;
    private final AppointeesSubCommand appointeesSubCommand;

    public AppointCommandsRegistrar(RuleGems plugin, GemManager gemManager, LanguageManager languageManager,
            SuggestionProviders suggestionProviders) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.languageManager = languageManager;
        this.suggestionProviders = suggestionProviders;
        this.appointSubCommand = new AppointSubCommand(plugin, gemManager, languageManager);
        this.dismissSubCommand = new DismissSubCommand(plugin, gemManager, languageManager);
        this.appointeesSubCommand = new AppointeesSubCommand(plugin, gemManager, languageManager);
    }

    @Override
    public void register(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("appoint")
                .required("perm_set", StringParser.stringParser(), suggestionProviders.permSetSuggestions())
                .required("player", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    String[] args = new String[] { ctx.get("perm_set"), ctx.get("player") };
                    appointSubCommand.execute(player, args);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("dismiss")
                .required("perm_set", StringParser.stringParser(), suggestionProviders.permSetSuggestions())
                .required("player", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    String[] args = new String[] { ctx.get("perm_set"), ctx.get("player") };
                    dismissSubCommand.execute(player, args);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("appointees")
                .permission("rulegems.admin")
                .optional("perm_set", StringParser.stringParser(), suggestionProviders.allPermSetSuggestions())
                .handler(ctx -> {
                    String[] args = ctx.contains("perm_set") ? new String[] { ctx.get("perm_set") } : new String[0];
                    appointeesSubCommand.execute(ctx.sender().sender(), args);
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
