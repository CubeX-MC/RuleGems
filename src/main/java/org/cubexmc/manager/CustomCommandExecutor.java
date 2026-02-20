package org.cubexmc.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.cubexmc.utils.SchedulerUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义命令执行器
 * 处理命令解析、参数替换、执行者切换等逻辑
 */
public class CustomCommandExecutor {
    private final org.cubexmc.RuleGems plugin;
    private final LanguageManager languageManager;
    private final GameplayConfig gameplayConfig;

    // 冷却时间管理: 玩家UUID -> (命令名 -> 过期时间戳)
    private final Map<UUID, Map<String, Long>> playerCooldowns = new ConcurrentHashMap<>();

    public CustomCommandExecutor(org.cubexmc.RuleGems plugin, LanguageManager languageManager, GameplayConfig gameplayConfig) {
        this.plugin = plugin;
        this.languageManager = languageManager;
        this.gameplayConfig = gameplayConfig;
    }

    /**
     * 以控制台身份调度命令（Folia 安全，fire-and-forget）。
     * 供外部调用（如 CommandAllowanceListener）在全局线程上执行命令。
     *
     * @param command 不含前导 / 的命令字符串
     * @return 调度是否成功（不代表命令本身成功）
     */
    public boolean dispatchAsConsole(String command) {
        return executeAsConsole(command, null);
    }

    /**
     * 以后台身份执行命令
     * 在 Folia 中，后台命令必须在全局线程执行
     */
    private boolean executeAsConsole(String command, Player player) {
        try {
            // 后台命令必须在全局线程执行
            SchedulerUtil.globalRun(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }, 0L, -1L);
            plugin.getLogger().fine("[Debug] Console command submitted: " + command);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to execute console command: " + command);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Console command execution failed", e);
            return false;
        }
    }

    /**
     * 以玩家身份执行命令（安全方式）
     * 如果配置允许 OP 提权则临时授予 OP，否则回退为控制台执行。
     */
    private boolean executeAsPlayerOp(String command, Player player) {
        boolean useOp = gameplayConfig != null && gameplayConfig.isOpEscalationAllowed();
        
        if (!useOp) {
            // 安全回退：以控制台身份执行，保留 %player% 替换
            plugin.getLogger().fine("[Safe mode] player-op command falling back to console execution: " + command);
            return executeAsConsole(command, player);
        }

        // OP 提权模式（管理员显式启用）
        boolean wasOp = player.isOp();
        try {
            if (!wasOp) {
                player.setOp(true);
            }
            boolean result = player.performCommand(command);
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to execute player command: " + command);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Player command execution failed", e);
            return false;
        } finally {
            if (!wasOp && player.isOp()) {
                player.setOp(false);
            }
        }
    }

    /**
     * 替换占位符，支持默认值语法：%arg1|defaultValue%
     */
    private String replacePlaceholders(String text, Map<String, String> placeholders, String[] args) {
        String result = text;
        
        // 先处理简单占位符
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        
        // 处理带默认值的占位符: %arg1|default%
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("%arg(\\d+)\\|([^%]+)%");
        java.util.regex.Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            int argIndex = Integer.parseInt(matcher.group(1)) - 1;  // arg1 = index 0
            String defaultValue = matcher.group(2);
            String replacement = (argIndex >= 0 && argIndex < args.length) ? args[argIndex] : defaultValue;
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * 执行扩展命令（多命令链或带执行者前缀）
     */
    public boolean executeExtendedCommand(Player player, org.cubexmc.model.AllowedCommand allowedCmd, String[] args) {
        if (player == null || allowedCmd == null) {
            return false;
        }
        
        // 准备占位符映射
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%player%", player.getName());
        for (int i = 0; i < args.length; i++) {
            placeholders.put("%arg" + (i + 1) + "%", args[i]);
        }
        
        // 调试日志
        plugin.getLogger().fine("[Debug] Executing extended command, player: " + player.getName());
        plugin.getLogger().fine("[Debug] Placeholders: " + placeholders);
        plugin.getLogger().fine("[Debug] Args: " + java.util.Arrays.toString(args));
        plugin.getLogger().fine("[Debug] Command list: " + allowedCmd.getCommands());
        
        // 执行所有命令
        boolean allSuccess = true;
        for (String commandLine : allowedCmd.getCommands()) {
            if (commandLine == null || commandLine.trim().isEmpty()) continue;
            
            plugin.getLogger().fine("[Debug] Raw command line: " + commandLine);
            
            // 解析执行者和命令
            String[] parsed = org.cubexmc.model.AllowedCommand.parseExecutor(commandLine);
            String executor = parsed[0];  // "console" 或 "player-op"
            String actualCommand = parsed[1];
            
            plugin.getLogger().fine("[Debug] Executor: " + executor + ", actual command: " + actualCommand);
            
            // 替换占位符（包括默认值支持）
            String finalCommand = replacePlaceholders(actualCommand, placeholders, args);
            
            plugin.getLogger().fine("[Debug] Command after substitution: " + finalCommand);
            
            // 移除开头的斜杠（如果有）
            if (finalCommand.startsWith("/")) {
                finalCommand = finalCommand.substring(1);
            }
            
            plugin.getLogger().fine("[Debug] Final command: " + finalCommand);
            
            // 根据执行者类型执行命令
            boolean success = false;
            if ("console".equals(executor)) {
                success = executeAsConsole(finalCommand, player);
            } else {
                // "player-op" 或默认
                success = executeAsPlayerOp(finalCommand, player);
            }
            
            if (!success) {
                allSuccess = false;
                if (languageManager != null) {
                    Map<String, String> messagePlaceholders = new HashMap<>();
                    messagePlaceholders.put("command", finalCommand);
                    languageManager.sendMessage(player, "allowance.command_failed_detail", messagePlaceholders);
                } else {
                    player.sendMessage("§cCommand execution failed: " + finalCommand);
                }
            }
        }
        
        return allSuccess;
    }

    /**
     * 检查玩家是否可以执行命令（冷却时间）
     */
    public boolean checkCooldown(UUID playerUuid, String commandName) {
        Map<String, Long> cooldowns = playerCooldowns.get(playerUuid);
        if (cooldowns == null) {
            return true;
        }
        
        Long expireTime = cooldowns.get(commandName);
        if (expireTime == null) {
            return true;
        }
        
        return System.currentTimeMillis() >= expireTime;
    }

    /**
     * 获取剩余冷却时间（秒）
     */
    public long getRemainingCooldown(UUID playerUuid, String commandName) {
        Map<String, Long> cooldowns = playerCooldowns.get(playerUuid);
        if (cooldowns == null) {
            return 0;
        }
        
        Long expireTime = cooldowns.get(commandName);
        if (expireTime == null) {
            return 0;
        }
        
        long remaining = (expireTime - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    /**
     * 设置冷却时间
     */
    public void setCooldown(UUID playerUuid, String commandName, int seconds) {
        Map<String, Long> cooldowns = playerCooldowns.computeIfAbsent(playerUuid, k -> new HashMap<>());
        long expireTime = System.currentTimeMillis() + (seconds * 1000L);
        cooldowns.put(commandName, expireTime);
    }

}

