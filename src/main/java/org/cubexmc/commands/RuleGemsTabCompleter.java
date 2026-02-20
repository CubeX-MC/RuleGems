package org.cubexmc.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.cubexmc.manager.GemDefinitionParser;
import org.cubexmc.manager.GemManager;
import org.cubexmc.model.GemDefinition;

public class RuleGemsTabCompleter implements TabCompleter {

    private final GemDefinitionParser gemParser;
    private final GemManager gemManager;

    public RuleGemsTabCompleter(GemDefinitionParser gemParser, GemManager gemManager) {
        this.gemParser = gemParser;
        this.gemManager = gemManager;
    }

    // 子命令列表
    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "gui",
            "place",
            "tp",
            "revoke",
            "reload",
            "rulers",
            "gems",
            "scatter",
            "redeem",
            "redeemall",
            "history",
            "setaltar",
            "removealtar",
            "appoint",
            "dismiss",
            "appointees",
            "help"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (!command.getName().equalsIgnoreCase("rulegems")) {
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

    // /rulegems place <gemKey 或 UUID>
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return getGemKeyAndUuidSuggestions(args[1]);
        }

    // /rulegems tp <gemKey 或 UUID>
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            return getGemKeyAndUuidSuggestions(args[1]);
        }

    // /rulegems revoke <player>
        if (args.length == 2 && args[0].equalsIgnoreCase("revoke")) {
            return getOnlinePlayerNames(args[1]);
        }

    // /rulegems redeem 不再需要 key，阻止玩家名或其它补全
        if (args.length >= 2 && (args[0].equalsIgnoreCase("redeem") || args[0].equalsIgnoreCase("redeemall"))) {
            return java.util.Collections.emptyList();
        }

    // /rulegems setaltar <gemKey>
        if (args.length == 2 && args[0].equalsIgnoreCase("setaltar")) {
            return getGemKeySuggestions(args[1]);
        }

    // /rulegems removealtar <gemKey>
        if (args.length == 2 && args[0].equalsIgnoreCase("removealtar")) {
            return getGemKeySuggestions(args[1]);
        }

    // /rulegems appoint <perm_set> <player>
        if (args.length == 2 && args[0].equalsIgnoreCase("appoint")) {
            return getPermSetSuggestions(sender, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("appoint")) {
            return getOnlinePlayerNames(args[2]);
        }

    // /rulegems dismiss <perm_set> <player>
        if (args.length == 2 && args[0].equalsIgnoreCase("dismiss")) {
            return getPermSetSuggestions(sender, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("dismiss")) {
            return getOnlinePlayerNames(args[2]);
        }

    // /rulegems appointees [perm_set]
        if (args.length == 2 && args[0].equalsIgnoreCase("appointees")) {
            return getAllPermSetSuggestions(args[1]);
        }

    // /rulegems place <gemKey> <x|~>
        if (args.length == 3 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[2]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

    // /rulegems place <gemKey> <x> <y|~>
        if (args.length == 4 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[3]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

    // /rulegems place <gemKey> <x> <y> <z|~>
        if (args.length == 5 && args[0].equalsIgnoreCase("place")) {
            return startsWith("~", args[4]) ? java.util.Collections.singletonList("~") : java.util.Collections.emptyList();
        }

        // redeem 不再需要参数：不提供二级补全

        return java.util.Collections.emptyList();
    }

    private boolean startsWith(String option, String typed) {
        return option.toLowerCase().startsWith(typed == null ? "" : typed.toLowerCase());
    }

    private List<String> getGemKeySuggestions(String typed) {
        if (gemParser == null || gemParser.getGemDefinitions() == null) {
            return java.util.Collections.emptyList();
        }
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (GemDefinition definition : gemParser.getGemDefinitions()) {
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

    /**
     * 获取 gem key 和 UUID 的补全建议
     * 优先显示 gem key，然后显示所有宝石的 UUID（简短形式）
     */
    private List<String> getGemKeyAndUuidSuggestions(String typed) {
        List<String> suggestions = new ArrayList<>();
        String prefix = typed == null ? "" : typed.toLowerCase();
        
        // 先添加 gem key 补全
        suggestions.addAll(getGemKeySuggestions(typed));
        
        // 再添加所有宝石的 UUID（简短形式前8位，输入更长时显示完整）
        if (gemManager != null) {
            for (java.util.UUID uuid : gemManager.getAllGemUuids()) {
                String fullUuid = uuid.toString();
                String shortUuid = fullUuid.substring(0, 8);
                
                if (prefix.isEmpty()) {
                    // 无输入时只显示短 UUID
                    suggestions.add(shortUuid);
                } else if (shortUuid.toLowerCase().startsWith(prefix)) {
                    // 输入匹配短 UUID 时，显示完整 UUID
                    suggestions.add(fullUuid);
                } else if (fullUuid.toLowerCase().startsWith(prefix)) {
                    // 输入匹配完整 UUID 时，显示完整 UUID
                    suggestions.add(fullUuid);
                }
            }
        }
        
        return suggestions;
    }

    private List<String> getOnlinePlayerNames(String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            if (prefix.isEmpty() || name.toLowerCase().startsWith(prefix)) {
                suggestions.add(name);
            }
        }
        return suggestions;
    }

    /**
     * 获取玩家有权限任命的权限集
     */
    private List<String> getPermSetSuggestions(CommandSender sender, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        
        // 需要获取 AppointFeature 来获取权限集列表
        // 这里通过遍历配置来获取
        org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RuleGems");
        if (plugin instanceof org.cubexmc.RuleGems) {
            org.cubexmc.RuleGems ruleGems = (org.cubexmc.RuleGems) plugin;
            org.cubexmc.features.appoint.AppointFeature appointFeature = ruleGems.getFeatureManager().getAppointFeature();
            if (appointFeature != null) {
                for (String key : appointFeature.getAppointDefinitions().keySet()) {
                    // 只显示玩家有权限的权限集
                    if (sender.hasPermission("rulegems.appoint." + key) || sender.hasPermission("rulegems.admin")) {
                        if (prefix.isEmpty() || key.toLowerCase().startsWith(prefix)) {
                            suggestions.add(key);
                        }
                    }
                }
            }
        }
        
        return suggestions;
    }

    /**
     * 获取所有权限集（用于 appointees 命令）
     */
    private List<String> getAllPermSetSuggestions(String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        
        org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RuleGems");
        if (plugin instanceof org.cubexmc.RuleGems) {
            org.cubexmc.RuleGems ruleGems = (org.cubexmc.RuleGems) plugin;
            org.cubexmc.features.appoint.AppointFeature appointFeature = ruleGems.getFeatureManager().getAppointFeature();
            if (appointFeature != null) {
                for (String key : appointFeature.getAppointDefinitions().keySet()) {
                    if (prefix.isEmpty() || key.toLowerCase().startsWith(prefix)) {
                        suggestions.add(key);
                    }
                }
            }
        }
        
        return suggestions;
    }
}
