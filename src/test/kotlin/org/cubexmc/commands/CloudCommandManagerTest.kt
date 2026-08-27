package org.cubexmc.commands

import org.bukkit.command.Command
import org.bukkit.command.TabCompleter
import org.bukkit.command.CommandExecutor
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.rulegems.gui.GUIManager
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.cubexmc.commands.sub.TransferReviewSubCommand
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.logging.Logger

class CloudCommandManagerTest {
    @Test
    fun `bare rg through plugin yml bridge opens the player main menu`() {
        val plugin = mock(RuleGems::class.java)
        val gemManager = mock(GemManager::class.java)
        val gameplayConfig = mock(GameplayConfig::class.java)
        val languageManager = mock(LanguageManager::class.java)
        val guiManager = mock(GUIManager::class.java)
        val pluginCommand = mock(PluginCommand::class.java)
        val player = mock(Player::class.java)
        val command = mock(Command::class.java)

        `when`(plugin.getCommand("rulegems")).thenReturn(pluginCommand)
        `when`(plugin.logger).thenReturn(Logger.getLogger("CloudCommandManagerTest"))
        `when`(player.hasPermission("rulegems.admin")).thenReturn(true)

        val manager = CloudCommandManager(plugin, gemManager, gameplayConfig, languageManager, guiManager)
        manager.installBukkitCompatibilityBridge()

        val executor = ArgumentCaptor.forClass(CommandExecutor::class.java)
        verify(pluginCommand).setExecutor(executor.capture())

        assertTrue(executor.value.onCommand(player, command, "rg", emptyArray()))
        verify(guiManager).openMainMenu(player, true)
    }
    @Test
    fun `fallback completion respects transfer review and resolve permissions`() {
        val plugin = mock(RuleGems::class.java)
        val pluginCommand = mock(PluginCommand::class.java)
        val player = mock(Player::class.java)
        val command = mock(Command::class.java)
        `when`(plugin.getCommand("rulegems")).thenReturn(pluginCommand)
        `when`(plugin.logger).thenReturn(Logger.getLogger("TransferCompletionTest"))
        val manager = CloudCommandManager(
            plugin, mock(GemManager::class.java), mock(GameplayConfig::class.java),
            mock(LanguageManager::class.java), mock(GUIManager::class.java),
        )
        manager.installBukkitCompatibilityBridge()
        val completer = ArgumentCaptor.forClass(TabCompleter::class.java)
        verify(pluginCommand).setTabCompleter(completer.capture())
        assertFalse(completer.value.onTabComplete(player, command, "rg", emptyArray())!!.contains("transfer-review"))
        `when`(player.hasPermission(TransferReviewSubCommand.REVIEW)).thenReturn(true)
        assertTrue(completer.value.onTabComplete(player, command, "rg", arrayOf(""))!!.contains("transfer-review"))
        assertEquals(
            listOf("list"),
            completer.value.onTabComplete(player, command, "rg", arrayOf("transfer-review", "")),
        )
        `when`(player.hasPermission(TransferReviewSubCommand.RESOLVE)).thenReturn(true)
        assertEquals(
            listOf("list", "resolve"),
            completer.value.onTabComplete(player, command, "rg", arrayOf("transfer-review", "")),
        )
    }
}
