package org.cubexmc.commands.registrar

import org.bukkit.Bukkit
import org.cubexmc.RuleGems
import org.cubexmc.commands.RuleGemsCommandActor
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider
import java.util.Locale

class SuggestionProviders(private val plugin: RuleGems) {
    fun permSetSuggestions(): SuggestionProvider<RuleGemsCommandActor> =
        SuggestionProvider.blocking { ctx, _ ->
            val feature = plugin.featureManager?.appointFeature
            if (feature == null) {
                return@blocking emptyList()
            }
            val sender = ctx.sender().sender()
            val suggestions = ArrayList<Suggestion>()
            for (key in feature.getAppointDefinitions().keys) {
                if (sender.hasPermission("rulegems.appoint." + key.lowercase(Locale.ROOT)) ||
                    sender.hasPermission("rulegems.appoint.$key") ||
                    sender.hasPermission("rulegems.admin")
                ) {
                    suggestions.add(Suggestion.suggestion(key))
                }
            }
            suggestions
        }

    fun allPermSetSuggestions(): SuggestionProvider<RuleGemsCommandActor> =
        SuggestionProvider.blocking { _, _ ->
            val feature = plugin.featureManager?.appointFeature
            if (feature == null) {
                return@blocking emptyList()
            }
            feature.getAppointDefinitions().keys.map { key -> Suggestion.suggestion(key) }
        }

    fun gemKeySuggestions(): SuggestionProvider<RuleGemsCommandActor> =
        SuggestionProvider.blocking { _, _ ->
            val keys = LinkedHashSet<String>()
            for (definition in plugin.gemParser.gemDefinitions) {
                val gemKey = definition?.gemKey
                if (gemKey.isNullOrBlank()) {
                    continue
                }
                keys.add(gemKey)
            }
            keys.sortedWith(String.CASE_INSENSITIVE_ORDER).map { key -> Suggestion.suggestion(key) }
        }

    fun onlinePlayerSuggestions(): SuggestionProvider<RuleGemsCommandActor> =
        SuggestionProvider.blocking { _, _ ->
            Bukkit.getOnlinePlayers().map { player -> Suggestion.suggestion(player.name) }
        }
}
