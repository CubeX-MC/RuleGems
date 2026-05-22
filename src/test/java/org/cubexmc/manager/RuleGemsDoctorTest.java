package org.cubexmc.manager;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.features.FeatureManager;
import org.cubexmc.features.revoke.RevokeFeature;
import org.cubexmc.features.revoke.RevokeRule;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.provider.BukkitPermissionProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class RuleGemsDoctorTest {

    @Test
    void warnsWhenBukkitProviderCannotApplyConfiguredGroups() {
        RuleGems plugin = basePlugin();
        PowerStructure power = new PowerStructure();
        power.getVaultGroups().add("king");
        GemDefinition crown = new GemDefinition.Builder("crown")
                .powerStructure(power)
                .count(2)
                .build();
        when(plugin.getGemParser().getGemDefinitions()).thenReturn(List.of(crown));
        when(plugin.getGemManager().getAllGemUuids()).thenReturn(Set.of(UUID.randomUUID()));
        when(plugin.getPermissionProvider()).thenReturn(new BukkitPermissionProvider());

        CommandSender sender = mock(CommandSender.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));

            new RuleGemsDoctor(plugin).sendReport(sender);
        }

        verify(sender).sendMessage(contains("permission_groups are configured"));
        verify(sender).sendMessage(contains("expected/current: 2/1"));
    }

    @Test
    void warnsWhenRevokeRuleReferencesUnknownGemKeys() {
        RuleGems plugin = basePlugin();
        GemDefinition crown = new GemDefinition.Builder("crown").count(1).build();
        when(plugin.getGemParser().getGemDefinitions()).thenReturn(List.of(crown));
        when(plugin.getGemManager().getAllGemUuids()).thenReturn(Set.of(UUID.randomUUID()));
        RevokeFeature revokeFeature = mock(RevokeFeature.class);
        when(revokeFeature.isEnabled()).thenReturn(true);
        when(revokeFeature.getRules()).thenReturn(java.util.Map.of("judgment",
                new RevokeRule("judgment", "Judgment", "judgment", List.of("missing_power"),
                        true, false, 0L, true, true, true)));
        FeatureManager featureManager = mock(FeatureManager.class);
        when(featureManager.getRevokeFeature()).thenReturn(revokeFeature);
        when(plugin.getFeatureManager()).thenReturn(featureManager);

        CommandSender sender = mock(CommandSender.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));

            new RuleGemsDoctor(plugin).sendReport(sender);
        }

        verify(sender).sendMessage(contains("missing trigger_gem"));
        verify(sender).sendMessage(contains("missing target power"));
    }

    private RuleGems basePlugin() {
        RuleGems plugin = mock(RuleGems.class);
        ConfigManager configManager = mock(ConfigManager.class);
        GameplayConfig gameplayConfig = mock(GameplayConfig.class);
        GemDefinitionParser parser = mock(GemDefinitionParser.class);
        GemManager gemManager = mock(GemManager.class);
        LanguageManager languageManager = mock(LanguageManager.class);

        YamlConfiguration config = new YamlConfiguration();
        config.set("random_place_range.world", "world");
        config.set("storage.type", "yaml");

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getGameplayConfig()).thenReturn(gameplayConfig);
        when(plugin.getGemParser()).thenReturn(parser);
        when(plugin.getGemManager()).thenReturn(gemManager);
        when(plugin.getLanguageManager()).thenReturn(languageManager);
        lenient().when(plugin.getFeatureManager()).thenReturn(null);
        lenient().when(plugin.getPermissionProvider()).thenReturn(mock(org.cubexmc.provider.PermissionProvider.class));
        when(configManager.getConfig()).thenReturn(config);
        when(languageManager.getLanguage()).thenReturn("en_US");

        PowerStructure emptyPower = new PowerStructure();
        when(gameplayConfig.getRedeemAllPowerStructure()).thenReturn(emptyPower);
        when(gameplayConfig.isPlaceRedeemEnabled()).thenReturn(false);
        when(gameplayConfig.isOpEscalationAllowed()).thenReturn(false);
        return plugin;
    }
}
