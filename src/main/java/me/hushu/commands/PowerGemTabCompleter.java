package me.hushu.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import me.hushu.manager.ConfigManager;
import me.hushu.model.GemDefinition;

public class PowerGemTabCompleter implements TabCompleter {

    private final ConfigManager configManager;

    public PowerGemTabCompleter(ConfigManager configManager) {
        this.configManager = configManager;
    }

    // 子命令列表
    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "place",
            "revoke",
            "reload",
            "powerplayer",
            "gems",
            "scatter",
            "redeem",
            "redeemall",
            "help"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (!command.getName().equalsIgnoreCase("powergem")) {
            return null; // 不处理其它指令
        }

        // 一级子命令
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    suggestions.add(sub);
                }
            }
            return suggestions;
        }

        // /powergem place <x>
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            List<String> placeArgs = new ArrayList<>();
            placeArgs.add("~");
            placeArgs.add("~");
            placeArgs.add("~");
            List<String> suggestions = new ArrayList<>();
            for (String s : placeArgs) {
                if (s.toLowerCase().startsWith(args[1].toLowerCase())) {
                    suggestions.add(s);
                }
            }
            return suggestions;
        }

        // /powergem redeem <key>
        if (args.length == 2 && args[0].equalsIgnoreCase("redeem")) {
            List<String> keys = new ArrayList<>();
            if (configManager != null && configManager.getGemDefinitions() != null) {
                for (GemDefinition d : configManager.getGemDefinitions()) {
                    keys.add(d.getGemKey());
                }
            }
            List<String> suggestions = new ArrayList<>();
            for (String k : keys) {
                if (k.toLowerCase().startsWith(args[1].toLowerCase())) {
                    suggestions.add(k);
                }
            }
            return suggestions;
        }

        return new ArrayList<>();
    }
}
