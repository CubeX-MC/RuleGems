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
            case "rulers":
                if (require(sender, "rulergem.rulers")) return true;
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
                if (require(sender, "rulergem.admin")) return true;
                gemManager.gemStatus(sender);
                return true;
            case "tp":
                if (require(sender, "rulergem.admin")) return true;
                return handleTpCommand(sender, args);
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
        // Optional: add help for tp if language exists
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
    languageManager.sendMessage(sender, "command.help.rulers");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.footer");
    }

    private boolean handleTpCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("该指令仅限玩家使用。");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("用法: /rulergem tp <gemId或key>");
            return true;
        }
        Player p = (Player) sender;
        UUID gemId = gemManager.resolveGemIdentifier(args[1]);
        if (gemId == null) {
            sender.sendMessage("未找到对应的宝石。");
            return true;
        }
        // 优先传送到持有者，否则传送到放置位置
        Player realHolder = gemManager.getGemHolder(gemId);
        if (realHolder != null && realHolder.isOnline()) {
            Location dest = realHolder.getLocation();
            me.hushu.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        Location loc = gemManager.getGemLocation(gemId);
        if (loc != null) {
            Location dest = loc.clone().add(0.5, 1.0, 0.5);
            me.hushu.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        sender.sendMessage("该宝石既未被持有也未放置。");
        return true;
    }

    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        // /rulergem place <gemId> <x|~> <y|~> <z|~>
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
