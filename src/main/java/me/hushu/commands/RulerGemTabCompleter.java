package me.hushu.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import me.hushu.manager.ConfigManager;
import me.hushu.model.GemDefinition;

public class RulerGemTabCompleter implements TabCompleter {

    private final ConfigManager configManager;

    public RulerGemTabCompleter(ConfigManager configManager) {
        this.configManager = configManager;
    }

    // 子命令列表
    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "place",
            "tp",
            "revoke",
            "reload",
            "rulers",
            "gems",
            "scatter",
            "redeem",
            "redeemall",
            "help"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (!command.getName().equalsIgnoreCase("rulergem")) {
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

        // /rulergem place <gemKey>
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return getGemKeySuggestions(args[1]);
        }

        // /rulergem tp <gemKey>
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            return getGemKeySuggestions(args[1]);
        }

        // /rulergem place <gemKey> <x|~>
        if (args.length == 3 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[2]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

        // /rulergem place <gemKey> <x> <y|~>
        if (args.length == 4 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[3]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

        // /rulergem place <gemKey> <x> <y> <z|~>
        if (args.length == 5 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[4]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

        // /rulergem redeem <key>
        if (args.length == 2 && args[0].equalsIgnoreCase("redeem")) {
            return getGemKeySuggestions(args[1]);
        }

        return new ArrayList<>();
    }

    private boolean startsWith(String option, String typed) {
        return option.toLowerCase().startsWith(typed == null ? "" : typed.toLowerCase());
    }

    private List<String> getGemKeySuggestions(String typed) {
        if (configManager == null || configManager.getGemDefinitions() == null) {
            return java.util.Collections.emptyList();
        }
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (GemDefinition definition : configManager.getGemDefinitions()) {
            if (definition == null || definition.getGemKey() == null) {
                continue;
            }
            String key = definition.getGemKey();
            if (prefix.isEmpty() || key.toLowerCase().startsWith(prefix)) {
                suggestions.add(key);
            }
        }
        return suggestions;
    }
}
