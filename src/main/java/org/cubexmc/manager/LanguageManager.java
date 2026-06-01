package org.cubexmc.manager;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.update.LanguageUpdater;
import org.cubexmc.utils.SchedulerUtil;

public class LanguageManager {
    private static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final String FALLBACK_LANGUAGE = "en_US";
    private static final String[] BUNDLED_LANGUAGES = {FALLBACK_LANGUAGE, DEFAULT_LANGUAGE};

    private final RuleGems plugin;
    private String language;
    private FileConfiguration langConfig;
    private String prefix;
    private final Map<String, FileConfiguration> loadedLanguages = new HashMap<>();
    private final Set<String> missingMessageWarnings = new HashSet<>();

    public LanguageManager(RuleGems plugin) {
        this.plugin = plugin;
    }

    private boolean copyLangFileIfNotExists(String lang) {
        File outFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (!outFile.exists()) {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (java.io.InputStream resource = plugin.getResource("lang/" + lang + ".yml")) {
                if (resource != null) {
                    // Close the probe stream before copying the resource again
                } else {
                    plugin.getLogger().warning("Bundled language file missing from jar: lang/" + lang + ".yml");
                    return false;
                }
            } catch (java.io.IOException ignored) {
            }
            plugin.saveResource("lang/" + lang + ".yml", false);
        }
        return true;
    }

    private void ensureLanguageUpdated(String lang) {
        if (!copyLangFileIfNotExists(lang)) {
            return;
        }
        File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        LanguageUpdater.merge(plugin, langFile, "lang/" + lang + ".yml");
    }

    public void updateBundledLanguages() {
        for (String lang : BUNDLED_LANGUAGES) {
            ensureLanguageUpdated(lang);
        }
    }

    public void loadLanguage() {
        loadedLanguages.clear();
        missingMessageWarnings.clear();
        this.langConfig = null;
        // reread language from config.yml
        this.language = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        ensureLanguageUpdated(language);
        loadLangConfig(language);
        if (langConfig == null) {
            this.language = DEFAULT_LANGUAGE;
            if (copyLangFileIfNotExists(DEFAULT_LANGUAGE)) {
                ensureLanguageUpdated(DEFAULT_LANGUAGE);
                loadLangConfig(DEFAULT_LANGUAGE);
            }
        }
        this.prefix = langConfig != null ? langConfig.getString("prefix", "&7[&6RuleGems&7] ") : "&7[&6RuleGems&7] ";
    }

    private void loadLangConfig(String lang) {
        // Ensure the requested language file exists; then load it
        if (!copyLangFileIfNotExists(lang)) {
            return;
        }
        File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (langFile.exists()) {
            FileConfiguration loaded = YamlConfiguration.loadConfiguration(langFile);
            loadedLanguages.put(lang, loaded);
            if (lang.equalsIgnoreCase(this.language)) {
                this.langConfig = loaded;
            }
        }
    }

    /**
     * 获取原始消息（不包含颜色转换）
     */
    public String getMessage(String path) {
        return getMessage(path, this.language);
    }

    public String getMessage(String path, String lang) {
        String message = lookupMessage(lang, path);
        if (message != null) {
            return message;
        }
        warnMissingKey(path, lang);
        return path;
    }

    public List<String> getMessageList(String path) {
        return getMessageList(path, this.language);
    }

    public List<String> getMessageList(String path, String lang) {
        List<String> messages = lookupMessageList(lang, path);
        if (messages != null && !messages.isEmpty()) {
            return messages;
        }
        warnMissingKey(path, lang);
        return Collections.emptyList();
    }

