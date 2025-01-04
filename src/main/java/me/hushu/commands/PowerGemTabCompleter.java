package me.hushu.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class PowerGemTabCompleter implements TabCompleter {

    // 准备要补全的子命令列表
    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "place",
            "revoke",
            "reload",
            "powerplayer",
            "gems",
            "scatter",
            "help"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        // 首先判断指令名称是否匹配
        if (!command.getName().equalsIgnoreCase("powergem")) {
            return null; // 返回 null 表示不处理
        }

        // 如果玩家只输入了 /powergem 或正在输入 /powergem <第一个参数> 时
        if (args.length == 1) {
            // 动态过滤出符合当前输入的子命令
            List<String> suggestions = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    suggestions.add(sub);
                }
            }
            return suggestions;
        }

        // 如果你还有更多层级，可以根据 args[0] 和 args.length 做后续判断，返回相应的建议
        // 例如对 `/powergem place <参数>` 做自动补全
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            // 如果需要，你可以对 place 子命令的第二个参数进行自动补全。这里仅做示例：
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

        // 未匹配到任何情况就返回空列表，不再提供补全
        return new ArrayList<>();
    }
}
