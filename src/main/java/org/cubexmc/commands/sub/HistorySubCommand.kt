package org.cubexmc.commands.sub

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.HistoryLogger
import org.cubexmc.manager.LanguageManager
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import kotlin.math.ceil
import kotlin.math.max

/**
 * /rulegems history [page] [player]
 */
class HistorySubCommand(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.admin"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val historyLogger = plugin.historyLogger
        if (historyLogger == null) {
            languageManager.sendMessage(sender, "command.history.disabled")
            return true
        }

        var page = 1
        var playerFilter: String? = null

        if (args.isNotEmpty()) {
            if (isInteger(args[0])) {
                page = max(1, args[0].toInt())
            } else {
                playerFilter = args[0]
            }
        }
        if (args.size > 1) {
            if (playerFilter == null && !isInteger(args[1])) {
                playerFilter = args[1]
            } else if (isInteger(args[1])) {
                page = max(1, args[1].toInt())
            }
        }

        val finalPage = page
        val finalPlayerFilter = playerFilter
        SchedulerUtil.asyncRun(
            plugin,
            {
                val historyPage = if (finalPlayerFilter != null) {
                    historyLogger.getPlayerHistoryPage(finalPlayerFilter, finalPage, PAGE_SIZE)
                } else {
                    historyLogger.getRecentHistoryPage(finalPage, PAGE_SIZE)
                }
                SchedulerUtil.globalRun(plugin, { displayResult(sender, historyPage, finalPage, finalPlayerFilter) }, 0, -1)
            },
            0,
        )

        return true
    }

    private fun displayResult(
        sender: CommandSender,
        historyPage: HistoryLogger.HistoryPage,
        page: Int,
        playerFilter: String?,
    ) {
        val totalPages: Int
        if (playerFilter != null) {
            if (historyPage.totalCount == 0) {
                val placeholders = HashMap<String, String>()
                placeholders["player"] = playerFilter
                languageManager.sendMessage(sender, "command.history.no_player_records", placeholders)
                return
            }
            if (historyPage.entries.isEmpty()) {
                val placeholders = HashMap<String, String>()
                placeholders["page"] = page.toString()
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders)
                return
            }
            val placeholders = HashMap<String, String>()
            placeholders["player"] = playerFilter
            placeholders["count"] = historyPage.entries.size.toString()
            placeholders["total"] = historyPage.totalCount.toString()
            totalPages = max(1, ceil(historyPage.totalCount / PAGE_SIZE.toDouble()).toInt())
            placeholders["page"] = page.toString()
            placeholders["pages"] = totalPages.toString()
            languageManager.sendMessage(sender, "command.history.player_header", placeholders)
        } else {
            if (historyPage.totalCount == 0) {
                languageManager.sendMessage(sender, "command.history.no_records")
                return
            }
            if (historyPage.entries.isEmpty()) {
                val placeholders = HashMap<String, String>()
                placeholders["page"] = page.toString()
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders)
                return
            }
            val placeholders = HashMap<String, String>()
            placeholders["count"] = historyPage.entries.size.toString()
            placeholders["total"] = historyPage.totalCount.toString()
            totalPages = max(1, ceil(historyPage.totalCount / PAGE_SIZE.toDouble()).toInt())
            placeholders["page"] = page.toString()
            placeholders["pages"] = totalPages.toString()
            languageManager.sendMessage(sender, "command.history.recent_header", placeholders)
        }

        for (line in historyPage.entries) {
            val placeholders = HashMap<String, String>()
            placeholders["line"] = line
            languageManager.sendMessage(sender, "command.history.line", placeholders)
        }

        if (totalPages > 1) {
            sendNavigation(sender, page, totalPages, playerFilter)
        }
    }

    private fun sendNavigation(sender: CommandSender, currentPage: Int, totalPages: Int, playerFilter: String?) {
        val prevPage = if (currentPage > 1) currentPage - 1 else -1
        val nextPage = if (currentPage < totalPages) currentPage + 1 else -1

        if (sender is Player) {
            val components = ArrayList<BaseComponent>()

            val basePlaceholders = HashMap<String, String>()
            basePlaceholders["page"] = currentPage.toString()
            basePlaceholders["pages"] = totalPages.toString()

            val divider = safeFormat("command.history.page_nav_divider", basePlaceholders)

            if (prevPage > 0) {
                val prevPlaceholders = HashMap(basePlaceholders)
                prevPlaceholders["target"] = prevPage.toString()
                prevPlaceholders["page"] = prevPage.toString()
                val prevLabel = safeFormat("command.history.page_nav_previous", prevPlaceholders)
                val prevHover = safeFormat("command.history.page_nav_hover", prevPlaceholders)
                appendInteractiveComponent(components, prevLabel, prevHover, buildCommand(prevPage, playerFilter))
            } else {
                val prevDisabled = safeFormat("command.history.page_nav_previous_disabled", basePlaceholders)
                appendStaticComponent(components, prevDisabled)
            }

            if (nextPage > 0) {
                val nextPlaceholders = HashMap(basePlaceholders)
                nextPlaceholders["target"] = nextPage.toString()
                nextPlaceholders["page"] = nextPage.toString()
                val nextLabel = safeFormat("command.history.page_nav_next", nextPlaceholders)
                val nextHover = safeFormat("command.history.page_nav_hover", nextPlaceholders)
                if (components.isNotEmpty() && divider.isNotEmpty()) {
                    appendStaticComponent(components, divider)
                }
                appendInteractiveComponent(components, nextLabel, nextHover, buildCommand(nextPage, playerFilter))
            } else {
                val nextDisabled = safeFormat("command.history.page_nav_next_disabled", basePlaceholders)
                if (nextDisabled.isNotEmpty()) {
                    if (components.isNotEmpty() && divider.isNotEmpty()) {
                        appendStaticComponent(components, divider)
                    }
                    appendStaticComponent(components, nextDisabled)
                }
            }

            if (components.isNotEmpty()) {
                sender.spigot().sendMessage(*components.toTypedArray())
            }
        } else {
            val placeholders = HashMap<String, String>()
            placeholders["page"] = currentPage.toString()
            placeholders["pages"] = totalPages.toString()
            placeholders["prev"] = if (prevPage > 0) prevPage.toString() else "-"
            placeholders["next"] = if (nextPage > 0) nextPage.toString() else "-"
            languageManager.sendMessage(sender, "command.history.pagination_hint", placeholders)
        }
    }

    private fun appendInteractiveComponent(components: MutableList<BaseComponent>, text: String?, hover: String?, command: String) {
        if (text.isNullOrEmpty()) {
            return
        }
        val parts = TextComponent.fromLegacyText(ColorUtils.translateColorCodes(text) ?: "")
        val clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
        val hoverEvent = if (!hover.isNullOrEmpty()) {
            HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(ColorUtils.translateColorCodes(hover) ?: ""))
        } else {
            null
        }
        for (part in parts) {
            part.clickEvent = clickEvent
            if (hoverEvent != null) {
                part.hoverEvent = hoverEvent
            }
            components.add(part)
        }
    }

    private fun appendStaticComponent(components: MutableList<BaseComponent>, text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }
        for (part in TextComponent.fromLegacyText(ColorUtils.translateColorCodes(text) ?: "")) {
            components.add(part)
        }
    }

    private fun buildCommand(page: Int, playerFilter: String?): String {
        val builder = StringBuilder("/rulegems history ").append(page)
        if (!playerFilter.isNullOrEmpty()) {
            builder.append(' ').append(playerFilter)
        }
        return builder.toString()
    }

    private fun safeFormat(path: String, placeholders: Map<String, String>?): String {
        val value = languageManager.formatMessage("messages.$path", placeholders ?: HashMap())
        if (value == null || value.startsWith("Missing message")) {
            return ""
        }
        return value
    }

    private fun isInteger(value: String?): Boolean {
        if (value.isNullOrEmpty()) {
            return false
        }
        return try {
            value.toInt()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    companion object {
        private const val PAGE_SIZE = 5
    }
}
