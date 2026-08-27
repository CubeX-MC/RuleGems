package org.cubexmc.features;

import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeatureManagerLifecycleTest {
    @Test
    void failedInitializationIsStillClosedAndOneCleanupFailureDoesNotSkipOthers() {
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FeatureManagerLifecycleTest"));
        FeatureManager manager = new FeatureManager(plugin, mock(GemManager.class));
        Feature first = mock(Feature.class);
        Feature failing = mock(Feature.class);
        when(first.getPermissionNode()).thenReturn("first");
        when(failing.getPermissionNode()).thenReturn("failing");
        doThrow(new IllegalStateException("initialize")).when(failing).initialize();
        doThrow(new IllegalStateException("close")).when(failing).close();
        manager.registerFeature(first);
        assertThrows(IllegalStateException.class, () -> manager.registerFeature(failing));
        assertDoesNotThrow(manager::close);
        verify(failing).close();
        verify(first).close();
        manager.close();
        verify(first, times(1)).close();
    }
    @Test
    void appointmentGateInputsAreReadyBeforeGemRestoration(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var features = directory.resolve("features");
        java.nio.file.Files.createDirectories(features);
        java.nio.file.Files.createDirectories(directory.resolve("data"));
        var playerId = java.util.UUID.randomUUID();
        java.nio.file.Files.writeString(features.resolve("rule.yml"),
                "enabled: true\npermission_gate:\n  enabled: false\nrequired_power_set: guard\n");
        java.nio.file.Files.writeString(features.resolve("appoint.yml"), "enabled: true\n");
        java.nio.file.Files.writeString(directory.resolve("data/appoints.yml"),
                "appointments:\n  guard:\n    " + playerId + ":\n      appointed_at: 1\n");
        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GatePreparationTest"));
        GemManager gems = mock(GemManager.class);
        FeatureManager manager = new FeatureManager(plugin, gems);
        when(plugin.getFeatureManager()).thenReturn(manager);
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        manager.prepareRuleGate();
        assertTrue(manager.getRuleGateFeature().canUsePower(player, "fire"));
        verifyNoInteractions(gems);
    }
}
