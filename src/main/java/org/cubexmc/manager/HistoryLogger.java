package org.cubexmc.manager;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 历史记录管理器 - 记录宝石权限更迭的"史官"
 * 负责将权力变更记录保存到日志文件
 */
public class HistoryLogger {
    
    private final RuleGems plugin;
    private final LanguageManager languageManager;
    private final File logsDirectory;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat fileNameFormat;
    
    public HistoryLogger(RuleGems plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;
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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("player_uuid", player.getUniqueId().toString());
        String gemName = gemDisplayName != null ? gemDisplayName : gemKey;
        placeholders.put("gem_name", gemName);
        placeholders.put("gem_key", gemKey);

        String previousOwnerSection = "";
        if (previousOwner != null && !previousOwner.isEmpty()) {
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("previous_owner", previousOwner);
            previousOwnerSection = formatHistoryMessage("history.redeem.previous_owner", sectionPlaceholders);
            if (previousOwnerSection.isEmpty()) {
                previousOwnerSection = " | 前任: " + previousOwner;
            }
        }

        String permissionsSection = "";
        if (permissions != null && !permissions.isEmpty()) {
            String joined = String.join(", ", permissions);
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("permissions", joined);
            permissionsSection = formatHistoryMessage("history.redeem.permissions", sectionPlaceholders);
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Permissions: [" + joined + "]";
            }
        }

