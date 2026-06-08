package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.cubexmc.RuleGems
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.features.revoke.RevokeRule
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PowerStructure
import org.cubexmc.provider.PermissionProvider
import org.cubexmc.utils.ColorUtils
import java.util.Locale

class RuleGemsDoctor(private val plugin: RuleGems) {
    fun sendReport(sender: CommandSender) {
        val entries = inspect()
        sender.sendMessage(color(header()))
        if (entries.isEmpty()) {
            sender.sendMessage(color(okLine(localized("未发现明显配置问题。", "No obvious configuration issues were found."))))
        } else {
            for (entry in entries) {
                sender.sendMessage(color(formatEntry(entry)))
            }
        }
        sender.sendMessage(color(footer(entries)))
    }

    fun logWarnings() {
        for (entry in inspect()) {
            if (entry.severity == Severity.OK) {
                continue
            }
            plugin.logger.warning(ChatColor.stripColor(color(formatEntry(entry))))
        }
    }

    private fun inspect(): List<Entry> {
        val entries = ArrayList<Entry>()
        val gameplayConfig = plugin.gameplayConfig
        val configManager = plugin.configManager

        if (configManager == null || configManager.config == null) {
            entries.add(Entry(Severity.ERROR, localized("主配置尚未加载。", "Main configuration is not loaded.")))
            return entries
        }

        val randomPlace = configManager.config?.getConfigurationSection("random_place_range")
        if (randomPlace == null) {
            entries.add(
                Entry(
                    Severity.ERROR,
                    localized("缺少 random_place_range，散落与补齐逻辑将不可用。", "Missing random_place_range; scatter and refill flows will fail."),
                ),
            )
        } else {
            val worldName = randomPlace.getString("world")
            if (worldName.isNullOrBlank()) {
                entries.add(Entry(Severity.ERROR, localized("random_place_range.world 未配置。", "random_place_range.world is not configured.")))
            } else {
                val world = Bukkit.getWorld(worldName)
                if (world == null) {
                    entries.add(Entry(Severity.ERROR, localized("随机放置世界不存在: ", "Random placement world not found: ") + worldName))
                }
            }
        }

        val gemDefinitions = plugin.gemParser.gemDefinitions
        if (gemDefinitions == null || gemDefinitions.isEmpty()) {
            entries.add(Entry(Severity.ERROR, localized("当前没有加载任何宝石定义。", "No gem definitions are currently loaded.")))
        } else {
            entries.add(Entry(Severity.OK, localized("已加载宝石定义数量: ", "Loaded gem definitions: ") + gemDefinitions.size))
            inspectGemInstanceCounts(entries, gemDefinitions)
        }

        inspectPermissionProvider(entries, gemDefinitions, gameplayConfig, configManager.config)
        inspectStorageConfig(entries, configManager.config)

        if (gameplayConfig != null && gameplayConfig.isPlaceRedeemEnabled && gemDefinitions != null && gemDefinitions.isNotEmpty()) {
            val missingAltars = gemDefinitions.count { definition -> definition.altarLocation == null }.toLong()
            if (missingAltars > 0) {
                entries.add(
                    Entry(
                        Severity.WARNING,
                        localized("祭坛兑换已启用，但仍有宝石未设置祭坛数量: ", "Altar redeem is enabled, but gems are missing altar locations: ") +
                            missingAltars,
                    ),
                )
            }
        }

        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature != null && appointFeature.isEnabled) {
            val appointCount = appointFeature.getAppointDefinitions().size
            if (appointCount == 0) {
                entries.add(Entry(Severity.WARNING, localized("委任功能已启用，但没有任何职位定义。", "Appoint feature is enabled, but no roles are defined.")))
            } else {
                entries.add(Entry(Severity.OK, localized("可委任职位数量: ", "Available appoint roles: ") + appointCount))
            }
        }

        inspectRevokeFeature(entries, gemDefinitions)

        if (gameplayConfig != null && gameplayConfig.isOpEscalationAllowed) {
            entries.add(Entry(Severity.WARNING, localized("allow_op_escalation 已开启，存在安全风险。", "allow_op_escalation is enabled and increases security risk.")))
        }