    private String lookupMessage(String lang, String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        FileConfiguration preferred = getLanguageConfig(lang);
        if (preferred != null) {
            String message = preferred.getString(path);
            if (message != null) {
                return message;
            }
        }

        if (langConfig != null && preferred != langConfig) {
            String message = langConfig.getString(path);
            if (message != null) {
                return message;
            }
        }

        if (!FALLBACK_LANGUAGE.equalsIgnoreCase(lang)) {
            FileConfiguration fallback = getLanguageConfig(FALLBACK_LANGUAGE);
            if (fallback != null) {
                String message = fallback.getString(path);
                if (message != null) {
                    return message;
                }
            }
        }

        if (!DEFAULT_LANGUAGE.equalsIgnoreCase(lang) && !DEFAULT_LANGUAGE.equalsIgnoreCase(FALLBACK_LANGUAGE)) {
            FileConfiguration defaultConfig = getLanguageConfig(DEFAULT_LANGUAGE);
            if (defaultConfig != null) {
                return defaultConfig.getString(path);
            }
        }
        return null;
    }

    private List<String> lookupMessageList(String lang, String path) {
        if (path == null || path.isEmpty()) {
            return Collections.emptyList();
        }

        FileConfiguration preferred = getLanguageConfig(lang);
        if (preferred != null && preferred.isList(path)) {
            return preferred.getStringList(path);
        }

        if (langConfig != null && preferred != langConfig && langConfig.isList(path)) {
            return langConfig.getStringList(path);
        }

        if (!FALLBACK_LANGUAGE.equalsIgnoreCase(lang)) {
            FileConfiguration fallback = getLanguageConfig(FALLBACK_LANGUAGE);
            if (fallback != null && fallback.isList(path)) {
                return fallback.getStringList(path);
            }
        }

        if (!DEFAULT_LANGUAGE.equalsIgnoreCase(lang) && !DEFAULT_LANGUAGE.equalsIgnoreCase(FALLBACK_LANGUAGE)) {
            FileConfiguration defaultConfig = getLanguageConfig(DEFAULT_LANGUAGE);
            if (defaultConfig != null && defaultConfig.isList(path)) {
                return defaultConfig.getStringList(path);
            }
        }
        return null;
    }

    private FileConfiguration getLanguageConfig(String lang) {
        String effectiveLang = (lang == null || lang.isEmpty()) ? this.language : lang;
        FileConfiguration cached = loadedLanguages.get(effectiveLang);
        if (cached != null) {
            return cached;
        }

        File langFile = new File(plugin.getDataFolder(), "lang/" + effectiveLang + ".yml");
        if (!langFile.exists()) {
            ensureLanguageUpdated(effectiveLang);
        }
        if (!langFile.exists()) {
            return null;
        }

        FileConfiguration loaded = YamlConfiguration.loadConfiguration(langFile);
        loadedLanguages.put(effectiveLang, loaded);
        return loaded;
    }

    private void warnMissingKey(String path, String lang) {
        String warnKey = (lang == null ? this.language : lang) + ":" + path;
        if (missingMessageWarnings.add(warnKey)) {
            plugin.getLogger().warning("Missing language message '" + path + "' for '" + warnKey.split(":", 2)[0]
                    + "', fallback exhausted.");
        }
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
        if (message != null && prefix != null)
            message = message.replace("%prefix%", prefix);
        return message;
    }

    public String formatText(String message, Map<String, String> placeholders) {
        if (placeholders != null && message != null) {
            for (String key : placeholders.keySet()) {
                String value = placeholders.get(key);
                if (value == null) {
                    continue;
                }
                message = message.replace("%" + key + "%", value);
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
        String key = path.startsWith("logger.") ? path : ("logger." + path);
        String message = formatMessage(key, placeholders);
        if (message == null) {
            return;
        }
        if (plugin.getServer() != null && plugin.getServer().getConsoleSender() != null) {
            plugin.getServer().getConsoleSender().sendMessage(translateColorCodes(message));
            return;
        }
        Logger logger = plugin.getLogger();
        logger.info(ChatColor.stripColor(translateColorCodes(message)));
    }

    /**
     * 转换颜色代码
     */
    public String translateColorCodes(String message) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(message);
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
