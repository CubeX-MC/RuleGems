package org.cubexmc.manager

import org.bukkit.entity.Player
import java.util.Locale

/** Uses the executable allowance source without consuming uses or querying economy accounts. */
internal class AllowedCommandSuggestions(private val allowanceManager: GemAllowanceManager) {
    /** null preserves native completions when RuleGems has no configured provider for this argument. */
    fun suggest(player: Player, label: String, args: Array<String>): List<String>? {
        val resolved = allowanceManager.resolveAllowedCommand(player.uniqueId, label.lowercase(Locale.ROOT))
        val constraints = resolved?.command?.argumentConstraints
        return when {
            constraints == null || args.isEmpty() -> null
            constraints.configurationError != null -> emptyList()
            else -> constraints.suggestionsFor(args.size)?.let { suggestions ->
                val prefix = args.last()
                if (suggestions.onlinePlayers) {
                    player.server.onlinePlayers.asSequence()
                        .filter { it.name.startsWith(prefix, ignoreCase = true) && player.canSee(it) }
                        .map { it.name }
                        .distinct()
                        .take(MAX_SUGGESTIONS)
                        .sortedWith(String.CASE_INSENSITIVE_ORDER)
                        .toList()
                } else {
                    suggestions.values.asSequence()
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .take(MAX_SUGGESTIONS)
                        .toList()
                }
            }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 50
    }
}
