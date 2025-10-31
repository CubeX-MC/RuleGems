package org.cubexmc.model;

import java.util.Collections;
import java.util.List;

/**
 * AllowedCommand 描述一条玩家可用的指令以及可用次数。
 * 扩展支持：自定义命令名、多命令链、执行者前缀（console:/player-op:）、冷却时间
 */
public class AllowedCommand {
    private final String label;              // 玩家输入的命令标签（不带斜杠的小写），如 "tfly"
    private final int uses;                  // 授予的可用次数
    private final List<String> executeCommands; // 实际执行的命令列表（可带前缀）
    private final int cooldown;              // 冷却时间（秒）

    // 完整构造函数（新功能：command和execute分离）
    public AllowedCommand(String label, int uses, List<String> executeCommands, int cooldown) {
        this.label = label;
        this.uses = Math.max(-1, uses);
        this.executeCommands = executeCommands != null && !executeCommands.isEmpty() ? 
            executeCommands : Collections.singletonList(label);
        this.cooldown = Math.max(0, cooldown);
    }

    public String getLabel() {
        return label;
    }

    public int getUses() {
        return uses;
    }

    public List<String> getCommands() {
        return executeCommands;
    }

    public int getCooldown() {
        return cooldown;
    }

    /**
     * 是否为简单命令（单条，无前缀，command和execute相同）
     */
    public boolean isSimpleCommand() {
        return executeCommands.size() == 1 && 
               !executeCommands.get(0).startsWith("console:") && 
               !executeCommands.get(0).startsWith("player-op:") &&
               executeCommands.get(0).equalsIgnoreCase(label);
    }

    /**
     * 解析命令行，返回执行者类型和实际命令。
     * 
     * @param commandLine 命令行（可能带前缀 console: 或 player-op:）
     * @return [executor, actualCommand] 数组，executor = "console" 或 "player-op"
     */
    public static String[] parseExecutor(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return new String[]{"player-op", ""};
        }
        
        String trimmed = commandLine.trim();
        
        // 检查是否有前缀
        if (trimmed.startsWith("console:")) {
            return new String[]{"console", trimmed.substring(8).trim()};
        } else if (trimmed.startsWith("player-op:")) {
            return new String[]{"player-op", trimmed.substring(10).trim()};
        } else {
            // 默认为 player-op
            return new String[]{"player-op", trimmed};
        }
    }
}


