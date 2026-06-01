package org.cubexmc.commands.sub;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.features.FeatureManager;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DismissSubCommandTest {

    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private RuleGems plugin;
    private GemManager gemManager;
    private LanguageManager languageManager;
    private FeatureManager featureManager;
    private AppointFeature appointFeature;
    private DismissSubCommand command;
    private MockedStatic<Bukkit> mockedBukkit;

    @BeforeEach
    void setUp() {
        plugin = mock(RuleGems.class);
        gemManager = mock(GemManager.class);
        languageManager = mock(LanguageManager.class);
        featureManager = mock(FeatureManager.class);
        appointFeature = mock(AppointFeature.class);

        when(plugin.getFeatureManager()).thenReturn(featureManager);
        when(featureManager.getAppointFeature()).thenReturn(appointFeature);
        when(appointFeature.isEnabled()).thenReturn(true);

        command = new DismissSubCommand(plugin, gemManager, languageManager);
        mockedBukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void resolvesOnlinePlayerAndDelegatesDismiss() {
        Player dismisser = mock(Player.class);
        Player target = mock(Player.class);
        AppointDefinition definition = new AppointDefinition("guard");
        definition.setDisplayName("&aGuard");

        when(target.getUniqueId()).thenReturn(TARGET_ID);
        when(appointFeature.getAppointDefinitions()).thenReturn(Map.of("guard", definition));
        when(appointFeature.dismiss(dismisser, TARGET_ID, "guard")).thenReturn(true);
        mockedBukkit.when(() -> Bukkit.getPlayer("Knight")).thenReturn(target);

        command.execute(dismisser, new String[] { "guard", "Knight" });

        verify(appointFeature).dismiss(dismisser, TARGET_ID, "guard");
        verify(languageManager).sendMessage(org.mockito.ArgumentMatchers.eq(dismisser),
                org.mockito.ArgumentMatchers.eq("command.dismiss.success"), anyMap());
    }
}
