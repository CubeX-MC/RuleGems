package me.hushu.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.hushu.RulerGem;
import me.hushu.manager.ConfigManager;
import me.hushu.manager.GemManager;
import me.hushu.manager.LanguageManager;

public class RulerGemCommand implements CommandExecutor {
    private final RulerGem plugin;
    private final GemManager gemManager;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;

    public RulerGemCommand(RulerGem plugin, GemManager gemManager, ConfigManager configManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.configManager = configManager;
        this.languageManager = languageManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!cmd.getName().equalsIgnoreCase("rulergem")) {
            return false;
        }

        if (args.length == 0) {
            languageManager.sendMessage(sender, "command.usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (require(sender, "rulergem.admin")) return true;
                gemManager.saveGems();
                configManager.reloadConfigs();
                languageManager.loadLanguage();
                plugin.loadPlugin();
                languageManager.sendMessage(sender, "command.reload_success");
                return true;
            case "powerplayer":
                if (require(sender, "rulergem.powerplayer")) return true;
                java.util.Map<java.util.UUID, java.util.Set<String>> holders = gemManager.getCurrentPowerHolders();
                if (holders.isEmpty()) {
                    languageManager.sendMessage(sender, "command.no_power_player");
                    return true;
                }
                for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> e : holders.entrySet()) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    String name = p != null ? p.getName() : e.getKey().toString();
                    String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", name + " (" + extra + ")");
                    languageManager.sendMessage(sender, "command.powerplayer_status", placeholders);
                }
                return true;
            case "gems":
                if (require(sender, "rulergem.admin")) return true;
                gemManager.gemStatus(sender);
                return true;
            case "scatter":
                if (require(sender, "rulergem.admin")) return true;
                gemManager.scatterGems();
                languageManager.sendMessage(sender, "command.scatter_success");
                return true;
            case "redeem":
                if (require(sender, "rulergem.redeem")) return true;
                if (!configManager.isRedeemEnabled()) {
                    languageManager.sendMessage(sender, "command.redeem.disabled");
                    return true;
                }
                return handleRedeem(sender, args);
            case "redeemall":
                if (require(sender, "rulergem.redeemall")) return true;
                if (!configManager.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(sender, "command.redeemall.disabled");
                    return true;
                }
                return handleRedeemAll(sender);
            case "place":
                if (require(sender, "rulergem.admin")) return true;
                return handlePlaceCommand(sender, args);
            case "revoke":
                if (require(sender, "rulergem.admin")) return true;
                return handleRevokeCommand(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                languageManager.sendMessage(sender, "command.unknown_subcommand");
                return true;
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            languageManager.sendMessage(sender, "command.no_permission");
            return true;
        }
        return false;
    }

    private void sendHelp(CommandSender sender) {
        languageManager.sendMessage(sender, "command.help.header");
        languageManager.sendMessage(sender, "command.help.place");
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
        languageManager.sendMessage(sender, "command.help.powerplayer");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.footer");
    }

    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            languageManager.sendMessage(sender, "command.place.usage");
            return true;
        }
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.place.player_only");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();
        // 如果玩家输入了 ~，则使用玩家当前坐标
        if (args[1].equals("~"))
            args[1] = String.valueOf(player.getLocation().getX());
        if (args[2].equals("~"))
            args[2] = String.valueOf(player.getLocation().getY());
        if (args[3].equals("~"))
            args[3] = String.valueOf(player.getLocation().getZ());
        double x, y, z;
        try {
            x = Double.parseDouble(args[1]);
            y = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            languageManager.sendMessage(sender, "command.place.invalid_coordinates");
            return true;
        }

        if (gemManager.getTotalGemCount() >= configManager.getRequiredCount()) {
            languageManager.sendMessage(sender, "command.place.gem_limit_reached");
            return false;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        // 放置宝石方块
        UUID newGemId = UUID.randomUUID();
        gemManager.placePowerGem(loc, newGemId);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("x", String.valueOf(x));
        placeholders.put("y", String.valueOf(y));
        placeholders.put("z", String.valueOf(z));
        languageManager.sendMessage(sender, "command.place.success", placeholders);
        return true;
    }

    private boolean handleRedeem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.redeem.player_only");
            return true;
        }
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.redeem.usage");
            return true;
        }
        Player player = (Player) sender;
        String name = args[1];
        boolean ok = gemManager.redeemGem(player, name);
        if (!ok) {
            languageManager.sendMessage(sender, "command.redeem.failed");
            return true;
        }
        languageManager.sendMessage(sender, "command.redeem.success");
        return true;
    }

    private boolean handleRedeemAll(CommandSender sender) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.redeem.player_only");
            return true;
        }
        Player player = (Player) sender;
        boolean ok = gemManager.redeemAll(player);
        if (!ok) {
            languageManager.sendMessage(sender, "command.redeemall.failed");
            return true;
        }
        languageManager.sendMessage(sender, "command.redeemall.success");
        return true;
    }

    private boolean handleRevokeCommand(CommandSender sender) {
        if (gemManager.getPowerPlayer() == null) {
            languageManager.sendMessage(sender, "command.revoke.no_power_player");
            return true;
        }
        gemManager.revokePermission(sender);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", gemManager.getPowerPlayer().getName());
        languageManager.sendMessage(sender, "command.revoke.success", placeholders);
        
        // 显示权限收回的标题
        for (Player player : Bukkit.getOnlinePlayers()) {
            languageManager.showTitle(player, "power_revoked", placeholders);
        }
        return true;
    }
}
