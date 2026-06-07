package org.cubexmc.view

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.manager.GemStateManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import java.util.Locale
import java.util.UUID

/**
 * Handles the formatting and sending of gem status information to players.
 * Separates view logic from the underlying state management.
 */
class GemStatusView(
    private val stateManager: GemStateManager,
    private val languageManager: LanguageManager,
) {
    fun sendStatus(sender: CommandSender, expectedCount: Int, placedCount: Int, heldCount: Int) {
        val summary = mutableMapOf<String, String>()
        summary["count"] = expectedCount.toString()
        summary["placed_count"] = placedCount.toString()
        summary["held_count"] = heldCount.toString()

        sender.sendMessage(languageManager.translateColorCodes(languageManager.formatMessage("gui.gem_status.total_expected", summary)))
        sender.sendMessage(languageManager.translateColorCodes(languageManager.formatMessage("gui.gem_status.total_counts", summary)))

        val entries = ArrayList(stateManager.allGemUuidsAndKeys)
        entries.sortWith { first, second ->
            val firstKey = first.value?.lowercase(Locale.getDefault()) ?: ""
            val secondKey = second.value?.lowercase(Locale.getDefault()) ?: ""
            val comparison = firstKey.compareTo(secondKey)
            if (comparison != 0) comparison else first.key.toString().compareTo(second.key.toString())
        }

        val isPlayerSender = sender is Player

        for ((gemId, gemKey) in entries) {
            val definition = if (gemKey != null) stateManager.findGemDefinition(gemKey) else null
            val displayName = definition?.displayName ?: "Gem"

            val holder = stateManager.getGemHolder(gemId)
            val location = stateManager.getGemLocation(gemId)
            val statusText = if (holder != null) {
                val placeholders = mutableMapOf<String, String>()
                placeholders["player"] = holder.name
                languageManager.formatMessage("gui.gem_status.status_held", placeholders)
            } else if (location != null) {
                val placeholders = mutableMapOf<String, String>()
                placeholders["x"] = location.blockX.toString()
                placeholders["y"] = location.blockY.toString()
                placeholders["z"] = location.blockZ.toString()
                placeholders["world"] = location.world?.name ?: "?"
                languageManager.formatMessage("gui.gem_status.status_placed", placeholders)
            } else {
                languageManager.getMessage("gui.gem_status.status_unknown")
            }

            val linePlaceholders = mutableMapOf<String, String>()
            linePlaceholders["gem_key"] = gemKey ?: "?"
            linePlaceholders["gem_name"] = displayName
            linePlaceholders["uuid"] = gemId.toString().substring(0, 8)
            linePlaceholders["status"] = statusText
            val plain = languageManager.formatMessage("gui.gem_status.gem_line", linePlaceholders)

            if (isPlayerSender) {
                sendClickableGemStatus(sender as Player, gemId, plain, definition)
            } else {
                sender.sendMessage(ChatColor.stripColor(languageManager.translateColorCodes(plain)) ?: "")
            }
        }
    }

    private fun sendClickableGemStatus(player: Player, gemId: UUID, plain: String, definition: GemDefinition?) {
        val component = TextComponent(*TextComponent.fromLegacyText(ColorUtils.translateColorCodes(plain)))

        val loreBuilder = StringBuilder()
        if (definition != null && !definition.lore.isNullOrEmpty()) {
            for (line in definition.lore) {
                loreBuilder.append(ColorUtils.translateColorCodes(line)).append("\n")
            }
        } else {
            val noMoreInfo = languageManager.getMessage("gui.no_more_info")
            loreBuilder.append(ChatColor.GRAY).append(noMoreInfo)
        }

        val text = Text(loreBuilder.toString().trim())
        component.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, text)

        val clickCommand = "/rulegems tp $gemId"
        component.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, clickCommand)

        player.spigot().sendMessage(component)
    }
}
