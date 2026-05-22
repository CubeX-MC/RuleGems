package org.cubexmc.features.rule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleGateFeatureTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsSpecificGemPermissionWhenEnabled() throws Exception {
        RuleGateFeature feature = createFeature("enabled: true\npermission_gate:\n  enabled: true\n");
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.rule")).thenReturn(false);
        when(player.hasPermission("rulegems.rule.*")).thenReturn(false);
        when(player.hasPermission("rulegems.rule.flight")).thenReturn(true);

        assertTrue(feature.canUsePower(player, "flight"));
        assertFalse(feature.canUsePower(player, "justice"));
    }

    @Test
    void allowsGlobalRulePermissionWhenEnabled() throws Exception {
        RuleGateFeature feature = createFeature("enabled: true\npermission_gate:\n  enabled: true\n");
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.rule")).thenReturn(true);

        assertTrue(feature.canUsePower(player, "justice"));
        assertTrue(feature.canUsePower(player));
    }

    @Test
    void disabledGateDoesNotRestrictPower() throws Exception {
        RuleGateFeature feature = createFeature("enabled: false\n");
        Player player = mock(Player.class);

        assertTrue(feature.canUsePower(player, "justice"));
    }

    private RuleGateFeature createFeature(String yaml) throws Exception {
        File dataFolder = tempDir.toFile();
        File featuresFolder = new File(dataFolder, "features");
        featuresFolder.mkdirs();
        java.nio.file.Files.writeString(new File(featuresFolder, "rule.yml").toPath(), yaml);

        RuleGems plugin = mock(RuleGems.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGateFeatureTest-" + UUID.randomUUID()));

        RuleGateFeature feature = new RuleGateFeature(plugin, mock(GemManager.class));
        feature.reload();
        return feature;
    }
}
