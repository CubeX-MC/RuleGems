package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.revoke.RevokeFeature;
import org.cubexmc.features.revoke.RevokeRule;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.provider.PermissionProvider;

public class RuleGemsDoctor {

    private final RuleGems plugin;

    public RuleGemsDoctor(RuleGems plugin) {
        this.plugin = plugin;
    }

    public void sendReport(CommandSender sender) {
        List<Entry> entries = inspect();
        sender.sendMessage(color(header()));
        if (entries.isEmpty()) {
            sender.sendMessage(color(okLine(localized("未发现明显配置问题。", "No obvious configuration issues were found."))));
        } else {
            for (Entry entry : entries) {
                sender.sendMessage(color(formatEntry(entry)));
            }
        }
        sender.sendMessage(color(footer(entries)));
    }

    public void logWarnings() {
        for (Entry entry : inspect()) {
            if (entry.severity == Severity.OK) {
                continue;
            }
            plugin.getLogger().warning(ChatColor.stripColor(color(formatEntry(entry))));
        }
    }

    private List<Entry> inspect() {
        List<Entry> entries = new ArrayList<>();
        GameplayConfig gameplayConfig = plugin.getGameplayConfig();
        ConfigManager configManager = plugin.getConfigManager();

        if (configManager == null || configManager.getConfig() == null) {
            entries.add(new Entry(Severity.ERROR, localized("主配置尚未加载。", "Main configuration is not loaded.")));
            return entries;
        }

        ConfigurationSection randomPlace = configManager.getConfig().getConfigurationSection("random_place_range");
        if (randomPlace == null) {
            entries.add(new Entry(Severity.ERROR,
                    localized("缺少 random_place_range，散落与补齐逻辑将不可用。", "Missing random_place_range; scatter and refill flows will fail.")));
        } else {
            String worldName = randomPlace.getString("world");
            if (worldName == null || worldName.isBlank()) {
                entries.add(new Entry(Severity.ERROR,
                        localized("random_place_range.world 未配置。", "random_place_range.world is not configured.")));
            } else {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    entries.add(new Entry(Severity.ERROR,
                            localized("随机放置世界不存在: ", "Random placement world not found: ") + worldName));
                }
            }
        }

        List<GemDefinition> gemDefinitions = plugin.getGemParser().getGemDefinitions();
        if (gemDefinitions == null || gemDefinitions.isEmpty()) {
            entries.add(new Entry(Severity.ERROR,
                    localized("当前没有加载任何宝石定义。", "No gem definitions are currently loaded.")));
        } else {
            entries.add(new Entry(Severity.OK,
                    localized("已加载宝石定义数量: ", "Loaded gem definitions: ") + gemDefinitions.size()));
            inspectGemInstanceCounts(entries, gemDefinitions);
        }

        inspectPermissionProvider(entries, gemDefinitions, gameplayConfig, configManager.getConfig());
        inspectStorageConfig(entries, configManager.getConfig());

        if (gameplayConfig != null && gameplayConfig.isPlaceRedeemEnabled() && gemDefinitions != null && !gemDefinitions.isEmpty()) {
            long missingAltars = gemDefinitions.stream().filter(def -> def.getAltarLocation() == null).count();
            if (missingAltars > 0) {
                entries.add(new Entry(Severity.WARNING,
                        localized("祭坛兑换已启用，但仍有宝石未设置祭坛数量: ", "Altar redeem is enabled, but gems are missing altar locations: ")
                                + missingAltars));
            }
        }

        AppointFeature appointFeature = plugin.getFeatureManager() != null ? plugin.getFeatureManager().getAppointFeature() : null;
        if (appointFeature != null && appointFeature.isEnabled()) {
            int appointCount = appointFeature.getAppointDefinitions().size();
            if (appointCount == 0) {
                entries.add(new Entry(Severity.WARNING,
                        localized("委任功能已启用，但没有任何职位定义。", "Appoint feature is enabled, but no roles are defined.")));
            } else {
                entries.add(new Entry(Severity.OK,
                        localized("可委任职位数量: ", "Available appoint roles: ") + appointCount));
            }
        }

        inspectRevokeFeature(entries, gemDefinitions);

        if (gameplayConfig != null && gameplayConfig.isOpEscalationAllowed()) {
            entries.add(new Entry(Severity.WARNING,
                    localized("allow_op_escalation 已开启，存在安全风险。", "allow_op_escalation is enabled and increases security risk.")));
        }

