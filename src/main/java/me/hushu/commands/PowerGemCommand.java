package me.hushu.commands;

import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.hushu.PowerGem;
import me.hushu.manager.ConfigManager;
import me.hushu.manager.GemManager;

public class PowerGemCommand implements CommandExecutor {
    private final PowerGem plugin;
    private final GemManager gemManager;
    private final ConfigManager configManager;

    public PowerGemCommand(PowerGem plugin, GemManager gemManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.configManager = configManager;
    }

    /**
     * 自定义命令：/powergem ...
     * 可能的子命令：
     *  - place <x> <y> <z> : 在指定坐标放置宝石方块
     *  - revoke <playerName> : 收回该玩家的权限
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("powergem")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /powergem <place|revoke|reload|powerplayer|gems|scatter|help>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                gemManager.saveGems();
                configManager.reloadConfigs();
                plugin.loadPlugin();
                sender.sendMessage(ChatColor.GREEN + "[PowerGem] 配置已重新加载！");
                return true;
            case "powerplayer":
                sender.sendMessage(ChatColor.GREEN + "当前的 powerPlayer 是: " + gemManager.getPowerPlayer().getName());
                return true;
            case "gems":
                gemManager.gemStatus(sender);
                return true;
            case "scatter":
                gemManager.scatterGems();
                sender.sendMessage(ChatColor.GREEN + "[PowerGem] 权力被收回，宝石已散落！");
                return true;
            case "place":
                return handlePlaceCommand(sender, args);
            case "revoke":
                return handleRevokeCommand(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.YELLOW + "未知的子命令。用法: /powergem <place|revoke|reload|powerplayer|gems|scatter|help>");
                return true;
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "=== PowerGem 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/powergem place <x> <y> <z> " + ChatColor.WHITE + "- 在指定坐标放置宝石方块");
        sender.sendMessage(ChatColor.YELLOW + "/powergem revoke <playerName> " + ChatColor.WHITE + "- 收回该玩家的权限");
        sender.sendMessage(ChatColor.YELLOW + "/powergem reload " + ChatColor.WHITE + "- 重新加载配置");
        sender.sendMessage(ChatColor.YELLOW + "/powergem powerplayer " + ChatColor.WHITE + "- 查看当前的 powerPlayer");
        sender.sendMessage(ChatColor.YELLOW + "/powergem gems " + ChatColor.WHITE + "- 查看所有宝石信息");
        sender.sendMessage(ChatColor.YELLOW + "/powergem scatter " + ChatColor.WHITE + "- 散落宝石");
        sender.sendMessage(ChatColor.YELLOW + "/powergem help " + ChatColor.WHITE + "- 显示帮助信息");
        sender.sendMessage(ChatColor.GREEN + "======================");
    }

    /**
     * 处理 /powergem place 子命令
     */
    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "用法: /powergem place <x> <y> <z>");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只能玩家执行此命令！");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();
        double x, y, z;
        try {
            x = Double.parseDouble(args[1]);
            y = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "坐标必须是数字！");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        // 放置宝石方块
        UUID newGemId = UUID.randomUUID();
        gemManager.placePowerGem(loc, newGemId);

        sender.sendMessage(ChatColor.GREEN + "已在 " + x + " " + y + " " + z + " 放置一颗宝石。");
        return true;
    }

    /**
     * 处理 /powergem revoke 子命令
     */
    private boolean handleRevokeCommand(CommandSender sender) {
        gemManager.revokePermission();
        sender.sendMessage(ChatColor.GREEN + "已收回 " + gemManager.getPowerPlayer().getName() + " 的权限。");
        return true;
    }
}