        return entries
    }

    private fun inspectGemInstanceCounts(entries: MutableList<Entry>, gemDefinitions: List<GemDefinition>?) {
        if (plugin.gemManager == null || gemDefinitions.isNullOrEmpty()) {
            return
        }
        val expected = gemDefinitions.sumOf { definition -> kotlin.math.max(0, definition.count) }
        val actual = plugin.gemManager.allGemUuids.size
        if (actual == expected) {
            entries.add(Entry(Severity.OK, localized("宝石实例数量匹配: ", "Configured gem instances match runtime state: ") + actual))
        } else {
            entries.add(
                Entry(
                    Severity.WARNING,
                    localized("宝石实例数量与配置不一致，配置/当前: ", "Configured gem instance count differs from runtime state, expected/current: ") +
                        expected + "/" + actual,
                ),
            )
        }
    }

    private fun inspectPermissionProvider(
        entries: MutableList<Entry>,
        gemDefinitions: List<GemDefinition>?,
        gameplayConfig: GameplayConfig?,
        config: ConfigurationSection?,
    ) {
        val provider: PermissionProvider? = plugin.permissionProvider
        val providerName = provider?.getName() ?: localized("未初始化", "not initialized")
        if (provider == null) {
            entries.add(Entry(Severity.WARNING, localized("权限后端尚未初始化。", "Permission provider is not initialized.")))
            return
        }
        entries.add(Entry(Severity.OK, localized("当前权限后端: ", "Current permission provider: ") + providerName))

        val groupReferences = countGroupReferences(gemDefinitions, gameplayConfig, config)
        if (groupReferences > 0 && "bukkit".equals(providerName, ignoreCase = true)) {
            entries.add(
                Entry(
                    Severity.WARNING,
                    localized(
                        "配置中使用了 permission_groups，但 Bukkit 后端没有持久权限组模型。建议安装 LuckPerms 或 Vault。引用数量: ",
                        "permission_groups are configured, but the Bukkit provider has no persistent group model. Install LuckPerms or Vault. References: ",
                    ) + groupReferences,
                ),
            )
        }
    }

    private fun countGroupReferences(
        gemDefinitions: List<GemDefinition>?,
        gameplayConfig: GameplayConfig?,
        config: ConfigurationSection?,
    ): Int {
        var count = 0
        if (gemDefinitions != null) {
            for (definition in gemDefinitions) {
                count += definition.vaultGroups.size
            }
        }
        val redeemAll: PowerStructure? = gameplayConfig?.redeemAllPowerStructure
        if (redeemAll != null) {
            count += redeemAll.vaultGroups.size
        }
        val thresholds = config?.getConfigurationSection("gem_collect_thresholds")
        if (thresholds != null) {
            count += thresholds.getKeys(false).size
        }
        return count
    }

    private fun inspectStorageConfig(entries: MutableList<Entry>, config: ConfigurationSection?) {
        val type = config?.getString("storage.type", "yaml") ?: "yaml"
        if (type.isBlank()) {
            entries.add(Entry(Severity.WARNING, localized("storage.type 为空，将回退到 YAML。", "storage.type is blank; YAML storage will be used.")))
            return
        }
        val normalized = type.lowercase(Locale.ROOT)
        if (normalized != "yaml" && normalized != "sqlite") {
            entries.add(Entry(Severity.WARNING, localized("未知 storage.type，将回退到 YAML: ", "Unknown storage.type; YAML storage will be used: ") + type))
        }
    }

    private fun inspectRevokeFeature(entries: MutableList<Entry>, gemDefinitions: List<GemDefinition>?) {
        val revokeFeature = plugin.featureManager?.revokeFeature
        if (revokeFeature == null || !revokeFeature.isEnabled) {
            return
        }
        if (revokeFeature.rules.isEmpty()) {
            entries.add(Entry(Severity.WARNING, localized("撤销宝石功能已启用，但没有可用规则。", "Revoke-power is enabled, but no usable rules are loaded.")))
            return
        }
        val gemKeys = normalizedGemKeys(gemDefinitions)
        for (rule: RevokeRule? in revokeFeature.rules.values) {
            if (rule == null) {
                continue
            }
            val trigger = normalize(rule.triggerGem)
            if (trigger.isNotEmpty() && !gemKeys.contains(trigger)) {
                entries.add(
                    Entry(
                        Severity.WARNING,
                        localized("撤销规则引用了不存在的 trigger_gem: ", "Revoke rule references missing trigger_gem: ") +
                            rule.key + " -> " + rule.triggerGem,
                    ),
                )
            }
            for (target in rule.targetPowers) {
                val normalizedTarget = normalize(target)
                if (normalizedTarget.isNotEmpty() && !gemKeys.contains(normalizedTarget)) {
                    entries.add(
                        Entry(
                            Severity.WARNING,
                            localized("撤销规则引用了不存在的目标权力: ", "Revoke rule references missing target power: ") +
                                rule.key + " -> " + target,
                        ),
                    )
                }
            }
        }
    }

    private fun normalizedGemKeys(gemDefinitions: List<GemDefinition>?): Set<String> {
        val keys = HashSet<String>()
        if (gemDefinitions == null) {
            return keys
        }
        for (definition in gemDefinitions) {
            val normalized = normalize(definition.gemKey)
            if (normalized.isNotEmpty()) {
                keys.add(normalized)
            }
        }
        return keys
    }

    private fun normalize(key: String?): String = key?.trim()?.lowercase(Locale.ROOT) ?: ""

    private fun formatEntry(entry: Entry): String =
        when (entry.severity) {
            Severity.OK -> "&a[OK] &f" + entry.message
            Severity.WARNING -> "&e[WARN] &f" + entry.message
            Severity.ERROR -> "&c[ERROR] &f" + entry.message
        }

    private fun header(): String = localized("&6===== RuleGems 诊断报告 =====", "&6===== RuleGems Doctor Report =====")

    private fun footer(entries: List<Entry>): String {
        val warnings = entries.count { entry -> entry.severity == Severity.WARNING }.toLong()
        val errors = entries.count { entry -> entry.severity == Severity.ERROR }.toLong()
        return localized("&7警告: &e", "&7Warnings: &e") + warnings +
            localized(" &7错误: &c", " &7Errors: &c") + errors
    }

    private fun okLine(message: String): String = "&a$message"

    private fun localized(zh: String, en: String): String {
        val activeLanguage = plugin.languageManager?.language
        return if (activeLanguage != null && activeLanguage.lowercase(Locale.ROOT).startsWith("zh")) {
            zh
        } else {
            en
        }
    }

    private fun color(input: String): String = ColorUtils.translateColorCodes(input) ?: ""

    private data class Entry(val severity: Severity, val message: String)

    private enum class Severity {
        OK,
        WARNING,
        ERROR,
    }
}
