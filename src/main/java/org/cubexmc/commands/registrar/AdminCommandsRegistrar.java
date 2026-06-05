package org.cubexmc.commands.registrar;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.commands.sub.HistorySubCommand;
import org.cubexmc.commands.sub.PlaceSubCommand;
import org.cubexmc.commands.sub.RemoveAltarSubCommand;
import org.cubexmc.commands.sub.RevokePowerSubCommand;
import org.cubexmc.commands.sub.RevokeSubCommand;
import org.cubexmc.commands.sub.SetAltarSubCommand;
import org.cubexmc.commands.sub.TpSubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.manager.RuleGemsDoctor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

public class AdminCommandsRegistrar implements CommandRegistrar {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;
    private final SuggestionProviders suggestionProviders;
    private final TpSubCommand tpSubCommand;
    private final PlaceSubCommand placeSubCommand;
    private final RevokeSubCommand revokeSubCommand;
    private final RevokePowerSubCommand revokePowerSubCommand;
    private final HistorySubCommand historySubCommand;
    private final SetAltarSubCommand setAltarSubCommand;
    private final RemoveAltarSubCommand removeAltarSubCommand;

    public AdminCommandsRegistrar(RuleGems plugin, GemManager gemManager, LanguageManager languageManager,
            SuggestionProviders suggestionProviders) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.languageManager = languageManager;
        this.suggestionProviders = suggestionProviders;
        this.tpSubCommand = new TpSubCommand(plugin, gemManager, languageManager);
        this.placeSubCommand = new PlaceSubCommand(gemManager, languageManager);
        this.revokeSubCommand = new RevokeSubCommand(gemManager, languageManager);
        this.revokePowerSubCommand = new RevokePowerSubCommand(plugin, languageManager);
        this.historySubCommand = new HistorySubCommand(plugin, languageManager);
        this.setAltarSubCommand = new SetAltarSubCommand(gemManager, languageManager);
        this.removeAltarSubCommand = new RemoveAltarSubCommand(gemManager, languageManager);
    }

    @Override
    public void register(CommandManager<RuleGemsCommandActor> m) {
        registerReload(m);
        registerDoctor(m);
        registerTp(m);
        registerScatter(m);
        registerPlace(m);
        registerRevoke(m);
        registerRevokePower(m);
        registerHistory(m);
        registerSetAltar(m);
        registerRemoveAltar(m);
    }

    private void registerReload(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("reload")
                .permission("rulegems.admin")
                .handler(ctx -> {
                    gemManager.saveGemsSync();
                    plugin.loadPlugin();
                    plugin.refreshAllowedCommandProxies();
                    languageManager.sendMessage(ctx.sender().sender(), "command.reload_success");
                }));
    }

    private void registerDoctor(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("doctor")
                .permission("rulegems.admin")
                .handler(ctx -> new RuleGemsDoctor(plugin).sendReport(ctx.sender().sender())));
    }

    private void registerTp(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("tp")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        tpSubCommand.execute(player, new String[] { ctx.get("gem_id") });
                    }
                }));
    }

    private void registerScatter(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("scatter")
                .permission("rulegems.admin")
                .handler(ctx -> {
                    gemManager.scatterGems();
                    languageManager.sendMessage(ctx.sender().sender(), "command.scatter_success");
                }));
    }

    private void registerPlace(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("place")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    String[] args = new String[] {
                            ctx.get("gem_id")
                    };
                    placeSubCommand.execute(player, args);
                }));

        m.command(m.commandBuilder("rulegems", "rg")
                .literal("place")
                .permission("rulegems.admin")
                .required("gem_id", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .required("x", StringParser.stringParser())
                .required("y", StringParser.stringParser())
                .required("z", StringParser.stringParser())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    String[] args = new String[] {
                            ctx.get("gem_id"),
                            ctx.get("x"),
                            ctx.get("y"),
                            ctx.get("z")
                    };
                    placeSubCommand.execute(player, args);
                }));
    }

    private void registerRevoke(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke")
                .permission("rulegems.admin")
                .required("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .optional("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    String playerName = ctx.get("player_name");
                    String gemKey = ctx.contains("gem_key") ? ctx.get("gem_key") : null;
                    String[] args = gemKey != null
                            ? new String[] { playerName, gemKey }
                            : new String[] { playerName };
                    revokeSubCommand.execute(ctx.sender().sender(), args);
                }));
    }

    private void registerRevokePower(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("list")
                .permission("rulegems.revoke")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        revokePowerSubCommand.execute(player, new String[] { "list" });
                    }
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("confirm")
                .permission("rulegems.revoke")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        revokePowerSubCommand.execute(player, new String[] { "confirm" });
                    }
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .literal("cancel")
                .permission("rulegems.revoke")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        revokePowerSubCommand.execute(player, new String[] { "cancel" });
                    }
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke-power")
                .permission("rulegems.revoke")
                .required("rule", StringParser.stringParser())
                .required("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .required("power", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    revokePowerSubCommand.execute(player, new String[] {
                            ctx.get("rule"),
                            ctx.get("player_name"),
                            ctx.get("power")
                    });
                }));
    }

    private void registerHistory(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("history")
                .permission("rulegems.admin")
                .optional("page", IntegerParser.integerParser(1))
                .optional("player_name", StringParser.stringParser(), suggestionProviders.onlinePlayerSuggestions())
                .handler(ctx -> {
                    int page = ctx.contains("page") ? (int) ctx.get("page") : 1;
                    String player = ctx.contains("player_name") ? ctx.get("player_name") : null;
                    String[] args = player != null
                            ? new String[] { String.valueOf(page), player }
                            : new String[] { String.valueOf(page) };
                    historySubCommand.execute(ctx.sender().sender(), args);
                }));
    }

    private void registerSetAltar(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("setaltar")
                .permission("rulegems.admin")
                .required("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        setAltarSubCommand.execute(player, new String[] { ctx.get("gem_key") });
                    }
                }));
    }

    private void registerRemoveAltar(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("removealtar")
                .permission("rulegems.admin")
                .required("gem_key", StringParser.stringParser(), suggestionProviders.gemKeySuggestions())
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player != null) {
                        removeAltarSubCommand.execute(player, new String[] { ctx.get("gem_key") });
                    }
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
