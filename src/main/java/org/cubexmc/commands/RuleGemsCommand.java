package org.cubexmc.commands;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.sub.AppointSubCommand;
import org.cubexmc.commands.sub.AppointeesSubCommand;
import org.cubexmc.commands.sub.DismissSubCommand;
import org.cubexmc.commands.sub.HistorySubCommand;
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
    //  Sub-command registration
    // ====================================================================

    private void registerSubCommands() {
        register("reload", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean execute(CommandSender s, String[] a) {
                gemManager.saveGems();
                plugin.loadPlugin();
                plugin.refreshAllowedCommandProxies();
                languageManager.sendMessage(s, "command.reload_success");
                return true;
            }
        });

        register("gui", new SubCommand() {
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) {
                if (guiManager != null) guiManager.openMainMenu((Player) s, s.hasPermission("rulegems.admin"));
                return true;
            }
        });
        subCommands.put("menu", subCommands.get("gui")); // alias

        register("rulers", new SubCommand() {
            @Override public String getPermission() { return "rulegems.rulers"; }
            @Override public boolean execute(CommandSender s, String[] a) {
                if (s instanceof Player && guiManager != null) {
                    guiManager.openRulersGUI((Player) s, s.hasPermission("rulegems.admin"));
                } else {
                    Map<UUID, Set<String>> holders = gemManager.getCurrentRulers();
                    if (holders.isEmpty()) { languageManager.sendMessage(s, "command.no_rulers"); return true; }
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
            @Override public String getPermission() { return "rulegems.gems"; }
            @Override public boolean execute(CommandSender s, String[] a) {
                if (s instanceof Player && guiManager != null) {
                    guiManager.openGemsGUI((Player) s, s.hasPermission("rulegems.admin"));
                } else { gemManager.gemStatus(s); }
                return true;
            }
        });

        register("tp", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) { return handleTp((Player) s, a); }
        });

        register("scatter", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean execute(CommandSender s, String[] a) {
                gemManager.scatterGems();
                languageManager.sendMessage(s, "command.scatter_success");
                return true;
            }
        });

        register("redeem", new SubCommand() {
            @Override public String getPermission() { return "rulegems.redeem"; }
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) {
                if (!gameplayConfig.isRedeemEnabled()) {
                    languageManager.sendMessage(s, "command.redeem.disabled");
                    return true;
                }
                return handleRedeem((Player) s);
            }
        });

        register("redeemall", new SubCommand() {
            @Override public String getPermission() { return "rulegems.redeemall"; }
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) {
                if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(s, "command.redeemall.disabled");
                    return true;
                }
                return handleRedeemAll((Player) s);
            }
        });

        register("place", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) { return handlePlace((Player) s, a); }
        });

        register("revoke", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean execute(CommandSender s, String[] a) { return handleRevoke(s, a); }
        });

        register("history", new HistorySubCommand(plugin, languageManager));

        register("setaltar", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean isPlayerOnly() { return true; }
            @Override public boolean execute(CommandSender s, String[] a) { return handleSetAltar((Player) s, a); }
        });

        register("removealtar", new SubCommand() {
            @Override public String getPermission() { return "rulegems.admin"; }
            @Override public boolean execute(CommandSender s, String[] a) { return handleRemoveAltar(s, a); }
        });

        register("appoint", new AppointSubCommand(plugin, gemManager, languageManager));
        register("dismiss", new DismissSubCommand(plugin, gemManager, languageManager));
        register("appointees", new AppointeesSubCommand(plugin, gemManager, languageManager));

        register("help", new SubCommand() {
            @Override public boolean execute(CommandSender s, String[] a) { sendHelp(s); return true; }
        });
    }

    private void register(String name, SubCommand cmd) { subCommands.put(name, cmd); }

    // ====================================================================
    //  Command dispatch
    // ====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("rulegems")) return false;
        if (args.length == 0) { sendHelp(sender); return true; }

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
    //  Help
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

    // ====================================================================
    //  Inline sub-command implementations
    // ====================================================================

    private boolean handleTp(Player player, String[] args) {
        if (args.length < 1) {
            languageManager.sendMessage(player, "command.tp.usage");
            return true;
        }
        UUID gemId = gemManager.resolveGemIdentifier(args[0]);
        if (gemId == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("input", args[0]);
            languageManager.sendMessage(player, "command.tp.not_found", placeholders);
            return true;
        }
        // Prefer teleporting to holder, otherwise to placed location
        Player realHolder = gemManager.getGemHolder(gemId);
        if (realHolder != null && realHolder.isOnline()) {
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, player, realHolder.getLocation());
            return true;
        }
        Location loc = gemManager.getGemLocation(gemId);
        if (loc != null) {
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, player, loc.clone().add(0.5, 1.0, 0.5));
            return true;
        }
        languageManager.sendMessage(player, "command.tp.unavailable");
        return true;
    }

    private boolean handlePlace(Player player, String[] args) {
        // subArgs: <gemId> <x|~> <y|~> <z|~>
        if (args.length < 4) {
            languageManager.sendMessage(player, "command.place.usage");
            return true;
        }
        World world = player.getWorld();
        String gemIdentifier = args[0];

        String sx = args[1].equals("~") ? String.valueOf(player.getLocation().getX()) : args[1];
        String sy = args[2].equals("~") ? String.valueOf(player.getLocation().getY()) : args[2];
        String sz = args[3].equals("~") ? String.valueOf(player.getLocation().getZ()) : args[3];

        double x, y, z;
        try {
            x = Double.parseDouble(sx);
            y = Double.parseDouble(sy);
            z = Double.parseDouble(sz);
        } catch (NumberFormatException e) {
            languageManager.sendMessage(player, "command.place.invalid_coordinates");
            return true;
        }

        UUID gemId = gemManager.resolveGemIdentifier(gemIdentifier);
        if (gemId == null) {
            languageManager.sendMessage(player, "command.place.invalid_gem");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) loc.getChunk().load();

        org.bukkit.Material m = gemManager.getGemMaterial(gemId);
        if (gemManager.isSupportRequired(m) && !gemManager.hasBlockSupport(loc)) {
            languageManager.sendMessage(player, "command.place.failed_unsupported");
            return true;
        }
        gemManager.forcePlaceGem(gemId, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("x", String.valueOf(x));
        placeholders.put("y", String.valueOf(y));
        placeholders.put("z", String.valueOf(z));
        languageManager.sendMessage(player, "command.place.success", placeholders);
        return true;
    }

    private boolean handleRedeem(Player player) {
        org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == org.bukkit.Material.AIR) {
            languageManager.sendMessage(player, "command.redeem.no_item_in_hand");
            return true;
        }
        if (!gemManager.isRuleGem(inHand)) {
            languageManager.sendMessage(player, "command.redeem.not_a_gem");
            return true;
        }
        boolean ok = gemManager.redeemGemInHand(player);
        if (!ok) {
            languageManager.sendMessage(player, "command.redeem.failed");
            return true;
        }
        languageManager.sendMessage(player, "command.redeem.success");
        return true;
    }

    private boolean handleRedeemAll(Player player) {
        boolean ok = gemManager.redeemAll(player);
        if (!ok) {
            languageManager.sendMessage(player, "command.redeemall.failed");
            return true;
        }
        languageManager.sendMessage(player, "command.redeemall.success");
        return true;
    }

    private boolean handleRevoke(CommandSender sender, String[] args) {
        if (args.length < 1) {
            languageManager.sendMessage(sender, "command.revoke.usage");
            return true;
        }
        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            languageManager.sendMessage(sender, "command.revoke.player_not_found", placeholders);
            return true;
        }
        boolean revoked = gemManager.revokeAllPlayerPermissions(targetPlayer);
        if (!revoked) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetPlayer.getName());
            languageManager.sendMessage(sender, "command.revoke.no_permissions", placeholders);
            return true;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", targetPlayer.getName());
        languageManager.sendMessage(sender, "command.revoke.success", placeholders);
        languageManager.sendMessage(targetPlayer, "command.revoke.revoked_notice");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("rulegems.admin")) {
                languageManager.sendMessage(online, "command.revoke.broadcast", placeholders);
            }
        }
        return true;
    }

    private boolean handleSetAltar(Player player, String[] args) {
        if (args.length < 1) {
            languageManager.sendMessage(player, "command.setaltar.usage");
            return true;
        }
        String gemKey = args[0].toLowerCase();
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[0]);
            languageManager.sendMessage(player, "command.setaltar.gem_not_found", placeholders);
            return true;
        }
        Location loc = player.getLocation().getBlock().getLocation();
        gemManager.setGemAltarLocation(gemKey, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        placeholders.put("x", String.valueOf(loc.getBlockX()));
        placeholders.put("y", String.valueOf(loc.getBlockY()));
        placeholders.put("z", String.valueOf(loc.getBlockZ()));
        placeholders.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
        languageManager.sendMessage(player, "command.setaltar.success", placeholders);
        return true;
    }

    private boolean handleRemoveAltar(CommandSender sender, String[] args) {
        if (args.length < 1) {
            languageManager.sendMessage(sender, "command.removealtar.usage");
            return true;
        }
        String gemKey = args[0].toLowerCase();
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[0]);
            languageManager.sendMessage(sender, "command.removealtar.gem_not_found", placeholders);
            return true;
        }
        if (def.getAltarLocation() == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", gemKey);
            placeholders.put("gem_name", def.getDisplayName());
            languageManager.sendMessage(sender, "command.removealtar.no_altar", placeholders);
            return true;
        }
        gemManager.removeGemAltarLocation(gemKey);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        languageManager.sendMessage(sender, "command.removealtar.success", placeholders);
        return true;
    }
}
