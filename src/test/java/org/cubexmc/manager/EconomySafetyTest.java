package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.economy.EconomyProvider;
import org.cubexmc.model.AllowedCommand;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class EconomySafetyTest {
    private static final UUID FROM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TO_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

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
                    EconomyProvider.Result.FAILED,
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
                    EconomyProvider.Result.ROLLBACK_FAILED,
                    provider(economy).transfer("uuid:" + FROM_ID, "uuid:" + TO_ID, 10.0));
        }
    }

    private EconomyProvider provider(Economy economy) throws Exception {
        Constructor<EconomyProvider> constructor =
                EconomyProvider.class.getDeclaredConstructor(Economy.class);
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
