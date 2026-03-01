package org.cubexmc.commands;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

public class RuleGemsCommand implements CommandExecutor {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;
    private final GUIManager guiManager;

    /** Ordered map so help output preserves registration order. */
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public RuleGemsCommand(RuleGems plugin, GemManager gemManager, GameplayConfig gameplayConfig,
            LanguageManager languageManager, GUIManager guiManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
        this.guiManager = guiManager;
        registerSubCommands();
    }

    // ====================================================================
    // Sub-command registration
    // ====================================================================

    private void registerSubCommands() {
        register("reload", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.admin";
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                gemManager.saveGems();
                plugin.loadPlugin();
                plugin.refreshAllowedCommandProxies();
                languageManager.sendMessage(s, "command.reload_success");
                return true;
            }
        });

        register("gui", new SubCommand() {
            @Override
            public boolean isPlayerOnly() {
                return true;
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                if (guiManager != null)
                    guiManager.openMainMenu((Player) s, s.hasPermission("rulegems.admin"));
                return true;
            }
        });
        subCommands.put("menu", subCommands.get("gui")); // alias

        register("rulers", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.rulers";
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                if (s instanceof Player && guiManager != null) {
                    guiManager.openRulersGUI((Player) s, s.hasPermission("rulegems.admin"));
                } else {
                    Map<UUID, Set<String>> holders = gemManager.getCurrentRulers();
                    if (holders.isEmpty()) {
                        languageManager.sendMessage(s, "command.no_rulers");
                        return true;
                    }
                    for (Map.Entry<UUID, Set<String>> e : holders.entrySet()) {
                        String name = gemManager.getCachedPlayerName(e.getKey());
                        String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                        Map<String, String> ph = new HashMap<>();
                        ph.put("player", name + " (" + extra + ")");
                        languageManager.sendMessage(s, "command.rulers_status", ph);
                    }
                }
                return true;
            }
        });

        register("gems", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.gems";
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                if (s instanceof Player && guiManager != null) {
                    guiManager.openGemsGUI((Player) s, s.hasPermission("rulegems.admin"));
                } else {
                    gemManager.gemStatus(s);
                }
                return true;
            }
        });

        register("tp", new TpSubCommand(plugin, gemManager, languageManager));

        register("scatter", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.admin";
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                gemManager.scatterGems();
                languageManager.sendMessage(s, "command.scatter_success");
                return true;
            }
        });

        register("redeem", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.redeem";
            }

            @Override
            public boolean isPlayerOnly() {
                return true;
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                if (!gameplayConfig.isRedeemEnabled()) {
                    languageManager.sendMessage(s, "command.redeem.disabled");
                    return true;
                }
                return new RedeemSubCommand(gemManager, languageManager).execute(s, a);
            }
        });

        register("redeemall", new SubCommand() {
            @Override
            public String getPermission() {
                return "rulegems.redeemall";
            }

            @Override
            public boolean isPlayerOnly() {
                return true;
            }

            @Override
            public boolean execute(CommandSender s, String[] a) {
                if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(s, "command.redeemall.disabled");
                    return true;
                }
                boolean ok = gemManager.redeemAll((Player) s);
                if (!ok) {
                    languageManager.sendMessage(s, "command.redeemall.failed");
                    return true;
                }
                languageManager.sendMessage(s, "command.redeemall.success");
                return true;
            }
        });

        register("place", new PlaceSubCommand(gemManager, languageManager));
        register("revoke", new RevokeSubCommand(gemManager, languageManager));
        register("history", new HistorySubCommand(plugin, languageManager));
        register("setaltar", new SetAltarSubCommand(gemManager, languageManager));
        register("removealtar", new RemoveAltarSubCommand(gemManager, languageManager));

        register("appoint", new AppointSubCommand(plugin, gemManager, languageManager));
        register("dismiss", new DismissSubCommand(plugin, gemManager, languageManager));
        register("appointees", new AppointeesSubCommand(plugin, gemManager, languageManager));

        register("help", new SubCommand() {
            @Override
            public boolean execute(CommandSender s, String[] a) {
                sendHelp(s);
                return true;
            }
        });
    }

    private void register(String name, SubCommand cmd) {
        subCommands.put(name, cmd);
    }

    // ====================================================================
    // Command dispatch
    // ====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("rulegems"))
            return false;
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        SubCommand handler = subCommands.get(sub);
        if (handler == null) {
            languageManager.sendMessage(sender, "command.unknown_subcommand");
            return true;
        }

        if (handler.getPermission() != null && !sender.hasPermission(handler.getPermission())) {
            languageManager.sendMessage(sender, "command.no_permission");
            return true;
        }
        if (handler.isPlayerOnly() && !(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command." + sub + ".player_only");
            return true;
        }

        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        return handler.execute(sender, subArgs);
    }

    // ====================================================================
    // Help
    // ====================================================================

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