        return entries;
    }

    private void inspectGemInstanceCounts(List<Entry> entries, List<GemDefinition> gemDefinitions) {
        if (plugin.getGemManager() == null || gemDefinitions == null || gemDefinitions.isEmpty()) {
            return;
        }
        int expected = gemDefinitions.stream().mapToInt(def -> Math.max(0, def.getCount())).sum();
        int actual = plugin.getGemManager().getAllGemUuids().size();
        if (actual == expected) {
            entries.add(new Entry(Severity.OK,
                    localized("宝石实例数量匹配: ", "Configured gem instances match runtime state: ") + actual));
        } else {
            entries.add(new Entry(Severity.WARNING,
                    localized("宝石实例数量与配置不一致，配置/当前: ", "Configured gem instance count differs from runtime state, expected/current: ")
                            + expected + "/" + actual));
        }
    }

    private void inspectPermissionProvider(List<Entry> entries, List<GemDefinition> gemDefinitions,
            GameplayConfig gameplayConfig, ConfigurationSection config) {
        PermissionProvider provider = plugin.getPermissionProvider();
        String providerName = provider != null ? provider.getName() : localized("未初始化", "not initialized");
        if (provider == null) {
            entries.add(new Entry(Severity.WARNING,
                    localized("权限后端尚未初始化。", "Permission provider is not initialized.")));
            return;
        }
        entries.add(new Entry(Severity.OK,
                localized("当前权限后端: ", "Current permission provider: ") + providerName));

        int groupReferences = countGroupReferences(gemDefinitions, gameplayConfig, config);
        if (groupReferences > 0 && "bukkit".equalsIgnoreCase(providerName)) {
            entries.add(new Entry(Severity.WARNING,
                    localized("配置中使用了 permission_groups，但 Bukkit 后端没有持久权限组模型。建议安装 LuckPerms 或 Vault。引用数量: ",
                            "permission_groups are configured, but the Bukkit provider has no persistent group model. Install LuckPerms or Vault. References: ")
                            + groupReferences));
        }
    }

    private int countGroupReferences(List<GemDefinition> gemDefinitions, GameplayConfig gameplayConfig,
            ConfigurationSection config) {
        int count = 0;
        if (gemDefinitions != null) {
            for (GemDefinition def : gemDefinitions) {
                if (def != null && def.getVaultGroups() != null) {
                    count += def.getVaultGroups().size();
                }
            }
        }
        PowerStructure redeemAll = gameplayConfig != null ? gameplayConfig.getRedeemAllPowerStructure() : null;
        if (redeemAll != null && redeemAll.getVaultGroups() != null) {
            count += redeemAll.getVaultGroups().size();
        }
        ConfigurationSection thresholds = config != null ? config.getConfigurationSection("gem_collect_thresholds") : null;
        if (thresholds != null) {
            count += thresholds.getKeys(false).size();
        }
        return count;
    }

    private void inspectStorageConfig(List<Entry> entries, ConfigurationSection config) {
        String type = config != null ? config.getString("storage.type", "yaml") : "yaml";
        if (type == null || type.isBlank()) {
            entries.add(new Entry(Severity.WARNING,
                    localized("storage.type 为空，将回退到 YAML。", "storage.type is blank; YAML storage will be used.")));
            return;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        if (!"yaml".equals(normalized) && !"sqlite".equals(normalized)) {
            entries.add(new Entry(Severity.WARNING,
                    localized("未知 storage.type，将回退到 YAML: ", "Unknown storage.type; YAML storage will be used: ") + type));
        }
    }

    private void inspectRevokeFeature(List<Entry> entries, List<GemDefinition> gemDefinitions) {
        RevokeFeature revokeFeature = plugin.getFeatureManager() != null ? plugin.getFeatureManager().getRevokeFeature() : null;
        if (revokeFeature == null || !revokeFeature.isEnabled()) {
            return;
        }
        if (revokeFeature.getRules().isEmpty()) {
            entries.add(new Entry(Severity.WARNING,
                    localized("撤销宝石功能已启用，但没有可用规则。", "Revoke-power is enabled, but no usable rules are loaded.")));
            return;
        }
        Set<String> gemKeys = normalizedGemKeys(gemDefinitions);
        for (RevokeRule rule : revokeFeature.getRules().values()) {
            if (rule == null) {
                continue;
            }
            String trigger = normalize(rule.getTriggerGem());
            if (!trigger.isEmpty() && !gemKeys.contains(trigger)) {
                entries.add(new Entry(Severity.WARNING,
                        localized("撤销规则引用了不存在的 trigger_gem: ", "Revoke rule references missing trigger_gem: ")
                                + rule.getKey() + " -> " + rule.getTriggerGem()));
            }
            for (String target : rule.getTargetPowers()) {
                String normalizedTarget = normalize(target);
                if (!normalizedTarget.isEmpty() && !gemKeys.contains(normalizedTarget)) {
                    entries.add(new Entry(Severity.WARNING,
                            localized("撤销规则引用了不存在的目标权力: ", "Revoke rule references missing target power: ")
                                    + rule.getKey() + " -> " + target));
                }
            }
        }
    }

    private Set<String> normalizedGemKeys(List<GemDefinition> gemDefinitions) {
        Set<String> keys = new HashSet<>();
        if (gemDefinitions == null) {
            return keys;
        }
        for (GemDefinition def : gemDefinitions) {
            if (def != null) {
                String normalized = normalize(def.getGemKey());
                if (!normalized.isEmpty()) {
                    keys.add(normalized);
                }
            }
        }
        return keys;
    }

    private String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private String formatEntry(Entry entry) {
        return switch (entry.severity) {
            case OK -> "&a[OK] &f" + entry.message;
            case WARNING -> "&e[WARN] &f" + entry.message;
            case ERROR -> "&c[ERROR] &f" + entry.message;
        };
    }

    private String header() {
        return localized("&6===== RuleGems 诊断报告 =====", "&6===== RuleGems Doctor Report =====");
    }

    private String footer(List<Entry> entries) {
        long warnings = entries.stream().filter(entry -> entry.severity == Severity.WARNING).count();
        long errors = entries.stream().filter(entry -> entry.severity == Severity.ERROR).count();
        return localized("&7警告: &e", "&7Warnings: &e") + warnings
                + localized(" &7错误: &c", " &7Errors: &c") + errors;
    }

    private String okLine(String message) {
        return "&a" + message;
    }

    private String localized(String zh, String en) {
        return plugin.getLanguageManager() != null
                && plugin.getLanguageManager().getLanguage() != null
                && plugin.getLanguageManager().getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh")
                        ? zh
                        : en;
    }

    private String color(String input) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(input);
    }

    private record Entry(Severity severity, String message) {
    }

    private enum Severity {
        OK,
        WARNING,
        ERROR
    }
}
