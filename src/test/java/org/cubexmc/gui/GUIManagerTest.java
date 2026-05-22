package org.cubexmc.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
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

class GUIManagerTest {

    private RuleGems plugin;
    private GemManager gemManager;
    private LanguageManager languageManager;
    private FeatureManager featureManager;
    private AppointFeature appointFeature;
    private PluginManager pluginManager;
    private MockedStatic<Bukkit> mockedBukkit;

    @BeforeEach
    void setUp() {
        plugin = mock(RuleGems.class);
        gemManager = mock(GemManager.class);
        languageManager = mock(LanguageManager.class);
        featureManager = mock(FeatureManager.class);
        appointFeature = mock(AppointFeature.class);
        pluginManager = mock(PluginManager.class);

        when(plugin.getFeatureManager()).thenReturn(featureManager);
        when(plugin.getName()).thenReturn("RuleGems");
        when(featureManager.getAppointFeature()).thenReturn(appointFeature);
        when(appointFeature.getAppointDefinitions()).thenReturn(Map.of("guard", new AppointDefinition("guard")));

        mockedBukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
        mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void cabinetAccessibleForAdminPlayer() {
        Player player = mock(Player.class);
        when(appointFeature.isEnabled()).thenReturn(true);
        when(player.hasPermission("rulegems.admin")).thenReturn(true);

        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertTrue(guiManager.canOpenCabinet(player));
    }

    @Test
    void cabinetAccessibleForPlayerWithAppointPermission() {
        Player player = mock(Player.class);
        when(appointFeature.isEnabled()).thenReturn(true);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.appoint.guard")).thenReturn(true);

        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertTrue(guiManager.canOpenCabinet(player));
    }

    @Test
    void cabinetRejectedWithoutFeatureOrPermission() {
        Player player = mock(Player.class);
        when(appointFeature.isEnabled()).thenReturn(false);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.appoint.guard")).thenReturn(false);

        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertFalse(guiManager.canOpenCabinet(player));
    }

    @Test
    void gemsGuiRequiresGemsPermissionOrAdmin() {
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.gems")).thenReturn(false, true);
        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertFalse(guiManager.canOpenGems(player));
        assertTrue(guiManager.canOpenGems(player));
    }

    @Test
    void rulersGuiRequiresRulersPermissionOrAdmin() {
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.rulers")).thenReturn(false, true);
        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertFalse(guiManager.canOpenRulers(player));
        assertTrue(guiManager.canOpenRulers(player));
    }

    @Test
    void adminCanOpenRestrictedGuiViews() {
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(true);
        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        assertTrue(guiManager.canOpenGems(player));
        assertTrue(guiManager.canOpenRulers(player));
    }

    @Test
    void menuNavigationToGemsRejectsMissingPermission() throws Exception {
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.gems")).thenReturn(false);
        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        invokeNavigation(guiManager, player, "open_gems");

        verify(languageManager).sendMessage(player, "command.no_permission");
    }

    @Test
    void menuNavigationToRulersRejectsMissingPermission() throws Exception {
        Player player = mock(Player.class);
        when(player.hasPermission("rulegems.admin")).thenReturn(false);
        when(player.hasPermission("rulegems.rulers")).thenReturn(false);
        GUIManager guiManager = new GUIManager(plugin, gemManager, languageManager);

        invokeNavigation(guiManager, player, "open_rulers");

        verify(languageManager).sendMessage(player, "command.no_permission");
    }

    private void invokeNavigation(GUIManager guiManager, Player player, String action) throws Exception {
        Method method = GUIManager.class.getDeclaredMethod("handleNavigation",
                Player.class, GUIHolder.class, String.class);
        method.setAccessible(true);
        method.invoke(guiManager, player,
                new GUIHolder(GUIHolder.GUIType.MAIN_MENU, UUID.randomUUID(), false), action);
    }
}