        String vaultGroupSection = "";
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("vault_group", vaultGroup);
            vaultGroupSection = formatHistoryMessage("history.redeem.vault_group", sectionPlaceholders);
            if (vaultGroupSection.isEmpty()) {
                vaultGroupSection = " | Vault group: " + vaultGroup;
            }
        }

        placeholders.put("previous_owner_section", previousOwnerSection);
        placeholders.put("permissions_section", permissionsSection);
        placeholders.put("vault_group_section", vaultGroupSection);

        String message = formatHistoryMessage("history.redeem.entry", placeholders);
        if (message.isEmpty()) {
            message = buildFallbackRedeem(player, gemKey, gemDisplayName, permissions, vaultGroup, previousOwner);
        }

        logEntry.append(message);

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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player_name", playerName != null ? playerName : "Unknown");
        placeholders.put("player_uuid", playerUuid);
        String gemName = gemDisplayName != null ? gemDisplayName : gemKey;
        placeholders.put("gem_name", gemName);
        placeholders.put("gem_key", gemKey);

        String reasonSection = "";
        if (reason != null && !reason.isEmpty()) {
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("reason", reason);
            reasonSection = formatHistoryMessage("history.revoke.reason", sectionPlaceholders);
            if (reasonSection.isEmpty()) {
                reasonSection = " | 原因: " + reason;
            }
        }

        String permissionsSection = "";
        if (permissions != null && !permissions.isEmpty()) {
            String joined = String.join(", ", permissions);
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("permissions", joined);
            permissionsSection = formatHistoryMessage("history.revoke.permissions", sectionPlaceholders);
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Revoked permissions: [" + joined + "]";
            }
        }

        String vaultGroupSection = "";
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("vault_group", vaultGroup);
            vaultGroupSection = formatHistoryMessage("history.revoke.vault_group", sectionPlaceholders);
            if (vaultGroupSection.isEmpty()) {
                vaultGroupSection = " | Revoked group: " + vaultGroup;
            }
        }

        placeholders.put("reason_section", reasonSection);
        placeholders.put("permissions_section", permissionsSection);
        placeholders.put("vault_group_section", vaultGroupSection);

        String message = formatHistoryMessage("history.revoke.entry", placeholders);
        if (message.isEmpty()) {
            message = buildFallbackRevoke(playerUuid, playerName, gemKey, gemDisplayName, permissions, vaultGroup, reason);
        }

        logEntry.append(message);

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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("player_uuid", player.getUniqueId().toString());
        placeholders.put("gem_count", String.valueOf(gemCount));

        String previousOwnerSection = "";
        if (previousFullSetOwner != null && !previousFullSetOwner.isEmpty()) {
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("previous_owner", previousFullSetOwner);
            previousOwnerSection = formatHistoryMessage("history.full_set.previous_owner", sectionPlaceholders);
            if (previousOwnerSection.isEmpty()) {
                previousOwnerSection = " | 前任统治者: " + previousFullSetOwner;
            }
        }

        String permissionsSection = "";
        if (permissions != null && !permissions.isEmpty()) {
            String joined = String.join(", ", permissions);
            Map<String, String> sectionPlaceholders = new HashMap<>();
            sectionPlaceholders.put("permissions", joined);
            permissionsSection = formatHistoryMessage("history.full_set.permissions", sectionPlaceholders);
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Total permissions: [" + joined + "]";
            }
        }

        placeholders.put("previous_owner_section", previousOwnerSection);
        placeholders.put("permissions_section", permissionsSection);

        String message = formatHistoryMessage("history.full_set.entry", placeholders);
        if (message.isEmpty()) {
            message = buildFallbackFullSet(player, gemCount, permissions, previousFullSetOwner);
        }

        logEntry.append(message);

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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("gem_key", gemKey);
        placeholders.put("location", location);

        String message = formatHistoryMessage("history.place.entry", placeholders);
        if (message.isEmpty()) {
            message = buildFallbackPlace(player, gemKey, location);
        }

        logEntry.append(message);

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

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("gem_key", gemKey);
        placeholders.put("location", location);

        String message = formatHistoryMessage("history.break.entry", placeholders);
        if (message.isEmpty()) {
            message = buildFallbackBreak(player, gemKey, location);
        }

        logEntry.append(message);

        writeLog(logEntry.toString());
    }

    private String formatHistoryMessage(String key, Map<String, String> placeholders) {
        if (languageManager == null) {
            return "";
        }
        String template = languageManager.getMessage(key);
        if (template == null || template.startsWith("Missing message")) {
            return "";
        }
        if (placeholders != null && !placeholders.isEmpty()) {
            template = languageManager.formatText(template, placeholders);
        }
        return ChatColor.translateAlternateColorCodes('&', template);
    }

    private String buildFallbackRedeem(Player player, String gemKey, String gemDisplayName,
                                       List<String> permissions, String vaultGroup, String previousOwner) {
        StringBuilder builder = new StringBuilder("§e[Gem Redeem] ");
        builder.append("Player: ").append(player.getName()).append(" (").append(player.getUniqueId()).append(") ");
        builder.append("| Gem: ").append(gemDisplayName != null ? gemDisplayName : gemKey).append(" (").append(gemKey).append(")");
        if (previousOwner != null && !previousOwner.isEmpty()) {
            builder.append(" | Previous: ").append(previousOwner);
        }
        if (permissions != null && !permissions.isEmpty()) {
            builder.append(" | Permissions: [").append(String.join(", ", permissions)).append("]");
        }
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            builder.append(" | Vault group: ").append(vaultGroup);
        }
        return builder.toString();
    }

    private String buildFallbackRevoke(String playerUuid, String playerName, String gemKey, String gemDisplayName,
                                       List<String> permissions, String vaultGroup, String reason) {
        StringBuilder builder = new StringBuilder("§c[Permission Revoke] ");
        builder.append("Player: ").append(playerName != null ? playerName : "Unknown").append(" (").append(playerUuid).append(") ");
        builder.append("| Gem: ").append(gemDisplayName != null ? gemDisplayName : gemKey).append(" (").append(gemKey).append(")");
        if (reason != null && !reason.isEmpty()) {
            builder.append(" | Reason: ").append(reason);
        }
        if (permissions != null && !permissions.isEmpty()) {
            builder.append(" | Revoked permissions: [").append(String.join(", ", permissions)).append("]");
        }
        if (vaultGroup != null && !vaultGroup.isEmpty()) {
            builder.append(" | Revoked group: ").append(vaultGroup);
        }
        return builder.toString();
    }

    private String buildFallbackFullSet(Player player, int gemCount, List<String> permissions, String previousFullSetOwner) {
        StringBuilder builder = new StringBuilder("§6[Full Set Redeem] ");
        builder.append("Player: ").append(player.getName()).append(" (").append(player.getUniqueId()).append(") ");
        builder.append("| Gem count: ").append(gemCount);
        if (previousFullSetOwner != null && !previousFullSetOwner.isEmpty()) {
            builder.append(" | Previous ruler: ").append(previousFullSetOwner);
        }
        if (permissions != null && !permissions.isEmpty()) {
            builder.append(" | Total permissions: [").append(String.join(", ", permissions)).append("]");
        }
        return builder.toString();
    }

    private String buildFallbackPlace(Player player, String gemKey, String location) {
        return "§a[Gem Placed] Player: " + player.getName() + " | Gem: " + gemKey + " | Location: " + location;
    }

    private String buildFallbackBreak(Player player, String gemKey, String location) {
        return "§c[Gem Broken] Player: " + player.getName() + " | Gem: " + gemKey + " | Location: " + location;
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
            plugin.getLogger().warning("Failed to write history log: " + e.getMessage());
        }
    }
    
    /**
     * 获取最近的N条历史记录
     * 
     * @param lines 要读取的行数
     * @return 历史记录列表
     */
    public HistoryPage getRecentHistoryPage(int page, int pageSize) {
        List<String> entries = new ArrayList<>();
        int total = 0;
        int startIndex = Math.max(0, (page - 1) * pageSize);
        int endIndex = startIndex + pageSize;

        try {
            File[] logFiles = logsDirectory.listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) {
                return new HistoryPage(entries, total);
            }

            java.util.Arrays.sort(logFiles, (a, b) -> b.getName().compareTo(a.getName()));

            for (File logFile : logFiles) {
                List<String> fileLines;
                try (Stream<String> stream = Files.lines(logFile.toPath())) {
                    fileLines = stream.collect(Collectors.toList());
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read log file: " + logFile.getName());
                    continue;
                }

                for (int i = fileLines.size() - 1; i >= 0; i--) {
                    String line = fileLines.get(i);
                    if (total >= startIndex && total < endIndex) {
                        entries.add(line);
                    }
                    total++;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read history: " + e.getMessage());
        }

        return new HistoryPage(entries, total);
    }
    
    /**
     * 获取指定玩家的历史记录
     * 
     * @param playerName 玩家名称
     * @param lines 最多返回的行数
     * @return 该玩家的历史记录
     */
    public HistoryPage getPlayerHistoryPage(String playerName, int page, int pageSize) {
        List<String> entries = new ArrayList<>();
        int total = 0;
        int startIndex = Math.max(0, (page - 1) * pageSize);
        int endIndex = startIndex + pageSize;
        String lowerPlayer = playerName.toLowerCase(java.util.Locale.ROOT);

        try {
            File[] logFiles = logsDirectory.listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) {
                return new HistoryPage(entries, total);
            }

            java.util.Arrays.sort(logFiles, (a, b) -> b.getName().compareTo(a.getName()));

            for (File logFile : logFiles) {
                List<String> fileLines;
                try (Stream<String> stream = Files.lines(logFile.toPath())) {
                    fileLines = stream.collect(Collectors.toList());
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read log file: " + logFile.getName());
                    continue;
                }

                for (int i = fileLines.size() - 1; i >= 0; i--) {
                    String line = fileLines.get(i);
                    if (!lineMatchesPlayer(line, lowerPlayer)) {
                        continue;
                    }
                    if (total >= startIndex && total < endIndex) {
                        entries.add(line);
                    }
                    total++;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read player history: " + e.getMessage());
        }

        return new HistoryPage(entries, total);
    }

    public List<String> getRecentHistory(int lines) {
        HistoryPage page = getRecentHistoryPage(1, Math.max(1, lines));
        return page.getEntries();
    }

    public List<String> getPlayerHistory(String playerName, int lines) {
        HistoryPage page = getPlayerHistoryPage(playerName, 1, Math.max(1, lines));
        return page.getEntries();
    }

    public static final class HistoryPage {
        private final List<String> entries;
        private final int totalCount;

        public HistoryPage(List<String> entries, int totalCount) {
            this.entries = entries;
            this.totalCount = totalCount;
        }

        public List<String> getEntries() {
            return entries;
        }

        public int getTotalCount() {
            return totalCount;
        }
    }

    private boolean lineMatchesPlayer(String line, String lowerPlayer) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String lowerLine = line.toLowerCase(java.util.Locale.ROOT);
        return lowerLine.contains("player: " + lowerPlayer)
                || lowerLine.contains(lowerPlayer + " (")
                || lowerLine.contains("| " + lowerPlayer + " |")
                || lowerLine.contains("\u73a9\u5bb6: " + lowerPlayer); // 兼容旧中文日志
    }
    
}
