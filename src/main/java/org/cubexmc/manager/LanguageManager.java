package org.cubexmc.manager;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.update.LanguageUpdater;
import org.cubexmc.utils.SchedulerUtil;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class LanguageManager {
    private static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final String FALLBACK_LANGUAGE = "en_US";
    private static final String[] BUNDLED_LANGUAGES = {FALLBACK_LANGUAGE, DEFAULT_LANGUAGE};
    private static final Pattern MINIMESSAGE_PLACEHOLDER = Pattern.compile("(?<!\\\\)<([a-z0-9_:-]+)>");
    private static final Pattern MINIMESSAGE_PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_:-]+");
    private static final Set<String> MINIMESSAGE_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "obfuscated", "bold", "strikethrough", "underlined", "italic", "reset");

    private final RuleGems plugin;
    private final I18nService i18n;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private String language;
    private FileConfiguration langConfig;
    private String prefix;
    private String prefixTemplate;
    private final Map<String, FileConfiguration> loadedLanguages = new HashMap<>();
    private final Set<String> missingMessageWarnings = new HashSet<>();

    public LanguageManager(RuleGems plugin) {
        this.plugin = plugin;
        this.i18n = I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(() -> language == null ? DEFAULT_LANGUAGE : language)
                .defaultLocale(FALLBACK_LANGUAGE)
                .fallbackLocales(List.of(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE))
                .bundledLocales(List.of(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE))
                .prefixKey("prefix")
                .prefixToken("<prefix>")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE));
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
        i18n.setCurrentLocale(this.language);
        i18n.reload();
        this.prefixTemplate = langConfig != null ? langConfig.getString("prefix", "<gray>[<red>RuleGems<gray>]<reset>")
                : "<gray>[<red>RuleGems<gray>]<reset>";
        this.prefix = renderTemplate(prefixTemplate, Collections.emptyMap());
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
            return renderTemplatePreservingPlaceholders(path, message);
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
            return messages.stream()
                    .map(message -> renderTemplatePreservingPlaceholders(path, message))
                    .toList();
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
        if (lookupMessage(lang, path) == null) {
            warnMissingKey(path, lang);
            return path;
        }
        return i18n.message(path, lang, placeholders == null ? Collections.emptyMap() : placeholders);
    }

    public String formatText(String message, Map<String, String> placeholders) {
        if (placeholders != null && message != null) {
            for (String key : placeholders.keySet()) {
                String value = placeholders.get(key);
                if (value == null) {
                    continue;
                }
                message = message.replace("%" + key + "%", value);
                message = message.replace("<" + key.toLowerCase(java.util.Locale.ROOT) + ">", value);
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

    private String renderTemplatePreservingPlaceholders(String path, String template) {
        if ("prefix".equals(path)) {
            return renderTemplate(template, Collections.emptyMap());
        }
        String legacyCompatible = preserveUnresolvedPlaceholders(template);
        return renderTemplate(legacyCompatible, Collections.emptyMap());
    }

    private String preserveUnresolvedPlaceholders(String template) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        Matcher matcher = MINIMESSAGE_PLACEHOLDER.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (isMiniMessageTag(name)) {
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("%" + name + "%"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean isMiniMessageTag(String name) {
        return name != null && (MINIMESSAGE_TAGS.contains(name) || name.startsWith("#"));
    }

    private String renderTemplate(String template, Map<String, String> placeholders) {
        String resolvedTemplate = template == null ? "" : template;
        if (prefixTemplate != null && !resolvedTemplate.equals(prefixTemplate)) {
            resolvedTemplate = resolvedTemplate.replace("<prefix>", prefixTemplate);
        }
        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (MINIMESSAGE_PLACEHOLDER_NAME.matcher(name).matches()) {
                    builder.resolver(Placeholder.unparsed(name, entry.getValue() == null ? "" : entry.getValue()));
                }
            }
        }
        return legacySerializer.serialize(miniMessage.deserialize(resolvedTemplate, builder.build()));
    }

    String renderTitleLine(String template, Map<String, String> placeholders) {
        return renderTemplate(template, placeholders);
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
            renderTitleLine(titleMessages.get(0), placeholders),
            null,
            10,
            70,
            20), 0L, -1L);
     } else if (titleMessages.size() == 2) {
        SchedulerUtil.entityRun(plugin, player, () -> player.sendTitle(
            renderTitleLine(titleMessages.get(0), placeholders),
            renderTitleLine(titleMessages.get(1), placeholders),
            10,
            70,
            20), 0L, -1L);
     }
    }
} 
