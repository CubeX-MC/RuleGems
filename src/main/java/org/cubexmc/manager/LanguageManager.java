package org.cubexmc.manager;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.utils.SchedulerUtil;

public class LanguageManager {
    private final RuleGems plugin;
    private String language;
    private FileConfiguration langConfig;
    private String prefix;

    public LanguageManager(RuleGems plugin) {
        this.plugin = plugin;
    }

    private void copyLangFileIfNotExists(String lang) {
        File outFile = new File(plugin.getDataFolder(), "local/" + lang + ".yml");
        if (!outFile.exists()) {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            plugin.saveResource("local/" + lang + ".yml", false);
        }
    }

    public void loadLanguage() {
        // reread language from config.yml
        this.language = plugin.getConfig().getString("language", "zh");
        loadLangConfig(language);
        if (langConfig == null) {
            this.language = "zh";
            copyLangFileIfNotExists("zh");
            loadLangConfig("zh");
        }
    this.prefix = langConfig.getString("prefix", "&7[&6RuleGems&7] ");
    }

    private void loadLangConfig(String lang) {
        // Ensure the requested language file exists; then load it
        copyLangFileIfNotExists(lang);
        File langFile = new File(plugin.getDataFolder(), "local/" + lang + ".yml");
        if (langFile.exists()) {
            this.langConfig = YamlConfiguration.loadConfiguration(langFile);
        }
    }

    /**
     * 获取原始消息（不包含颜色转换）
     */
    public String getMessage(String path) {
        return getMessage(path, this.language);
    }

    public String getMessage(String path, String lang) {
        if (langConfig == null) {
            return "Missing language file: " + lang;
        }
        String message = langConfig.getString(path);
        return message != null ? message : "Missing message: " + path;
    }

    /**
     * 格式化消息，替换变量但不转换颜色
     */
    public String formatMessage(String path, Map<String, String> placeholders) {
        return formatMessage(path, this.language, placeholders);
    }

    public String formatMessage(String path, String lang, Map<String, String> placeholders) {
        String message = getMessage(path, lang);
        message = formatText(message, placeholders);
        // 替换前缀
        if (message != null)
            message = message.replace("%prefix%", prefix);
        return message;
    }

    public String formatText(String message, Map<String, String> placeholders) {
        if (placeholders != null) {
            for (String key : placeholders.keySet()) {
                message = message.replace("%" + key + "%", placeholders.get(key));
            }
        }
        return message;
    }

    /**
     * 发送彩色消息给玩家
     */
    public void sendMessage(CommandSender sender, String path) {
        sendMessage(sender, path, new HashMap<>());
    }

    public void sendMessage(CommandSender sender, String path, Map<String, String> placeholders) {
        String message = formatMessage("messages." + path, placeholders);
        sender.sendMessage(translateColorCodes(message));
    }

    /**
     * 发送彩色消息到控制台
     */
    public void logMessage(String path) {
        logMessage(path, new HashMap<>());
    }

    public void logMessage(String path, Map<String, String> placeholders) {
        Logger logger = plugin.getLogger();
        String key = path.startsWith("logger.") ? path : ("logger." + path);
        String message = formatMessage(key, placeholders);
        logger.info(translateColorCodes(message));
    }

    /**
     * 转换颜色代码
     */
    private String translateColorCodes(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }



    public String getLanguage() {
        return language;
    }

    /**
     * 显示标题消息
     */
    public void showTitle(Player player, String path, Map<String, String> placeholders) {
        if (langConfig == null) return;
        List<String> titleMessages = langConfig.getStringList("title." + path);
        // 如果没有配置标题消息，直接返回
     if (titleMessages.size() == 1) {
        SchedulerUtil.entityRun(plugin, player, () -> player.sendTitle(
            translateColorCodes(formatText(titleMessages.get(0), placeholders)),
            null,
            10,
            70,
            20), 0L, -1L);
     } else if (titleMessages.size() == 2) {
        SchedulerUtil.entityRun(plugin, player, () -> player.sendTitle(
            translateColorCodes(formatText(titleMessages.get(0), placeholders)),
            translateColorCodes(formatText(titleMessages.get(1), placeholders)),
            10,
            70,
            20), 0L, -1L);
     }
    }
} 