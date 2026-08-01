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

    internal fun displayResult(
        sender: CommandSender,
        historyPage: HistoryLogger.HistoryPage,
        page: Int,
        playerFilter: String?,
    ) {
        if (historyPage.totalCount == 0) {
            if (playerFilter == null) {
                languageManager.sendMessage(sender, "command.history.no_records")
            } else {
                languageManager.sendMessage(
                    sender,
                    "command.history.no_player_records",
                    mapOf("player" to playerFilter),
                )
            }
            return
        }
        if (historyPage.entries.isEmpty()) {
            languageManager.sendMessage(
                sender,
                "command.history.page_out_of_range",
                mapOf("page" to page.toString()),
            )
            return
        }

        val totalPages = max(1, ceil(historyPage.totalCount / PAGE_SIZE.toDouble()).toInt())
        val summary = HashMap<String, String>()
        summary["count"] = historyPage.entries.size.toString()
        summary["total"] = historyPage.totalCount.toString()
        summary["page"] = page.toString()
        summary["pages"] = totalPages.toString()
        if (playerFilter != null) {
            summary["player"] = playerFilter
        }
        languageManager.sendMessage(
            sender,
            if (playerFilter == null) "command.history.title_recent" else "command.history.title_player",
            summary,
        )
        languageManager.sendMessage(sender, "command.history.summary", summary)

        historyPage.entries.forEachIndexed { offset, line ->
            val recordIndex = (page - 1).toLong() * PAGE_SIZE + offset + 1
            languageManager.sendMessage(
                sender,
                "command.history.entry",
                mapOf("index" to recordIndex.toString(), "line" to line),
            )
        }

        languageManager.sendMessage(sender, "command.history.footer")
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

            if (prevPage > 0) {
                val prevPlaceholders = HashMap(basePlaceholders)
                prevPlaceholders["page"] = prevPage.toString()
                val prevLabel = safeFormat("command.history.navigation_previous", prevPlaceholders)
                val prevHover = safeFormat("command.history.navigation_hover", prevPlaceholders)
                appendInteractiveComponent(components, prevLabel, prevHover, buildCommand(prevPage, playerFilter))
            } else {
                val prevDisabled = safeFormat("command.history.navigation_previous_disabled", basePlaceholders)
                appendStaticComponent(components, prevDisabled)
            }

            appendStaticComponent(components, safeFormat("command.history.navigation_page", basePlaceholders))

            if (nextPage > 0) {
                val nextPlaceholders = HashMap(basePlaceholders)
                nextPlaceholders["page"] = nextPage.toString()
                val nextLabel = safeFormat("command.history.navigation_next", nextPlaceholders)
                val nextHover = safeFormat("command.history.navigation_hover", nextPlaceholders)
                appendInteractiveComponent(components, nextLabel, nextHover, buildCommand(nextPage, playerFilter))
            } else {
                val nextDisabled = safeFormat("command.history.navigation_next_disabled", basePlaceholders)
                appendStaticComponent(components, nextDisabled)
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
            languageManager.sendMessage(sender, "command.history.console_navigation", placeholders)
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
