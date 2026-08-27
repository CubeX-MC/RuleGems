package org.cubexmc.manager

import org.bukkit.entity.Player
import org.cubexmc.model.AllowedCommand
import java.util.regex.Matcher
import java.util.regex.Pattern

internal object AllowedCommandRenderer {
    data class Entry(val executor: String, val command: String)
    private val defaults = Pattern.compile("%arg(\\d+)\\|([^%]+)%")

    fun render(player: Player, allowed: AllowedCommand, args: Array<String>): List<Entry> {
        val placeholders = args.mapIndexed { i, value -> "%arg${i + 1}%" to value }.toMap() +
            ("%player%" to player.name)
        return allowed.getCommands().filter { it.isNotBlank() }.map { line ->
            val parsed = AllowedCommand.parseExecutor(line)
            Entry(parsed[0], replace(parsed[1], placeholders, args).removePrefix("/"))
        }
    }

    private fun replace(text: String, placeholders: Map<String, String>, args: Array<String>): String {
        var value = text
        for ((key, replacement) in placeholders) value = value.replace(key, replacement)
        val matcher = defaults.matcher(value)
        val output = StringBuffer()
        while (matcher.find()) {
            val index = matcher.group(1).toInt() - 1
            val replacement = args.getOrNull(index) ?: matcher.group(2)
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(output)
        return output.toString()
    }
}
