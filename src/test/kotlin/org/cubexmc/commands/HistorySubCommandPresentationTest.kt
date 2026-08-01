package org.cubexmc.commands

import org.bukkit.command.CommandSender
import org.cubexmc.RuleGems
import org.cubexmc.commands.sub.HistorySubCommand
import org.cubexmc.manager.HistoryLogger
import org.cubexmc.manager.LanguageManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

class HistorySubCommandPresentationTest {
    @Test
    fun `history display adds summary and global record numbers`() {
        val plugin = mock(RuleGems::class.java)
        val languageManager = mock(LanguageManager::class.java)
        val sender = mock(CommandSender::class.java)
        val command = HistorySubCommand(plugin, languageManager)

        command.displayResult(
            sender,
            HistoryLogger.HistoryPage(listOf("sixth record", "seventh record"), 7),
            2,
            null,
        )

        @Suppress("UNCHECKED_CAST")
        val calls = mockingDetails(languageManager).invocations
            .filter { it.method.name == "sendMessage" && it.arguments.size == 3 }
            .map { it.arguments[1] as String to it.arguments[2] as Map<String, String> }
        val simpleCalls = mockingDetails(languageManager).invocations
            .filter { it.method.name == "sendMessage" && it.arguments.size == 2 }
            .map { it.arguments[1] as String }

        assertEquals(5, calls.size)
        assertTrue("command.history.footer" in simpleCalls)
        assertTrue(calls.any { it.first == "command.history.title_recent" })
        assertTrue(
            calls.any {
                it.first == "command.history.summary" &&
                    it.second["page"] == "2" &&
                    it.second["pages"] == "2" &&
                    it.second["total"] == "7"
            },
        )

        val entries = calls.filter { it.first == "command.history.entry" }.map { it.second }
        assertEquals(listOf("6", "7"), entries.map { it["index"] })
        assertEquals(listOf("sixth record", "seventh record"), entries.map { it["line"] })

        val navigation = calls.single { it.first == "command.history.console_navigation" }.second
        assertEquals("1", navigation["prev"])
        assertEquals("-", navigation["next"])
    }
}
