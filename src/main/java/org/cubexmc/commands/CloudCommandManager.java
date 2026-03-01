package org.cubexmc.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.exception.InvalidCommandSenderException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.sub.AppointSubCommand;
import org.cubexmc.commands.sub.AppointeesSubCommand;
import org.cubexmc.commands.sub.DismissSubCommand;
import org.cubexmc.commands.sub.HistorySubCommand;
import org.cubexmc.commands.sub.PlaceSubCommand;
import org.cubexmc.commands.sub.RedeemSubCommand;
import org.cubexmc.commands.sub.RemoveAltarSubCommand;
import org.cubexmc.commands.sub.RevokeSubCommand;
import org.cubexmc.commands.sub.SetAltarSubCommand;
import org.cubexmc.commands.sub.TpSubCommand;
import org.cubexmc.gui.GUIManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

/**
 * Registers all /rulegems sub-commands via the Incendo Cloud 2.0 framework.
 */
public class CloudCommandManager {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;
    private final GUIManager guiManager;

    // Existing sub-command handler instances (reused from previous implementation)
    private final TpSubCommand tpSubCommand;
    private final PlaceSubCommand placeSubCommand;
    private final RevokeSubCommand revokeSubCommand;
    private final HistorySubCommand historySubCommand;
    private final SetAltarSubCommand setAltarSubCommand;
    private final RemoveAltarSubCommand removeAltarSubCommand;
    private final AppointSubCommand appointSubCommand;
    private final DismissSubCommand dismissSubCommand;
    private final AppointeesSubCommand appointeesSubCommand;

    public CloudCommandManager(RuleGems plugin, GemManager gemManager, GameplayConfig gameplayConfig,
            LanguageManager languageManager, GUIManager guiManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
        this.guiManager = guiManager;

        this.tpSubCommand = new TpSubCommand(plugin, gemManager, languageManager);
        this.placeSubCommand = new PlaceSubCommand(gemManager, languageManager);
        this.revokeSubCommand = new RevokeSubCommand(gemManager, languageManager);
        this.historySubCommand = new HistorySubCommand(plugin, languageManager);
        this.setAltarSubCommand = new SetAltarSubCommand(gemManager, languageManager);
        this.removeAltarSubCommand = new RemoveAltarSubCommand(gemManager, languageManager);
        this.appointSubCommand = new AppointSubCommand(plugin, gemManager, languageManager);
        this.dismissSubCommand = new DismissSubCommand(plugin, gemManager, languageManager);
        this.appointeesSubCommand = new AppointeesSubCommand(plugin, gemManager, languageManager);
    }

    /**
     * Creates the Cloud CommandManager and registers all commands.
     */
    public void registerAll() {
        final LegacyPaperCommandManager<CommandSender> manager;
        try {
            manager = LegacyPaperCommandManager.createNative(
                    plugin,
                    ExecutionCoordinator.simpleCoordinator());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Cloud command manager: " + e.getMessage());
            return;
        }

        // Register Brigadier if the manager supports hooking into it via Commodore
        // (pre-1.20.6)
        if (manager.hasCapability(CloudBukkitCapabilities.BRIGADIER)) {
            manager.registerBrigadier();
        }
        if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }

        // Exception handlers
        manager.exceptionController()
                .registerHandler(NoPermissionException.class,
                        ctx -> languageManager.sendMessage(ctx.context().sender(), "command.no_permission"))
                .registerHandler(InvalidCommandSenderException.class,
                        ctx -> languageManager.sendMessage(ctx.context().sender(), "command.player_only"))
                .registerHandler(InvalidSyntaxException.class,
                        ctx -> languageManager.sendMessage(ctx.context().sender(), "command.invalid_syntax"));

