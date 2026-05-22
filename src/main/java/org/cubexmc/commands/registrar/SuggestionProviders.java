package org.cubexmc.commands.registrar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.model.GemDefinition;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

public final class SuggestionProviders {

    private final RuleGems plugin;

    public SuggestionProviders(RuleGems plugin) {
        this.plugin = plugin;
    }

    public SuggestionProvider<RuleGemsCommandActor> permSetSuggestions() {
        return SuggestionProvider.blocking((ctx, input) -> {
            AppointFeature feature = plugin.getFeatureManager() != null
                    ? plugin.getFeatureManager().getAppointFeature()
                    : null;
            if (feature == null) {
                return java.util.Collections.emptyList();
            }
            CommandSender sender = ctx.sender().sender();
            List<Suggestion> suggestions = new ArrayList<>();
            for (String key : feature.getAppointDefinitions().keySet()) {
                if (sender.hasPermission("rulegems.appoint." + key.toLowerCase(java.util.Locale.ROOT))
                        || sender.hasPermission("rulegems.appoint." + key)
                        || sender.hasPermission("rulegems.admin")) {
                    suggestions.add(Suggestion.suggestion(key));
                }
            }
            return suggestions;
        });
    }

    public SuggestionProvider<RuleGemsCommandActor> allPermSetSuggestions() {
        return SuggestionProvider.blocking((ctx, input) -> {
            AppointFeature feature = plugin.getFeatureManager() != null
                    ? plugin.getFeatureManager().getAppointFeature()
                    : null;
            if (feature == null) {
                return java.util.Collections.emptyList();
            }
            return feature.getAppointDefinitions().keySet().stream()
                    .map(Suggestion::suggestion)
                    .collect(Collectors.toList());
        });
    }

    public SuggestionProvider<RuleGemsCommandActor> gemKeySuggestions() {
        return SuggestionProvider.blocking((ctx, input) -> {
            Set<String> keys = new LinkedHashSet<>();
            for (GemDefinition definition : plugin.getGemParser().getGemDefinitions()) {
                if (definition == null || definition.getGemKey() == null || definition.getGemKey().isBlank()) {
                    continue;
                }
                keys.add(definition.getGemKey());
            }
            return keys.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(Suggestion::suggestion)
                    .collect(Collectors.toList());
        });
    }

    public SuggestionProvider<RuleGemsCommandActor> onlinePlayerSuggestions() {
        return SuggestionProvider.blocking((ctx, input) -> Bukkit.getOnlinePlayers().stream()
                .map(p -> Suggestion.suggestion(p.getName()))
                .collect(Collectors.toList()));
    }
}
