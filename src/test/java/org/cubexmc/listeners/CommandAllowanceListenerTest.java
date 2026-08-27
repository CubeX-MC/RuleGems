package org.cubexmc.listeners;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.cubexmc.manager.CustomCommandExecutor;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemDefinitionParser;
import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.GemAllowanceManager.AllowanceSourceType;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AllowedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.inOrder;

class CommandAllowanceListenerTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOverCapBeforeConsumeCooldownOrAnyCommandOnBothEntryPaths(boolean proxy) {
        ArgumentFixture fixture = new ArgumentFixture(500, AllowanceSourceType.APPOINTMENT);
        if (proxy) {
            fixture.listener.handleProxyExecution(fixture.player, "cxfine Alex 501", "cxfine", new String[]{"Alex", "501"});
        } else {
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(fixture.player, "/cxfine Alex 501");
            fixture.listener.onPlayerCommand(event);
            assertTrue(event.isCancelled());
        }
        verify(fixture.allowances, never()).tryConsumeAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
        verify(fixture.allowances, never()).refundAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
        verifyNoInteractions(fixture.executor);
        verify(fixture.language).sendMessage(fixture.player, "allowance.args_max", Map.of(
                "argument", "arg2", "max", "500", "usage", "/cxfine <player> <amount>"));
        verifyNoMoreInteractions(fixture.language);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 10000})
    void exactCapConsumesOnceAndStartsSourceCooldown(int cap) {
        AllowanceSourceType source = cap == 500 ? AllowanceSourceType.APPOINTMENT : AllowanceSourceType.HELD;
        ArgumentFixture fixture = new ArgumentFixture(cap, source);
        when(fixture.allowances.tryConsumeAllowed(fixture.id, fixture.resolved)).thenReturn(true);
        when(fixture.executor.checkCooldown(fixture.id, fixture.resolved.getCooldownKey())).thenReturn(true);
        when(fixture.executor.executeExtendedCommand(eq(fixture.player), eq(fixture.command), any())).thenReturn(true);
        fixture.listener.handleProxyExecution(fixture.player, "cxfine Alex " + cap, "cxfine", new String[]{"Alex", "" + cap});

        var order = inOrder(fixture.allowances, fixture.executor);
        order.verify(fixture.allowances).tryConsumeAllowed(fixture.id, fixture.resolved);
        order.verify(fixture.executor).executeExtendedCommand(eq(fixture.player), eq(fixture.command), any());
        order.verify(fixture.executor).setCooldown(fixture.id, fixture.resolved.getCooldownKey(), 7200);
        verify(fixture.allowances, never()).refundAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
    }

    private static class ArgumentFixture {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final GemAllowanceManager allowances = mock(GemAllowanceManager.class);
        final LanguageManager language = mock(LanguageManager.class);
        final CustomCommandExecutor executor = mock(CustomCommandExecutor.class);
        final CommandAllowanceListener listener = new CommandAllowanceListener(allowances, language, executor, null);
        final AllowedCommand command;
        final GemAllowanceManager.ResolvedAllowance resolved;

        ArgumentFixture(int cap, AllowanceSourceType source) {
            when(player.getUniqueId()).thenReturn(id);
            Server server = mock(Server.class);
            when(server.getOnlinePlayers()).thenReturn(Collections.emptyList());
            when(player.getServer()).thenReturn(server);
            command = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null).parsePowerStructure(Map.of(
                    "command_allows", List.of(Map.of("command", "/cxfine", "usage", "/cxfine <player> <amount>",
                            "execute", List.of("transfer:%arg1% cubex_bank %arg2%"), "cooldown", 7200,
                            "args", Map.of("arg2", Map.of("type", "number", "min", 0.01, "max", cap))))))
                    .getAllowedCommands().get(0);
            resolved = new GemAllowanceManager.ResolvedAllowance(id, source, null, "money", "cxfine", command);
            when(allowances.hasAnyAllowed(id, "cxfine")).thenReturn(true);
            when(allowances.resolveAllowedCommand(id, "cxfine")).thenReturn(resolved);
        }
    }

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
        GemAllowanceManager.ResolvedAllowance resolved = new GemAllowanceManager.ResolvedAllowance(playerId,
                AllowanceSourceType.APPOINTMENT, null, "police_power", "jail", command);
        when(allowanceManager.hasAnyAllowed(playerId, "jail steve jailed 10m")).thenReturn(false);
        when(allowanceManager.hasAnyAllowed(playerId, "jail")).thenReturn(true);
        when(allowanceManager.resolveAllowedCommand(playerId, "jail")).thenReturn(resolved);
        when(allowanceManager.tryConsumeAllowed(playerId, resolved)).thenReturn(true);
        when(allowanceManager.getRemainingAllowed(playerId, "jail")).thenReturn(0);
        when(executor.executeExtendedCommand(eq(player), eq(command), any(String[].class))).thenReturn(true);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/jail Steve jailed 10m");
        event.setCancelled(true);

        listener.onPlayerCommand(event);

        assertTrue(event.isCancelled());
        verify(allowanceManager).tryConsumeAllowed(playerId, resolved);
        verify(executor).executeExtendedCommand(eq(player), eq(command), any(String[].class));
    }
}
