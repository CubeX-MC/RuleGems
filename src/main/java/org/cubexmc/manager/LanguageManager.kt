package org.cubexmc.manager

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.update.LanguageUpdater
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.logging.Logger
import java.util.regex.Matcher
import java.util.regex.Pattern

class LanguageManager(private val plugin: RuleGems) {
    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale { language ?: DEFAULT_LANGUAGE }
            .defaultLocale(FALLBACK_LANGUAGE)
            .fallbackLocales(listOf(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE))
            .bundledLocales(listOf(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    )
    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val legacySerializer: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    var language: String? = null
        private set
    private var langConfig: FileConfiguration? = null
    private var prefix: String? = null
    private var prefixTemplate: String? = null
    private val loadedLanguages: MutableMap<String, FileConfiguration> = HashMap()
    private val missingMessageWarnings: MutableSet<String> = HashSet()

    private fun copyLangFileIfNotExists(lang: String): Boolean {
        val outFile = File(plugin.dataFolder, "lang/$lang.yml")
        if (!outFile.exists()) {
            val parent = outFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            try {
                plugin.getResource("lang/$lang.yml").use { resource ->
                    if (resource == null) {
                        plugin.logger.warning("Bundled language file missing from jar: lang/$lang.yml")
                        return false
                    }
                }
            } catch (_: IOException) {
            }
            plugin.saveResource("lang/$lang.yml", false)
        }
        return true
    }

    private fun ensureLanguageUpdated(lang: String) {
        if (!copyLangFileIfNotExists(lang)) {
            return
        }
        val langFile = File(plugin.dataFolder, "lang/$lang.yml")
        LanguageUpdater.merge(plugin, langFile, "lang/$lang.yml")
    }

    fun updateBundledLanguages() {
        for (lang in BUNDLED_LANGUAGES) {
            ensureLanguageUpdated(lang)
        }
    }

    fun loadLanguage() {
        loadedLanguages.clear()
        missingMessageWarnings.clear()
        langConfig = null
        language = plugin.config.getString("language", DEFAULT_LANGUAGE)
        ensureLanguageUpdated(language ?: DEFAULT_LANGUAGE)
        loadLangConfig(language ?: DEFAULT_LANGUAGE)
        if (langConfig == null) {
            language = DEFAULT_LANGUAGE
            if (copyLangFileIfNotExists(DEFAULT_LANGUAGE)) {
                ensureLanguageUpdated(DEFAULT_LANGUAGE)
                loadLangConfig(DEFAULT_LANGUAGE)
            }
        }
        i18n.setCurrentLocale(language)
        i18n.reload()
        prefixTemplate = langConfig?.getString("prefix", "<gray>[<red>RuleGems<gray>]<reset>")
            ?: "<gray>[<red>RuleGems<gray>]<reset>"
        prefix = renderTemplate(prefixTemplate, emptyMap())
    }

    private fun loadLangConfig(lang: String) {
        if (!copyLangFileIfNotExists(lang)) {
            return
        }
        val langFile = File(plugin.dataFolder, "lang/$lang.yml")
        if (langFile.exists()) {
            val loaded = YamlConfiguration.loadConfiguration(langFile)
            loadedLanguages[lang] = loaded
            if (lang.equals(language, ignoreCase = true)) {
                langConfig = loaded
            }
        }
    }

    fun getMessage(path: String?): String = getMessage(path, language)

    fun getMessage(path: String?, lang: String?): String {
        val message = lookupMessage(lang, path)
        if (message != null) {
            return renderTemplatePreservingPlaceholders(path, message)
        }
        warnMissingKey(path, lang)
        return path ?: ""
    }

    fun getMessageList(path: String?): List<String> = getMessageList(path, language)

    fun getMessageList(path: String?, lang: String?): List<String> {
        val messages = lookupMessageList(lang, path)
        if (!messages.isNullOrEmpty()) {
            return messages.map { message -> renderTemplatePreservingPlaceholders(path, message) }
        }
        warnMissingKey(path, lang)
        return emptyList()
    }

    private fun lookupMessage(lang: String?, path: String?): String? {
        if (path.isNullOrEmpty()) {
            return ""
        }

        val preferred = getLanguageConfig(lang)
        if (preferred != null) {
            val message = preferred.getString(path)
            if (message != null) {
                return message
            }
        }

        val currentLangConfig = langConfig
        if (currentLangConfig != null && preferred !== currentLangConfig) {
            val message = currentLangConfig.getString(path)
            if (message != null) {
                return message
            }
        }

        if (!FALLBACK_LANGUAGE.equals(lang, ignoreCase = true)) {
            val fallback = getLanguageConfig(FALLBACK_LANGUAGE)
            if (fallback != null) {
                val message = fallback.getString(path)
                if (message != null) {
                    return message
                }
            }
        }

        if (!DEFAULT_LANGUAGE.equals(lang, ignoreCase = true) &&
            !DEFAULT_LANGUAGE.equals(FALLBACK_LANGUAGE, ignoreCase = true)
        ) {
            val defaultConfig = getLanguageConfig(DEFAULT_LANGUAGE)
            if (defaultConfig != null) {
                return defaultConfig.getString(path)
            }
        }
        return null
    }

    private fun lookupMessageList(lang: String?, path: String?): List<String>? {
        if (path.isNullOrEmpty()) {
            return emptyList()
        }

        val preferred = getLanguageConfig(lang)
        if (preferred != null && preferred.isList(path)) {
            return preferred.getStringList(path)
        }

        val currentLangConfig = langConfig
        if (currentLangConfig != null && preferred !== currentLangConfig && currentLangConfig.isList(path)) {
            return currentLangConfig.getStringList(path)
        }

        if (!FALLBACK_LANGUAGE.equals(lang, ignoreCase = true)) {
            val fallback = getLanguageConfig(FALLBACK_LANGUAGE)
            if (fallback != null && fallback.isList(path)) {
                return fallback.getStringList(path)
            }
        }

        if (!DEFAULT_LANGUAGE.equals(lang, ignoreCase = true) &&
            !DEFAULT_LANGUAGE.equals(FALLBACK_LANGUAGE, ignoreCase = true)
        ) {
            val defaultConfig = getLanguageConfig(DEFAULT_LANGUAGE)
            if (defaultConfig != null && defaultConfig.isList(path)) {
                return defaultConfig.getStringList(path)
            }
        }
        return null
    }

    private fun getLanguageConfig(lang: String?): FileConfiguration? {
        val effectiveLang = if (lang.isNullOrEmpty()) language else lang
        if (effectiveLang.isNullOrEmpty()) {
            return null
        }
        val cached = loadedLanguages[effectiveLang]
        if (cached != null) {
            return cached
        }

        val langFile = File(plugin.dataFolder, "lang/$effectiveLang.yml")
        if (!langFile.exists()) {
            ensureLanguageUpdated(effectiveLang)
        }
        if (!langFile.exists()) {
            return null
        }

        val loaded = YamlConfiguration.loadConfiguration(langFile)
        loadedLanguages[effectiveLang] = loaded
        return loaded
    }

    private fun warnMissingKey(path: String?, lang: String?) {
        val warnKey = (lang ?: language) + ":" + path
        if (missingMessageWarnings.add(warnKey)) {
            plugin.logger.warning(
                "Missing language message '$path' for '${warnKey.split(":", limit = 2)[0]}', fallback exhausted.",
            )
        }
    }

    fun formatMessage(path: String?, placeholders: Map<String, String>?): String =
        formatMessage(path, language, placeholders)

    fun formatMessage(path: String?, lang: String?, placeholders: Map<String, String>?): String {
        if (lookupMessage(lang, path) == null) {
            warnMissingKey(path, lang)
            return path ?: ""
        }
        return i18n.message(path, lang ?: language ?: DEFAULT_LANGUAGE, placeholders ?: emptyMap<String, String>())
    }

    fun formatText(message: String?, placeholders: Map<String, String>?): String? {
        var formatted = message
        if (placeholders != null && formatted != null) {
            for ((key, value) in placeholders) {
                if (value == null) {
                    continue
                }
                formatted = formatted
                    ?.replace("%$key%", value)
                    ?.replace("<${key.lowercase(Locale.ROOT)}>", value)
            }
        }
        return formatted
    }

    fun sendMessage(sender: CommandSender, path: String) {
        sendMessage(sender, path, HashMap())
    }

    fun sendMessage(sender: CommandSender, path: String, placeholders: Map<String, String>?) {
        val message = formatMessage("messages.$path", placeholders)
        sender.sendMessage(translateColorCodes(message))
    }

    fun logMessage(path: String) {
        logMessage(path, HashMap())
    }

    fun logMessage(path: String, placeholders: Map<String, String>?) {
        val key = if (path.startsWith("logger.")) path else "logger.$path"
        val message = formatMessage(key, placeholders)
        if (plugin.server != null && plugin.server.consoleSender != null) {
            plugin.server.consoleSender.sendMessage(translateColorCodes(message))
            return
        }
        val logger: Logger = plugin.logger
        logger.info(ChatColor.stripColor(translateColorCodes(message)))
    }

    fun translateColorCodes(message: String?): String = ColorUtils.translateColorCodes(message) ?: ""

    private fun renderTemplatePreservingPlaceholders(path: String?, template: String?): String {
        if ("prefix" == path) {
            return renderTemplate(template, emptyMap())
        }
        val legacyCompatible = preserveUnresolvedPlaceholders(template)
        return renderTemplate(legacyCompatible, emptyMap())
    }

    private fun preserveUnresolvedPlaceholders(template: String?): String {
        if (template.isNullOrEmpty()) {
            return template ?: ""
        }
        val matcher = MINIMESSAGE_PLACEHOLDER.matcher(template)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val name = matcher.group(1)
            if (isMiniMessageTag(name)) {
                continue
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("%$name%"))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun isMiniMessageTag(name: String?): Boolean =
        name != null && (MINIMESSAGE_TAGS.contains(name) || name.startsWith("#"))

    private fun renderTemplate(template: String?, placeholders: Map<String, String>?): String {
        var resolvedTemplate = template ?: ""
        val currentPrefixTemplate = prefixTemplate
        if (currentPrefixTemplate != null && resolvedTemplate != currentPrefixTemplate) {
            resolvedTemplate = resolvedTemplate.replace("<prefix>", currentPrefixTemplate)
        }
        val builder = TagResolver.builder()
        if (placeholders != null) {
            for ((key, value) in placeholders) {
                val name = key.lowercase(Locale.ROOT)
                if (MINIMESSAGE_PLACEHOLDER_NAME.matcher(name).matches()) {
                    builder.resolver(Placeholder.unparsed(name, value))
                }
            }
        }
        return legacySerializer.serialize(miniMessage.deserialize(resolvedTemplate, builder.build()))
    }

    fun renderTitleLine(template: String?, placeholders: Map<String, String>?): String =
        renderTemplate(template, placeholders)

    fun showTitle(player: Player, path: String, placeholders: Map<String, String>?) {
        val currentLangConfig = langConfig ?: return
        val titleMessages = currentLangConfig.getStringList("title.$path")
        if (titleMessages.size == 1) {
            SchedulerUtil.entityRun(
                plugin,
                player,
                {
                    player.sendTitle(
                        renderTitleLine(titleMessages[0], placeholders),
                        null,
                        10,
                        70,
                        20,
                    )
                },
                0L,
                -1L,
            )
        } else if (titleMessages.size == 2) {
            SchedulerUtil.entityRun(
                plugin,
                player,
                {
                    player.sendTitle(
                        renderTitleLine(titleMessages[0], placeholders),
                        renderTitleLine(titleMessages[1], placeholders),
                        10,
                        70,
                        20,
                    )
                },
                0L,
                -1L,
            )
        }
    }

    companion object {
        private const val DEFAULT_LANGUAGE = "zh_CN"
        private const val FALLBACK_LANGUAGE = "en_US"
        private val BUNDLED_LANGUAGES = arrayOf(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE)
        private val MINIMESSAGE_PLACEHOLDER: Pattern = Pattern.compile("(?<!\\\\)<([a-z0-9_:-]+)>")
        private val MINIMESSAGE_PLACEHOLDER_NAME: Pattern = Pattern.compile("[a-z0-9_:-]+")
        private val MINIMESSAGE_TAGS: Set<String> = setOf(
            "black",
            "dark_blue",
            "dark_green",
            "dark_aqua",
            "dark_red",
            "dark_purple",
            "gold",
            "gray",
            "dark_gray",
            "blue",
            "green",
            "aqua",
            "red",
            "light_purple",
            "yellow",
            "white",
            "obfuscated",
            "bold",
            "strikethrough",
            "underlined",
            "italic",
            "reset",
        )
    }
}