        registerHelp(manager);
        registerReload(manager);
        registerGui(manager);
        registerRulers(manager);
        registerGems(manager);
        registerTp(manager);
        registerScatter(manager);
        registerRedeem(manager);
        registerRedeemAll(manager);
        registerPlace(manager);
        registerRevoke(manager);
        registerHistory(manager);
        registerSetAltar(manager);
        registerRemoveAltar(manager);
        registerAppoint(manager);
        registerDismiss(manager);
        registerAppointees(manager);
    }

    // ======================================================================
    // Individual command registrations
    // ======================================================================

    private void registerHelp(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .handler(ctx -> sendHelp(ctx.sender())));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("help")
                .handler(ctx -> sendHelp(ctx.sender())));
    }

    private void registerReload(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("reload")
                .permission("rulegems.admin")
                .handler(ctx -> {
                    gemManager.saveGems();
                    plugin.loadPlugin();
                    plugin.refreshAllowedCommandProxies();
                    languageManager.sendMessage(ctx.sender(), "command.reload_success");
                }));
    }

    private void registerGui(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("gui")
                .senderType(Player.class)
                .handler(ctx -> {
                    Player p = (Player) ctx.sender();
                    if (guiManager != null)
                        guiManager.openMainMenu(p, p.hasPermission("rulegems.admin"));
                }));
        // alias /rg menu
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("menu")
                .senderType(Player.class)
                .handler(ctx -> {
                    Player p = (Player) ctx.sender();
                    if (guiManager != null)
                        guiManager.openMainMenu(p, p.hasPermission("rulegems.admin"));
                }));
    }

    private void registerRulers(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("rulers")
                .permission("rulegems.rulers")
                .handler(ctx -> {
                    CommandSender sender = ctx.sender();
                    if (sender instanceof Player && guiManager != null) {
                        guiManager.openRulersGUI((Player) sender, sender.hasPermission("rulegems.admin"));
                    } else {
                        Map<UUID, Set<String>> holders = gemManager.getCurrentRulers();
                        if (holders.isEmpty()) {
                            languageManager.sendMessage(sender, "command.no_rulers");
                            return;
                        }
                        for (Map.Entry<UUID, Set<String>> e : holders.entrySet()) {
                            String name = gemManager.getCachedPlayerName(e.getKey());
                            String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                            Map<String, String> ph = new HashMap<>();
                            ph.put("player", name + " (" + extra + ")");
                            languageManager.sendMessage(sender, "command.rulers_status", ph);
                        }
                    }
                }));
    }

    private void registerGems(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("gems")
                .permission("rulegems.gems")
                .handler(ctx -> {
                    CommandSender sender = ctx.sender();
                    if (sender instanceof Player && guiManager != null) {
                        guiManager.openGemsGUI((Player) sender, sender.hasPermission("rulegems.admin"));
                    } else {
                        gemManager.gemStatus(sender);
                    }
                }));
    }

    private void registerTp(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("tp")
                .permission("rulegems.admin")
                .senderType(Player.class)
                .required("gem_id", StringParser.stringParser())
                .handler(ctx -> tpSubCommand.execute(ctx.sender(), new String[] { ctx.get("gem_id") })));
    }

    private void registerScatter(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("scatter")
                .permission("rulegems.admin")
                .handler(ctx -> {
                    gemManager.scatterGems();
                    languageManager.sendMessage(ctx.sender(), "command.scatter_success");
                }));
    }

    private void registerRedeem(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("redeem")
                .permission("rulegems.redeem")
                .senderType(Player.class)
                .handler(ctx -> {
                    if (!gameplayConfig.isRedeemEnabled()) {
                        languageManager.sendMessage(ctx.sender(), "command.redeem.disabled");
                        return;
                    }
                    new RedeemSubCommand(gemManager, languageManager).execute(ctx.sender(), new String[0]);
                }));
    }

    private void registerRedeemAll(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("redeemall")
                .permission("rulegems.redeemall")
                .senderType(Player.class)
                .handler(ctx -> {
                    if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
                        languageManager.sendMessage(ctx.sender(), "command.redeemall.disabled");
                        return;
                    }
                    boolean ok = gemManager.redeemAll((Player) ctx.sender());
                    if (!ok) {
                        languageManager.sendMessage(ctx.sender(), "command.redeemall.failed");
                        return;
                    }
                    languageManager.sendMessage(ctx.sender(), "command.redeemall.success");
                }));
    }

    private void registerPlace(CommandManager<CommandSender> m) {
        // /rg place <gemId> <x> <y> <z>
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("place")
                .permission("rulegems.admin")
                .senderType(Player.class)
                .required("gem_id", StringParser.stringParser())
                .required("x", StringParser.stringParser())
                .required("y", StringParser.stringParser())
                .required("z", StringParser.stringParser())
                .handler(ctx -> {
                    String[] args = new String[] {
                            ctx.get("gem_id"),
                            ctx.get("x"),
                            ctx.get("y"),
                            ctx.get("z")
                    };
                    placeSubCommand.execute(ctx.sender(), args);
                }));
    }

    private void registerRevoke(CommandManager<CommandSender> m) {
        // /rg revoke <player> [gemKey]
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("revoke")
                .permission("rulegems.admin")
                .required("player_name", StringParser.stringParser())
                .optional("gem_key", StringParser.stringParser())
                .handler(ctx -> {
                    String playerName = ctx.get("player_name");
                    String gemKey = ctx.contains("gem_key") ? ctx.get("gem_key") : null;
                    String[] args = gemKey != null
                            ? new String[] { playerName, gemKey }
                            : new String[] { playerName };
                    revokeSubCommand.execute(ctx.sender(), args);
                }));
    }

    private void registerHistory(CommandManager<CommandSender> m) {
        // /rg history [page] [player]
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("history")
                .permission("rulegems.admin")
                .optional("page", IntegerParser.integerParser(1))
                .optional("player_name", StringParser.stringParser())
                .handler(ctx -> {
                    int page = ctx.contains("page") ? (int) ctx.get("page") : 1;
                    String player = ctx.contains("player_name") ? ctx.get("player_name") : null;
                    String[] args = player != null
                            ? new String[] { String.valueOf(page), player }
                            : new String[] { String.valueOf(page) };
                    historySubCommand.execute(ctx.sender(), args);
                }));
    }

    private void registerSetAltar(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("setaltar")
                .permission("rulegems.admin")
                .senderType(Player.class)
                .handler(ctx -> setAltarSubCommand.execute(ctx.sender(), new String[0])));
    }

    private void registerRemoveAltar(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("removealtar")
                .permission("rulegems.admin")
                .senderType(Player.class)
                .handler(ctx -> removeAltarSubCommand.execute(ctx.sender(), new String[0])));
    }

    private void registerAppoint(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("appoint")
                .permission("rulegems.admin")
                .required("args", StringParser.greedyStringParser())
                .handler(ctx -> {
                    String raw = ctx.get("args");
                    appointSubCommand.execute(ctx.sender(), raw.split(" "));
                }));
    }

    private void registerDismiss(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("dismiss")
                .permission("rulegems.admin")
                .required("args", StringParser.greedyStringParser())
                .handler(ctx -> {
                    String raw = ctx.get("args");
                    dismissSubCommand.execute(ctx.sender(), raw.split(" "));
                }));
    }

    private void registerAppointees(CommandManager<CommandSender> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("appointees")
                .permission("rulegems.admin")
                .handler(ctx -> appointeesSubCommand.execute(ctx.sender(), new String[0])));
    }

    // ======================================================================
    // Help
    // ======================================================================

    private void sendHelp(CommandSender sender) {
        languageManager.sendMessage(sender, "command.help.header");
        languageManager.sendMessage(sender, "command.help.gui");
        languageManager.sendMessage(sender, "command.help.place");
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
        languageManager.sendMessage(sender, "command.help.rulers");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.history");
        languageManager.sendMessage(sender, "command.help.setaltar");
        languageManager.sendMessage(sender, "command.help.removealtar");
        languageManager.sendMessage(sender, "command.help.appoint");
        languageManager.sendMessage(sender, "command.help.dismiss");
        languageManager.sendMessage(sender, "command.help.appointees");
        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.footer");
    }
}
