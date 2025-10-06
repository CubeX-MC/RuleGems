package org.cubexmc.manager;

import org.cubexmc.RuleGems;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 历史记录管理器 - 记录宝石权限更迭的"史官"
 * 负责将权力变更记录保存到日志文件
 */
public class HistoryLogger {
    
    private final RuleGems plugin;
    private final File logsDirectory;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat fileNameFormat;
    
    public HistoryLogger(RuleGems plugin) {
        this.plugin = plugin;
        this.logsDirectory = new File(plugin.getDataFolder(), "history");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.fileNameFormat = new SimpleDateFormat("yyyy-MM");
        
        // 确保日志目录存在
        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs();
        }
    }
    
    /**
     * 记录单个宝石兑换事件
     * 
     * @param player 兑换玩家
     * @param gemKey 宝石标识
     * @param gemDisplayName 宝石显示名称
     * @param permissions 授予的权限列表
     * @param vaultGroup 授予的权限组
     * @param previousOwner 之前的拥有者（如果有）
     */
    public void logGemRedeem(Player player, String gemKey, String gemDisplayName, 
                            List<String> permissions, String vaultGroup, String previousOwner) {
        StringBuilder logEntry = new StringBuilder();
        String timestamp = dateFormat.format(new Date());
        
        logEntry.append("[").append(timestamp).append("] ");
        logEntry.append("§e【宝石兑换】 ");
        logEntry.append("玩家: ").append(player.getName()).append(" (").append(player.getUniqueId()).append(") ");
        logEntry.append("| 宝石: ").append(gemDisplayName != null ? gemDisplayName : gemKey).append(" (").append(gemKey).append(")");
        
        if (previousOwner != null && !previousOwner.isEmpty()) {
            logEntry.append(" | 前任: ").append(previousOwner);
        }
        
        if (permissions != null && !permissions.isEmpty()) {
            logEntry.append(" | 权限: [").append(String.join(", ", permissions)).append("]");
        }
        
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            logEntry.append(" | 权限组: ").append(vaultGroup);
        }
        
        writeLog(logEntry.toString());
    }
    
    /**
     * 记录权限撤销事件
     * 
     * @param playerUuid 被撤销玩家的UUID
     * @param playerName 被撤销玩家的名称
     * @param gemKey 宝石标识
     * @param gemDisplayName 宝石显示名称
     * @param permissions 撤销的权限列表
     * @param vaultGroup 撤销的权限组
     * @param reason 撤销原因
     */
    public void logPermissionRevoke(String playerUuid, String playerName, String gemKey, 
                                   String gemDisplayName, List<String> permissions, 
                                   String vaultGroup, String reason) {
        StringBuilder logEntry = new StringBuilder();
        String timestamp = dateFormat.format(new Date());
        
        logEntry.append("[").append(timestamp).append("] ");
        logEntry.append("§c【权限撤销】 ");
        logEntry.append("玩家: ").append(playerName != null ? playerName : "未知").append(" (").append(playerUuid).append(") ");
        logEntry.append("| 宝石: ").append(gemDisplayName != null ? gemDisplayName : gemKey).append(" (").append(gemKey).append(")");
        
        if (reason != null && !reason.isEmpty()) {
            logEntry.append(" | 原因: ").append(reason);
        }
        
        if (permissions != null && !permissions.isEmpty()) {
            logEntry.append(" | 撤销权限: [").append(String.join(", ", permissions)).append("]");
        }
        
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            logEntry.append(" | 撤销权限组: ").append(vaultGroup);
        }
        
        writeLog(logEntry.toString());
    }
    
    /**
     * 记录全套宝石兑换事件
     * 
     * @param player 兑换玩家
     * @param gemCount 宝石数量
     * @param permissions 授予的所有权限
     * @param previousFullSetOwner 之前的全套拥有者（如果有）
     */
    public void logFullSetRedeem(Player player, int gemCount, List<String> permissions, String previousFullSetOwner) {
        StringBuilder logEntry = new StringBuilder();
        String timestamp = dateFormat.format(new Date());
        
        logEntry.append("[").append(timestamp).append("] ");
        logEntry.append("§6【集齐全套】 ");
        logEntry.append("玩家: ").append(player.getName()).append(" (").append(player.getUniqueId()).append(") ");
        logEntry.append("| 宝石数量: ").append(gemCount);
        
        if (previousFullSetOwner != null && !previousFullSetOwner.isEmpty()) {
            logEntry.append(" | 前任统治者: ").append(previousFullSetOwner);
        }
        
        if (permissions != null && !permissions.isEmpty()) {
            logEntry.append(" | 总权限: [").append(String.join(", ", permissions)).append("]");
        }
        
        writeLog(logEntry.toString());
    }
    
    /**
     * 记录宝石放置事件
     * 
     * @param player 放置玩家
     * @param gemKey 宝石标识
     * @param location 放置位置
     */
    public void logGemPlace(Player player, String gemKey, String location) {
        StringBuilder logEntry = new StringBuilder();
        String timestamp = dateFormat.format(new Date());
        
        logEntry.append("[").append(timestamp).append("] ");
        logEntry.append("§a【宝石放置】 ");
        logEntry.append("玩家: ").append(player.getName()).append(" ");
        logEntry.append("| 宝石: ").append(gemKey).append(" ");
        logEntry.append("| 位置: ").append(location);
        
        writeLog(logEntry.toString());
    }
    
    /**
     * 记录宝石破坏事件
     * 
     * @param player 破坏玩家
     * @param gemKey 宝石标识
     * @param location 破坏位置
     */
    public void logGemBreak(Player player, String gemKey, String location) {
        StringBuilder logEntry = new StringBuilder();
        String timestamp = dateFormat.format(new Date());
        
        logEntry.append("[").append(timestamp).append("] ");
        logEntry.append("§c【宝石破坏】 ");
        logEntry.append("玩家: ").append(player.getName()).append(" ");
        logEntry.append("| 宝石: ").append(gemKey).append(" ");
        logEntry.append("| 位置: ").append(location);
        
        writeLog(logEntry.toString());
    }
    
    /**
     * 将日志写入文件
     * 文件按月份分类，例如：2025-01.log
     */
    private void writeLog(String logEntry) {
        String fileName = fileNameFormat.format(new Date()) + ".log";
        File logFile = new File(logsDirectory, fileName);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            // 移除颜色代码（用于文件存储）
            String cleanEntry = logEntry.replaceAll("§[0-9a-fk-or]", "");
            writer.write(cleanEntry);
            writer.newLine();
        } catch (IOException e) {
            plugin.getLogger().warning("无法写入历史日志: " + e.getMessage());
        }
    }
    
    /**
     * 获取最近的N条历史记录
     * 
     * @param lines 要读取的行数
     * @return 历史记录列表
     */
    public List<String> getRecentHistory(int lines) {
        List<String> history = new ArrayList<>();
        
        try {
            // 获取所有日志文件，按名称排序（即按时间排序）
            File[] logFiles = logsDirectory.listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) {
                return history;
            }
            
            // 按文件名倒序排列（最新的在前）
            java.util.Arrays.sort(logFiles, (a, b) -> b.getName().compareTo(a.getName()));
            
            // 从最新的文件开始读取
            for (File logFile : logFiles) {
                try (Stream<String> stream = Files.lines(logFile.toPath())) {
                    List<String> fileLines = stream.collect(Collectors.toList());
                    // 倒序读取（最新的在前）
                    for (int i = fileLines.size() - 1; i >= 0 && history.size() < lines; i--) {
                        history.add(fileLines.get(i));
                    }
                    
                    if (history.size() >= lines) {
                        break;
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("读取日志文件失败: " + logFile.getName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取历史记录失败: " + e.getMessage());
        }
        
        return history;
    }
    
    /**
     * 获取指定玩家的历史记录
     * 
     * @param playerName 玩家名称
     * @param lines 最多返回的行数
     * @return 该玩家的历史记录
     */
    public List<String> getPlayerHistory(String playerName, int lines) {
        List<String> history = new ArrayList<>();
        
        try {
            File[] logFiles = logsDirectory.listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) {
                return history;
            }
            
            java.util.Arrays.sort(logFiles, (a, b) -> b.getName().compareTo(a.getName()));
            
            for (File logFile : logFiles) {
                try (Stream<String> stream = Files.lines(logFile.toPath())) {
                    List<String> matchingLines = stream
                        .filter(line -> line.contains("玩家: " + playerName))
                        .collect(Collectors.toList());
                    
                    // 倒序添加
                    for (int i = matchingLines.size() - 1; i >= 0 && history.size() < lines; i--) {
                        history.add(matchingLines.get(i));
                    }
                    
                    if (history.size() >= lines) {
                        break;
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("读取日志文件失败: " + logFile.getName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取玩家历史记录失败: " + e.getMessage());
        }
        
        return history;
    }
    
    /**
     * 获取日志目录路径
     */
    public File getLogsDirectory() {
        return logsDirectory;
    }
}
