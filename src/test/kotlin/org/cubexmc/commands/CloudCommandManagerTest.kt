package org.cubexmc.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.gui.GUIManager
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.junit.jupiter.api.Assertions.assertTrue
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
}
