package org.cubexmc.commands.sub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

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

class AppointSubCommandTest {

    private RuleGems plugin;
    private GemManager gemManager;
    private LanguageManager languageManager;
    private FeatureManager featureManager;
    private AppointFeature appointFeature;
    private AppointSubCommand command;
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

        command = new AppointSubCommand(plugin, gemManager, languageManager);
        mockedBukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void playerWithAppointPermissionCanExecuteCommand() {
        Player appointer = mock(Player.class);
        Player target = mock(Player.class);
        AppointDefinition definition = new AppointDefinition("guard");
        definition.setDisplayName("&aGuard");

        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(true);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);
        when(target.getName()).thenReturn("Knight");
        when(appointFeature.getAppointDefinitions()).thenReturn(Map.of("guard", definition));
        when(appointFeature.isAppointed(any(), eq("guard"))).thenReturn(false);
        when(appointFeature.getAppointmentCountBy(any(), eq("guard"))).thenReturn(0);
        when(appointFeature.appoint(appointer, target, "guard")).thenReturn(true);
        mockedBukkit.when(() -> Bukkit.getPlayer("Knight")).thenReturn(target);

        command.execute(appointer, new String[] { "guard", "Knight" });

        verify(appointFeature).appoint(appointer, target, "guard");
        verify(languageManager).sendMessage(org.mockito.ArgumentMatchers.eq(appointer),
                org.mockito.ArgumentMatchers.eq("command.appoint.success"), anyMap());
    }

    @Test
    void playerWithoutPermissionIsRejectedBeforeAppointing() {
        Player appointer = mock(Player.class);
        AppointDefinition definition = new AppointDefinition("guard");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(false);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);
        when(appointFeature.getAppointDefinitions()).thenReturn(Map.of("guard", definition));

        command.execute(appointer, new String[] { "guard", "Knight" });

        verify(languageManager).sendMessage(appointer, "command.no_permission");
        verify(appointFeature, never()).appoint(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
