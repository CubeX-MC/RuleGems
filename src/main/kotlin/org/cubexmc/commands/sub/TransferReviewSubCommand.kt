package org.cubexmc.commands.sub

import org.bukkit.command.CommandSender
import org.cubexmc.RuleGems
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.LanguageManager
import org.cubexmc.manager.attemptTransferOperation
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

class TransferReviewSubCommand(
    private val plugin: RuleGems,
    private val language: LanguageManager,
) : SubCommand {
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "list"
        val permission = if (action == "resolve") RESOLVE else REVIEW
        if (!sender.hasPermission(permission)) {
            language.sendMessage(sender, "command.no_permission")
            return true
        }
        attemptTransferOperation {
            when (action) {
                "list" -> list(sender, args)
                "resolve" -> resolve(sender, args)
                else -> usage(sender)
            }
        }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "Transfer reconciliation command failed; guard retained.", failure)
            language.sendMessage(sender, "command.transfer_review.storage_failed")
        }
        return true
    }

    fun suggest(sender: CommandSender, args: Array<String>): List<String> {
        val prefix = args.lastOrNull().orEmpty()
        val candidates = when {
            args.size <= 1 -> buildList {
                if (sender.hasPermission(REVIEW)) add("list")
                if (sender.hasPermission(RESOLVE)) add("resolve")
            }
            args.size == 2 && args[0] == "resolve" && sender.hasPermission(RESOLVE) ->
                plugin.transferOperations.all().map { it.id.toString() }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(prefix, ignoreCase = true) }.take(PAGE_SIZE)
    }

    private fun list(sender: CommandSender, args: Array<String>) {
        val page = if (args.size <= 1) 1 else args[1].toIntOrNull()
        if (page == null || page < 1 || args.size > 2) return usage(sender)
        val entries = plugin.transferOperations.all()
        val pages = ((entries.size - 1) / PAGE_SIZE + 1).coerceAtLeast(1)
        when {
            entries.isEmpty() -> language.sendMessage(sender, "command.transfer_review.empty")
            page > pages -> usage(sender)
            else -> showPage(sender, entries, page, pages)
        }
    }

    private fun showPage(
        sender: CommandSender,
        entries: List<org.cubexmc.storage.TransferOperationStore.Operation>,
        page: Int,
        pages: Int,
    ) {
        language.sendMessage(sender, "command.transfer_review.header", mapOf("page" to "$page", "pages" to "$pages"))
        for (operation in entries.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)) {
            language.sendMessage(sender, "command.transfer_review.line", mapOf(
                "operation" to operation.id.toString(), "player" to operation.playerId.toString(),
                "command" to operation.label, "status" to operation.status,
                "details" to operation.commands.joinToString(" | "),
            ))
        }
    }

    private fun resolve(sender: CommandSender, args: Array<String>) {
        val id = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val note = args.drop(2).joinToString(" ").trim()
        if (id == null || note.isBlank() || note.length > MAX_NOTE_LENGTH) return usage(sender)
        if (plugin.transferOperations.all().none { it.id == id }) {
            language.sendMessage(sender, "command.transfer_review.not_found")
            return
        }
        check(plugin.gemManager.saveGemsSync()) { "Cannot persist allowances before reconciliation" }
        val resolved = plugin.transferOperations.resolve(id, sender.name, note)
        language.sendMessage(sender,
            if (resolved) "command.transfer_review.resolved" else "command.transfer_review.not_found",
            mapOf("operation" to id.toString()))
    }

    private fun usage(sender: CommandSender) = language.sendMessage(sender, "command.transfer_review.usage")

    companion object {
        const val REVIEW = "rulegems.transfer.review"
        const val RESOLVE = "rulegems.transfer.resolve"
        private const val PAGE_SIZE = 10
        private const val MAX_NOTE_LENGTH = 256
    }
}
