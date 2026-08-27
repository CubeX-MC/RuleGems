package org.cubexmc.listeners;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.cubexmc.manager.CustomCommandExecutor;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemDefinitionParser;
import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.GemAllowanceManager.AllowanceSourceType;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.manager.TransferExecutionCoordinator;
import org.cubexmc.model.AllowedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.cubexmc.commands.AllowedCommandProxy;
import org.cubexmc.RuleGems;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void proxySuggestsVisibleOnlinePlayersCaseInsensitivelyWithoutConsumingUses() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        Player alex = fixture.online("Alex", true);
        Player alice = fixture.online("Alice", true);
        Player hidden = fixture.online("AlHidden", false);
        Player bob = fixture.online("Bob", true);
        when(fixture.server.getOnlinePlayers()).thenAnswer(ignored -> List.of(bob, hidden, alice, alex));
        AllowedCommandProxy proxy = new AllowedCommandProxy("cxfine", mock(RuleGems.class), fixture.listener);

        assertEquals(List.of("Alex", "Alice"), proxy.tabComplete(fixture.player, "cxfine", new String[]{"aL"}));
        verify(fixture.allowances, never()).tryConsumeAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
        verifyNoInteractions(fixture.executor, fixture.language);
    }

    @Test
    void amountCandidatesFollowResolvedSourceAndNeverReadOnlinePlayers() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        assertEquals(List.of("50", "100", "500"), fixture.listener.suggestProxyTab(
                fixture.player, "cxfine", new String[]{"Alex", ""}));
        assertEquals(List.of("50", "500"), fixture.listener.suggestProxyTab(
                fixture.player, "cxfine", new String[]{"Alex", "5"}));
        fixture.useCap(10000);
        assertEquals(List.of("50", "100", "500", "1000", "5000", "10000"), fixture.listener.suggestProxyTab(
                fixture.player, "cxfine", new String[]{"Alex", ""}));
        assertEquals(List.of(), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{"Alex", "999"}));
        verify(fixture.server, never()).getOnlinePlayers();
        verifyNoInteractions(fixture.executor, fixture.language);
    }

    @Test
    void nativeEventReplacesUnfilteredCandidatesAndHandlesTrailingSpace() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        Player alex = fixture.online("Alex", true);
        when(fixture.server.getOnlinePlayers()).thenAnswer(ignored -> List.of(alex));
        TabCompleteEvent names = new TabCompleteEvent(fixture.player, "/cxfine ", List.of("HiddenPlayer"));
        fixture.listener.onTabComplete(names);
        assertEquals(List.of("Alex"), names.getCompletions());
        TabCompleteEvent amounts = new TabCompleteEvent(fixture.player, "/cxfine Alex ", List.of("999999"));
        fixture.listener.onTabComplete(amounts);
        assertEquals(List.of("50", "100", "500"), amounts.getCompletions());
        TabCompleteEvent noMatch = new TabCompleteEvent(fixture.player, "/cxfine Alex 9", List.of("999999"));
        fixture.listener.onTabComplete(noMatch);
        assertEquals(List.of(), noMatch.getCompletions());
    }

    @Test
    void proxyCompletionEventDoesNotQueryCandidatesTwice() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        fixture.listener.updateProxyLabels(Set.of("cxfine"));
        TabCompleteEvent event = new TabCompleteEvent(fixture.player, "/cxfine ", List.of("Alex"));
        fixture.listener.onTabComplete(event);
        assertEquals(List.of("Alex"), event.getCompletions());
        verify(fixture.allowances, never()).resolveAllowedCommand(any(), any());
        verify(fixture.server, never()).getOnlinePlayers();
    }

    @Test
    void unavailableSourceCannotProvideRuleGemsHintsEvenWithCachedLabels() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        when(fixture.allowances.resolveAllowedCommand(fixture.id, "cxfine")).thenReturn(null);
        assertEquals(List.of(), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{""}));
        TabCompleteEvent event = new TabCompleteEvent(fixture.player, "/cxfine ", List.of("native"));
        fixture.listener.onTabComplete(event);
        assertEquals(List.of("native"), event.getCompletions());
        verify(fixture.server, never()).getOnlinePlayers();
        verifyNoInteractions(fixture.executor, fixture.language);
    }

    @Test
    void unconfiguredArgumentPreservesNativeCompletionsAndEmptyArgsDoNotThrow() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        assertEquals(List.of("cxfine"), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[0]));
        assertEquals(List.of(), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{"Alex", "50", ""}));
        TabCompleteEvent event = new TabCompleteEvent(fixture.player, "/cxfine Alex 50 ", List.of("native"));
        fixture.listener.onTabComplete(event);
        assertEquals(List.of("native"), event.getCompletions());
    }

    @Test
    void playerSuggestionsAreLimitedAndRefreshedWithoutAStaleCache() {
        SuggestionFixture fixture = new SuggestionFixture(500);
        List<Player> online = new ArrayList<>();
        for (int i = 0; i < 60; i++) online.add(fixture.online("Player" + i, true));
        when(fixture.server.getOnlinePlayers()).thenAnswer(ignored -> online);
        assertEquals(50, fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{""}).size());
        assertEquals(List.of("Player59"), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{"Player59"}));
        when(fixture.server.getOnlinePlayers()).thenReturn(List.of());
        assertEquals(List.of(), fixture.listener.suggestProxyTab(fixture.player, "cxfine", new String[]{""}));
    }

    private static class SuggestionFixture {
        final UUID id = UUID.randomUUID();
        final Server server = mock(Server.class);
        final Player player = mock(Player.class);
        final GemAllowanceManager allowances = mock(GemAllowanceManager.class);
        final LanguageManager language = mock(LanguageManager.class);
        final CustomCommandExecutor executor = mock(CustomCommandExecutor.class);
        final CommandAllowanceListener listener = new CommandAllowanceListener(allowances, language, executor, null);

        SuggestionFixture(int cap) {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getServer()).thenReturn(server);
            when(allowances.getAvailableCommandLabels(id)).thenReturn(Set.of("cxfine"));
            useCap(cap);
        }

        void useCap(int cap) {
            AllowedCommand command = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null).parsePowerStructure(Map.of(
                    "command_allows", List.of(Map.of("command", "/cxfine", "execute", List.of("transfer:%arg1% bank %arg2%"),
                            "args", Map.of("arg1", Map.of("suggestions", "online_players"), "arg2", Map.of("type", "number",
                                    "min", 0.01, "max", cap, "suggestions", List.of(50, 100, 500, 1000, 5000, 10000)))))))
                    .getAllowedCommands().get(0);
            var source = cap == 500 ? AllowanceSourceType.APPOINTMENT : AllowanceSourceType.HELD;
            var resolved = new GemAllowanceManager.ResolvedAllowance(id, source, null, "money", "cxfine", command);
            when(allowances.resolveAllowedCommand(id, "cxfine")).thenReturn(resolved);
        }

        Player online(String name, boolean visible) {
            Player target = mock(Player.class);
            when(target.getName()).thenReturn(name);
            when(player.canSee(target)).thenReturn(visible);
            return target;
        }
    }

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
    void exactCapDelegatesToDurableTransferBoundary(int cap) {
        AllowanceSourceType source = cap == 500 ? AllowanceSourceType.APPOINTMENT : AllowanceSourceType.HELD;
        ArgumentFixture fixture = new ArgumentFixture(cap, source);
        when(fixture.executor.checkCooldown(fixture.id, fixture.resolved.getCooldownKey())).thenReturn(true);
        when(fixture.transfers.execute(eq(fixture.player), eq(fixture.resolved), any()))
                .thenReturn(new TransferExecutionCoordinator.Attempt("allowance.used", null));
        fixture.listener.handleProxyExecution(fixture.player, "cxfine Alex " + cap, "cxfine", new String[]{"Alex", "" + cap});

        verify(fixture.transfers).execute(eq(fixture.player), eq(fixture.resolved), any());
        verify(fixture.allowances, never()).tryConsumeAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
        verify(fixture.allowances, never()).refundAllowed(any(), any(GemAllowanceManager.ResolvedAllowance.class));
    }

    private static class ArgumentFixture {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final GemAllowanceManager allowances = mock(GemAllowanceManager.class);
        final LanguageManager language = mock(LanguageManager.class);
        final CustomCommandExecutor executor = mock(CustomCommandExecutor.class);
        final TransferExecutionCoordinator transfers = mock(TransferExecutionCoordinator.class);
        final CommandAllowanceListener listener = new CommandAllowanceListener(allowances, language, executor, null, transfers);
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
