package org.cubexmc.commands.sub;

import org.bukkit.command.CommandSender;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.storage.TransferOperationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferReviewSubCommandTest {
    @TempDir Path directory;
    private RuleGems plugin;
    private GemManager gems;
    private CommandSender sender;
    private LanguageManager language;
    private TransferOperationStore store;
    private TransferReviewSubCommand command;
    private UUID operation;

    @BeforeEach
    void setup() {
        plugin = mock(RuleGems.class);
        gems = mock(GemManager.class);
        sender = mock(CommandSender.class);
        language = mock(LanguageManager.class);
        store = new TransferOperationStore(directory.resolve("transfer-operations.yml").toFile());
        store.reload();
        operation = store.begin(UUID.randomUUID(), "source", "fine").getId();
        when(plugin.getTransferOperations()).thenReturn(store);
        when(plugin.getGemManager()).thenReturn(gems);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferReviewSubCommandTest"));
        command = new TransferReviewSubCommand(plugin, language);
    }

    @Test
    void ordinaryPlayerCannotReadOrResolveAndGetsNoSuggestions() {
        command.execute(sender, new String[]{"list"});
        command.execute(sender, new String[]{"resolve", operation.toString(), "checked"});
        verify(language, times(2)).sendMessage(sender, "command.no_permission");
        assertTrue(command.suggest(sender, new String[]{""}).isEmpty());
        assertTrue(command.suggest(sender, new String[]{"resolve", ""}).isEmpty());
        verifyNoInteractions(gems);
        assertEquals(1, store.all().size());
    }

    @Test
    void readPermissionDoesNotGrantResolveOrLeakCompletionIds() {
        when(sender.hasPermission(TransferReviewSubCommand.REVIEW)).thenReturn(true);
        command.execute(sender, new String[]{"list"});
        verify(language).sendMessage(eq(sender), eq("command.transfer_review.line"), anyMap());
        assertEquals(List.of("list"), command.suggest(sender, new String[]{""}));
        assertTrue(command.suggest(sender, new String[]{"resolve", ""}).isEmpty());
        command.execute(sender, new String[]{"resolve", operation.toString(), "checked"});
        assertEquals(1, store.all().size());
    }

    @Test
    void resolveRequiresSavedAllowancesAndPreservesGuardOnFailure() {
        when(sender.hasPermission(TransferReviewSubCommand.RESOLVE)).thenReturn(true);
        when(sender.getName()).thenReturn("Operator");
        assertEquals(List.of(operation.toString()), command.suggest(sender, new String[]{"resolve", ""}));
        command.execute(sender, new String[]{"resolve", operation.toString(), "balances checked"});
        verify(language).sendMessage(sender, "command.transfer_review.storage_failed");
        assertEquals(1, store.all().size());
        when(gems.saveGemsSync()).thenReturn(true);
        command.execute(sender, new String[]{"resolve", operation.toString(), "balances checked"});
        store.reload();
        assertTrue(store.all().isEmpty());
        verify(language).sendMessage(eq(sender), eq("command.transfer_review.resolved"), anyMap());
    }
    @Test
    void helpUsesTheSameReviewAndResolvePermissionSplit() {
        when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        when(plugin.getFeatureManager()).thenReturn(mock(org.cubexmc.features.FeatureManager.class));
        when(language.formatMessage(anyString(), anyMap())).thenReturn("");
        when(language.translateColorCodes(anyString())).thenReturn("");
        var help = new org.cubexmc.commands.registrar.InfoCommandsRegistrar(plugin, gems, null,
                mock(org.cubexmc.manager.GameplayConfig.class), language);
        help.sendHelp(sender);
        verify(language, never()).formatMessage(eq("messages.command.help.transfer_review"), anyMap());
        verify(language, never()).formatMessage(eq("messages.command.help.transfer_resolve"), anyMap());
        when(sender.hasPermission(TransferReviewSubCommand.REVIEW)).thenReturn(true);
        help.sendHelp(sender);
        verify(language).formatMessage(eq("messages.command.help.transfer_review"), anyMap());
        verify(language, never()).formatMessage(eq("messages.command.help.transfer_resolve"), anyMap());
        when(sender.hasPermission(TransferReviewSubCommand.RESOLVE)).thenReturn(true);
        help.sendHelp(sender);
        verify(language).formatMessage(eq("messages.command.help.transfer_resolve"), anyMap());
    }
}
