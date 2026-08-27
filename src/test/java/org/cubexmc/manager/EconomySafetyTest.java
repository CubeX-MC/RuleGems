package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.economy.VaultTransfers;
import org.cubexmc.model.AllowedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class EconomySafetyTest {
    private static final UUID FROM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TO_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @ParameterizedTest
    @ValueSource(strings = {"10000.01", "0", "-1", "NaN", "Infinity", "%arg3%"})
    void invalidAmountStopsTheWholeChainBeforeTransferOrEarlierCommands(String amount) throws Exception {
        RuleGems plugin = mock(RuleGems.class);
        LanguageManager language = mock(LanguageManager.class);
        GameplayConfig gameplay = mock(GameplayConfig.class);
        Player player = mock(Player.class);
        Economy economy = mock(Economy.class);
        AllowedCommand command = cappedCommand(List.of("player:announce", "transfer:bank Alex %arg2%"));
        CustomCommandExecutor executor = new CustomCommandExecutor(plugin, language, gameplay, provider(economy));

        assertFalse(executor.executeExtendedCommand(player, command, new String[]{"Alex", amount}));

        verifyNoInteractions(economy, gameplay);
        verify(player, never()).performCommand(anyString());
    }

    @Test
    void validAmountAtCapReachesTransferUnchanged() throws Exception {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomySafetyTest"));
        GameplayConfig gameplay = mock(GameplayConfig.class);
        when(gameplay.isTransferDirectivesEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("ruler");
        Economy economy = mock(Economy.class);
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(economy.has(from, 10000.0)).thenReturn(true);
        when(economy.withdrawPlayer(from, 10000.0)).thenReturn(success());
        when(economy.depositPlayer(to, 10000.0)).thenReturn(success());
        AllowedCommand command = cappedCommand(List.of("transfer:uuid:" + FROM_ID + " uuid:" + TO_ID + " %arg2%"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(FROM_ID)).thenReturn(from);
            bukkit.when(() -> Bukkit.getOfflinePlayer(TO_ID)).thenReturn(to);
            org.junit.jupiter.api.Assertions.assertTrue(new CustomCommandExecutor(plugin, null, gameplay, provider(economy))
                    .executeExtendedCommand(player, command, new String[]{"Alex", "10000"}));
        }
        verify(economy).withdrawPlayer(from, 10000.0);
        verify(economy).depositPlayer(to, 10000.0);
    }

    @Test
    void missingRequiredAmountCannotUseAnUncheckedTemplateDefault() throws Exception {
        Economy economy = mock(Economy.class);
        GameplayConfig gameplay = mock(GameplayConfig.class);
        AllowedCommand command = cappedCommand(List.of("transfer:bank Alex %arg2|999999%"));
        CustomCommandExecutor executor = new CustomCommandExecutor(mock(RuleGems.class), null, gameplay, provider(economy));

        assertFalse(executor.executeExtendedCommand(mock(Player.class), command, new String[]{"Alex"}));
        verifyNoInteractions(economy, gameplay);
    }

    private AllowedCommand cappedCommand(List<String> commands) {
        return new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null).parsePowerStructure(Map.of(
                "command_allows", List.of(Map.of("command", "/cxfine", "execute", commands,
                        "args", Map.of("arg2", Map.of("type", "number", "min", 0.01, "max", 10000))))))
                .getAllowedCommands().get(0);
    }

    @Test
    void builtInTransferDirectiveIsDisabledByDefault() {
        RuleGems plugin = mock(RuleGems.class);
        LanguageManager language = mock(LanguageManager.class);
        GameplayConfig gameplay = mock(GameplayConfig.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("payer");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomySafetyTest"));
        AllowedCommand command = new AllowedCommand(
                "pay",
                1,
                List.of("transfer:uuid:" + FROM_ID + " uuid:" + TO_ID + " 10"),
                0);

        CustomCommandExecutor executor =
                new CustomCommandExecutor(plugin, language, gameplay, null);

        assertFalse(executor.executeExtendedCommand(player, command, new String[0]));
        verify(language).sendMessage(player, "allowance.transfer_disabled");
    }

    @Test
    void failedDepositWithSuccessfulCompensationReportsFailure() throws Exception {
        Economy economy = mock(Economy.class);
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(economy.has(from, 10.0)).thenReturn(true);
        when(economy.withdrawPlayer(from, 10.0)).thenReturn(success());
        when(economy.depositPlayer(to, 10.0)).thenReturn(failure());
        when(economy.depositPlayer(from, 10.0)).thenReturn(success());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(FROM_ID)).thenReturn(from);
            bukkit.when(() -> Bukkit.getOfflinePlayer(TO_ID)).thenReturn(to);

            assertEquals(
                    VaultTransfers.Result.FAILED,
                    provider(economy).transfer("uuid:" + FROM_ID, "uuid:" + TO_ID, 10.0));
        }

        verify(economy).depositPlayer(from, 10.0);
    }

    @Test
    void failedCompensationIsDistinguishedForRecovery() throws Exception {
        Economy economy = mock(Economy.class);
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(economy.has(from, 10.0)).thenReturn(true);
        when(economy.withdrawPlayer(from, 10.0)).thenReturn(success());
        when(economy.depositPlayer(to, 10.0)).thenReturn(failure());
        when(economy.depositPlayer(from, 10.0)).thenReturn(failure());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(FROM_ID)).thenReturn(from);
            bukkit.when(() -> Bukkit.getOfflinePlayer(TO_ID)).thenReturn(to);

            assertEquals(
                    VaultTransfers.Result.ROLLBACK_FAILED,
                    provider(economy).transfer("uuid:" + FROM_ID, "uuid:" + TO_ID, 10.0));
        }
    }

    private VaultTransfers provider(Economy economy) throws Exception {
        Constructor<VaultTransfers> constructor =
                VaultTransfers.class.getDeclaredConstructor(Economy.class);
        constructor.setAccessible(true);
        return constructor.newInstance(economy);
    }

    private EconomyResponse success() {
        return new EconomyResponse(10.0, 90.0, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse failure() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "failure");
    }
}
