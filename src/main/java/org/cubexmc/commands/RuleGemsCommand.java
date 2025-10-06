package org.cubexmc.commands;

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

import org.cubexmc.RuleGems;
import org.cubexmc.manager.ConfigManager;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

public class RuleGemsCommand implements CommandExecutor {
    private final RuleGems plugin;
    private final GemManager gemManager;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;

    public RuleGemsCommand(RuleGems plugin, GemManager gemManager, ConfigManager configManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.configManager = configManager;
        this.languageManager = languageManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!cmd.getName().equalsIgnoreCase("rulegems")) {
            return false;
        }

        if (args.length == 0) {
            languageManager.sendMessage(sender, "command.usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.saveGems();
                configManager.reloadConfigs();
                languageManager.loadLanguage();
                plugin.loadPlugin();
                languageManager.sendMessage(sender, "command.reload_success");
                return true;
            case "rulers":
                if (require(sender, "rulegems.rulers")) return true;
                java.util.Map<java.util.UUID, java.util.Set<String>> holders = gemManager.getCurrentPowerHolders();
                if (holders.isEmpty()) {
                    languageManager.sendMessage(sender, "command.no_rulers");
                    return true;
                }
                for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> e : holders.entrySet()) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    String name = p != null ? p.getName() : e.getKey().toString();
                    String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", name + " (" + extra + ")");
                    languageManager.sendMessage(sender, "command.rulers_status", placeholders);
                }
                return true;
            case "gems":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.gemStatus(sender);
                return true;
            case "tp":
                if (require(sender, "rulegems.admin")) return true;
                return handleTpCommand(sender, args);
            case "scatter":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.scatterGems();
                languageManager.sendMessage(sender, "command.scatter_success");
                return true;
            case "redeem":
                if (require(sender, "rulegems.redeem")) return true;
                if (!configManager.isRedeemEnabled()) {
                    languageManager.sendMessage(sender, "command.redeem.disabled");
                    return true;
                }
                return handleRedeem(sender, args);
            case "redeemall":
                if (require(sender, "rulegems.redeemall")) return true;
                if (!configManager.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(sender, "command.redeemall.disabled");
                    return true;
                }
                return handleRedeemAll(sender);
            case "place":
                if (require(sender, "rulegems.admin")) return true;
                return handlePlaceCommand(sender, args);
            case "revoke":
                if (require(sender, "rulegems.admin")) return true;
                return handleRevokeCommand(sender);
            case "history":
                if (require(sender, "rulegems.admin")) return true;
                return handleHistoryCommand(sender, args);
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
        // Optional: add help for tp if language exists
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
        languageManager.sendMessage(sender, "command.help.rulers");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.history");
        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.footer");
    }

    private boolean handleTpCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.tp.player_only");
            return true;
        }
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.tp.usage");
            return true;
        }
        Player p = (Player) sender;
        UUID gemId = gemManager.resolveGemIdentifier(args[1]);
        if (gemId == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("input", args[1]);
            languageManager.sendMessage(sender, "command.tp.not_found", placeholders);
            return true;
        }
        // 优先传送到持有者，否则传送到放置位置
        Player realHolder = gemManager.getGemHolder(gemId);
        if (realHolder != null && realHolder.isOnline()) {
            Location dest = realHolder.getLocation();
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        Location loc = gemManager.getGemLocation(gemId);
        if (loc != null) {
            Location dest = loc.clone().add(0.5, 1.0, 0.5);
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        languageManager.sendMessage(sender, "command.tp.unavailable");
        return true;
    }

    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        // /rulegems place <gemId> <x|~> <y|~> <z|~>
        if (args.length < 5) {
            languageManager.sendMessage(sender, "command.place.usage");
            return true;
        }
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.place.player_only");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        String gemIdentifier = args[1];

        // 处理 ~ 坐标（基于玩家当前位置）
        if (args[2].equals("~")) args[2] = String.valueOf(player.getLocation().getX());
        if (args[3].equals("~")) args[3] = String.valueOf(player.getLocation().getY());
        if (args[4].equals("~")) args[4] = String.valueOf(player.getLocation().getZ());

        double x, y, z;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            languageManager.sendMessage(sender, "command.place.invalid_coordinates");
            return true;
        }

        // 解析 gem 标识（UUID 或 gem key/name）
        UUID gemId = gemManager.resolveGemIdentifier(gemIdentifier);
        if (gemId == null) {
            languageManager.sendMessage(sender, "command.place.invalid_gem");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        // 强制移动该宝石（若被持有则移除背包，若已放置则先清除），确保全服唯一
        // 支撑性预检查：某些方块（如 sculk_catalyst）需要下面有方块
        org.bukkit.Material m = gemManager.getGemMaterial(gemId);
        if (gemManager.isSupportRequired(m) && !gemManager.hasBlockSupport(loc)) {
            languageManager.sendMessage(sender, "command.place.failed_unsupported");
            return true;
        }
        gemManager.forcePlaceGem(gemId, loc);

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
        Player player = (Player) sender;
        // 新行为：仅支持手持兑换
        org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == org.bukkit.Material.AIR) {
            languageManager.sendMessage(sender, "command.redeem.no_item_in_hand");
            return true;
        }
        if (!gemManager.isRulerGem(inHand)) {
            languageManager.sendMessage(sender, "command.redeem.not_a_gem");
            return true;
        }
        boolean ok = gemManager.redeemGemInHand(player);
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

    private boolean handleHistoryCommand(CommandSender sender, String[] args) {
        org.cubexmc.manager.HistoryLogger historyLogger = plugin.getHistoryLogger();
        if (historyLogger == null) {
            languageManager.sendMessage(sender, "command.history.disabled");
            return true;
        }

        int lines = 10; // 默认显示10条
        String playerFilter = null;

        // 解析参数: /rulegems history [行数] [玩家名]
        if (args.length > 1) {
            try {
                lines = Integer.parseInt(args[1]);
                if (lines < 1) lines = 10;
                if (lines > 50) lines = 50; // 最多50条
            } catch (NumberFormatException e) {
                // 第一个参数不是数字，可能是玩家名
                playerFilter = args[1];
            }
        }
        if (args.length > 2) {
            playerFilter = args[2];
        }

        java.util.List<String> history;
        if (playerFilter != null) {
            history = historyLogger.getPlayerHistory(playerFilter, lines);
            if (history.isEmpty()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", playerFilter);
                languageManager.sendMessage(sender, "command.history.no_player_records", placeholders);
                return true;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", playerFilter);
            placeholders.put("count", String.valueOf(history.size()));
            languageManager.sendMessage(sender, "command.history.player_header", placeholders);
        } else {
            history = historyLogger.getRecentHistory(lines);
            if (history.isEmpty()) {
                languageManager.sendMessage(sender, "command.history.no_records");
                return true;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(history.size()));
            languageManager.sendMessage(sender, "command.history.recent_header", placeholders);
        }

        for (String line : history) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("line", line);
            languageManager.sendMessage(sender, "command.history.line", placeholders);
        }
        languageManager.sendMessage(sender, "command.history.separator");

        return true;
    }
}
