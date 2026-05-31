package org.cubexmc.listeners;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.cubexmc.manager.CustomCommandExecutor;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AllowedCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandAllowanceListenerTest {

    @Test
    void playerCommandHandlerReceivesCancelledEventsSoAllowancesCanOverridePluginConflicts() throws Exception {
        Method method = CommandAllowanceListener.class.getDeclaredMethod("onPlayerCommand",
                PlayerCommandPreprocessEvent.class);

        EventHandler handler = method.getAnnotation(EventHandler.class);

        assertFalse(handler.ignoreCancelled());
    }

    @Test
    void cancelledConflictingCommandUsesAllowedCommandBeforeUnderlyingPluginCommand() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(server.getOnlinePlayers()).thenReturn(Collections.emptyList());
        when(player.getServer()).thenReturn(server);
        when(player.getUniqueId()).thenReturn(playerId);

        GemAllowanceManager allowanceManager = mock(GemAllowanceManager.class);
        LanguageManager languageManager = mock(LanguageManager.class);
        CustomCommandExecutor executor = mock(CustomCommandExecutor.class);
        GameplayConfig gameplayConfig = mock(GameplayConfig.class);
        CommandAllowanceListener listener = new CommandAllowanceListener(allowanceManager, languageManager, executor,
                gameplayConfig);

        AllowedCommand command = new AllowedCommand("jail", 1,
                Collections.singletonList("console:cmi jail %arg1% jailed 10m"), 0);
        when(allowanceManager.hasAnyAllowed(playerId, "jail steve jailed 10m")).thenReturn(false);
        when(allowanceManager.hasAnyAllowed(playerId, "jail")).thenReturn(true);
        when(allowanceManager.getAllowedCommand(playerId, "jail")).thenReturn(command);
        when(allowanceManager.tryConsumeAllowed(playerId, "jail")).thenReturn(true);
        when(allowanceManager.getRemainingAllowed(playerId, "jail")).thenReturn(0);
        when(executor.executeExtendedCommand(eq(player), eq(command), any(String[].class))).thenReturn(true);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/jail Steve jailed 10m");
        event.setCancelled(true);

        listener.onPlayerCommand(event);

        assertTrue(event.isCancelled());
        verify(allowanceManager).tryConsumeAllowed(playerId, "jail");
        verify(executor).executeExtendedCommand(eq(player), eq(command), any(String[].class));
    }
}
