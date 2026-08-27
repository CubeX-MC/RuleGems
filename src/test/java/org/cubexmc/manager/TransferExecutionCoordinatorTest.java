package org.cubexmc.manager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.*;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.economy.*;
import org.cubexmc.model.*;
import org.cubexmc.storage.TransferOperationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferExecutionCoordinatorTest {
    @TempDir Path directory;
    final UUID playerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final UUID gemId = UUID.fromString("20000000-0000-0000-0000-000000000002");
    final Logger logger = Logger.getLogger("TransferExecutionTest");

    @Test void committedThenThrownDepositKeepsUseAndSurvivesReloadAndRestart() throws Exception {
        Fixture f = new Fixture(false);
        doAnswer(call -> {
            f.received.addAndGet(10);
            throw new IllegalStateException("committed then threw");
        }).when(f.economy).depositPlayer("recipient", 10.0);
        var first = f.execute();
        assertEquals("allowance.transfer_review_required", first.getMessage());
        assertEquals(4, f.remaining.get("pay"));
        assertFalse(f.executor.checkCooldown(playerId, f.resolved.getCooldownKey()));
        f.store.reload();
        var reopened = new TransferOperationStore(f.file.toFile());
        reopened.reload();
        var retry = new TransferExecutionCoordinator(reopened, f.allowances, f.executor, () -> true, logger)
                .execute(f.player, f.resolved, new String[0]);
        assertEquals("allowance.transfer_blocked", retry.getMessage());
        assertEquals(first.getOperationId(), retry.getOperationId());
        assertEquals(10, f.received.get());
        verify(f.economy, times(1)).withdrawPlayer("cubex_bank", 10.0);
        assertEquals(List.of("name:cubex_bank name:recipient 10"), reopened.all().get(0).getCommands());
    }

    @Test void successfulTransferThenFailedCommandDoesNotRefund() throws Exception {
        Fixture f = new Fixture(true);
        assertEquals("allowance.transfer_review_required", f.execute().getMessage());
        assertEquals(4, f.remaining.get("pay"));
        assertEquals(10, f.received.get());
        assertEquals("PARTIAL", f.store.all().get(0).getStatus());
        assertEquals("allowance.transfer_blocked", f.execute().getMessage());
        assertEquals(10, f.received.get());
    }

    @Test void compensatedRejectionRefundsAndClosesMarker() throws Exception {
        Fixture f = new Fixture(false);
        doReturn(failure()).when(f.economy).depositPlayer("recipient", 10.0);
        when(f.economy.depositPlayer("cubex_bank", 10.0)).thenReturn(success());
        assertEquals("allowance.execute_failed", f.execute().getMessage());
        assertEquals(5, f.remaining.get("pay"));
        assertTrue(f.store.all().isEmpty());
        assertTrue(f.executor.checkCooldown(playerId, f.resolved.getCooldownKey()));
        verify(f.economy).depositPlayer("cubex_bank", 10.0);
    }

    @Test void normalSuccessPersistsConsumptionBeforeEconomyAndBeforeUnlock() throws Exception {
        Fixture f = new Fixture(false);
        AtomicInteger checkpoints = new AtomicInteger();
        f.persist = () -> {
            assertEquals(4, f.remaining.get("pay"));
            assertEquals(1, f.store.all().size());
            checkpoints.incrementAndGet();
            return true;
        };
        when(f.economy.withdrawPlayer("cubex_bank", 10.0)).thenAnswer(call -> {
            assertEquals(1, checkpoints.get());
            return success();
        });
        assertTrue(f.execute().getSuccessful());
        assertEquals(2, checkpoints.get());
        assertTrue(f.store.all().isEmpty());
        assertEquals(4, f.remaining.get("pay"));
    }

    @Test void failedPreExecutionSaveNeverCallsEconomyAndKeepsGuard() throws Exception {
        Fixture f = new Fixture(false);
        f.persist = () -> false;
        assertEquals("allowance.transfer_review_required", f.execute().getMessage());
        verifyNoInteractions(f.economy);
        assertEquals(5, f.remaining.get("pay"));
        assertEquals("PRE_EXECUTION_SAVE_FAILED", f.store.all().get(0).getStatus());
    }

    @Test void failedFinalSaveKeepsCommittedUseAndRetryGuard() throws Exception {
        Fixture f = new Fixture(false);
        AtomicInteger saves = new AtomicInteger();
        f.persist = () -> saves.incrementAndGet() == 1;
        assertEquals("allowance.transfer_review_required", f.execute().getMessage());
        assertEquals(4, f.remaining.get("pay"));
        assertEquals(10, f.received.get());
        assertEquals("POST_EXECUTION_SAVE_FAILED", f.store.all().get(0).getStatus());
    }

    @Test void failedJournalWriteNeverConsumesOrMovesMoney() throws Exception {
        Fixture f = new Fixture(false);
        Files.createDirectory(f.file);
        assertEquals("allowance.transfer_storage_failed", f.execute().getMessage());
        assertEquals(5, f.remaining.get("pay"));
        verifyNoInteractions(f.economy);
    }

    @Test void malformedJournalPreservesExistingGuardAndDoesNotOverwriteOnClose() throws Exception {
        Fixture f = new Fixture(false);
        f.store.begin(playerId, f.resolved.getCooldownKey(), "pay");
        Files.writeString(f.file, "operations: [broken\n");
        assertThrows(Exception.class, f.store::reload);
        assertNotNull(f.store.pending(playerId, f.resolved.getCooldownKey()));
        f.store.close();
        assertEquals("operations: [broken\n", Files.readString(f.file));
    }

    @Test void reconciliationArchivesAcknowledgementWithoutChangingMoneyOrUses() throws Exception {
        Fixture f = new Fixture(true);
        var attempt = f.execute();
        clearInvocations(f.economy);
        assertTrue(f.store.resolve(attempt.getOperationId(), "Admin", "checked provider ledger"));
        assertTrue(f.store.all().isEmpty());
        assertEquals(4, f.remaining.get("pay"));
        verifyNoInteractions(f.economy);
        try (var files = Files.list(directory.resolve("transfer-reviews"))) {
            assertEquals(1, files.count());
        }
    }

    class Fixture {
        final Path file = directory.resolve("transfer-operations.yml");
        final TransferOperationStore store = new TransferOperationStore(file.toFile());
        final Player player = mock(Player.class);
        final Economy economy = mock(Economy.class);
        final AtomicInteger received = new AtomicInteger();
        final Map<String, Integer> remaining = new HashMap<>(Map.of("pay", 5));
        final GemAllowanceManager allowances;
        final CustomCommandExecutor executor;
        final GemAllowanceManager.ResolvedAllowance resolved;
        BooleanSupplier persist = () -> true;
        Fixture(boolean laterFailure) throws Exception {
            store.reload();
            RuleGems plugin = mock(RuleGems.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.getName()).thenReturn("recipient");
            GameplayConfig config = mock(GameplayConfig.class);
            when(config.isTransferDirectivesEnabled()).thenReturn(true);
            when(economy.has("cubex_bank", 10.0)).thenReturn(true);
            when(economy.withdrawPlayer("cubex_bank", 10.0)).thenReturn(success());
            when(economy.depositPlayer("recipient", 10.0)).thenAnswer(call -> {
                received.addAndGet(10); return success();
            });
            List<String> chain = new ArrayList<>(List.of("transfer:name:cubex_bank name:recipient 10"));
            if (laterFailure) chain.add("player:missing-command");
            AllowedCommand command = new AllowedCommand("pay", 5, chain, 7200);
            GemDefinition definition = mock(GemDefinition.class);
            when(definition.getGemKey()).thenReturn("treasury");
            when(definition.getAllowedCommands()).thenReturn(List.of(command));
            GemDefinitionParser parser = mock(GemDefinitionParser.class);
            when(parser.getGemDefinitions()).thenReturn(List.of(definition));
            allowances = new GemAllowanceManager(parser, config);
            allowances.getPlayerGemHeldUses().put(playerId, new HashMap<>(Map.of(gemId, remaining)));
            allowances.setGemKeyLookup(ignored -> "treasury");
            resolved = allowances.resolveAllowedCommand(playerId, "pay");
            executor = new CustomCommandExecutor(plugin, mock(LanguageManager.class), config,
                    new VaultTransfers(economy, mock(OfflinePlayerLookup.class), logger));
        }
        TransferExecutionCoordinator.Attempt execute() {
            return new TransferExecutionCoordinator(store, allowances, executor, persist, logger)
                    .execute(player, resolved, new String[0]);
        }
    }
    static EconomyResponse success() { return new EconomyResponse(10, 90, EconomyResponse.ResponseType.SUCCESS, null); }
    static EconomyResponse failure() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "rejected"); }
}
