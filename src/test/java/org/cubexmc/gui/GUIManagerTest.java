package org.cubexmc.rulegems.gui;

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
    void sharedRegistryRoutesTopButtonsButNeverPlayerInventoryItems() {
        GUIManager manager = new GUIManager(plugin, gemManager, languageManager);
        org.mockito.ArgumentCaptor<org.bukkit.event.Listener> registered =
                org.mockito.ArgumentCaptor.forClass(org.bukkit.event.Listener.class);
        verify(pluginManager).registerEvents(registered.capture(), org.mockito.ArgumentMatchers.eq(plugin));
        org.cubexmc.gui.MenuRegistry registry =
                (org.cubexmc.gui.MenuRegistry) registered.getValue();
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        org.bukkit.inventory.Inventory inventory = mock(org.bukkit.inventory.Inventory.class);
        when(inventory.getSize()).thenReturn(54);
        when(inventory.getHolder()).thenReturn(new GUIHolder(GUIHolder.GUIType.MAIN_MENU,
                playerId, false));
        org.bukkit.inventory.ItemStack icon = mock(org.bukkit.inventory.ItemStack.class);
        when(icon.getType()).thenReturn(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        when(icon.getItemMeta()).thenReturn(meta);
        org.bukkit.persistence.PersistentDataContainer pdc = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(manager.getNavActionKey(), org.bukkit.persistence.PersistentDataType.STRING)).thenReturn("close");
        when(inventory.getItem(0)).thenReturn(icon);
        manager.openInventory(player, inventory);

        org.bukkit.event.inventory.InventoryClickEvent event =
                mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(icon);
        when(event.getRawSlot()).thenReturn(54);
        registry.onClick(event);
        verify(event).setCancelled(true);
        verify(player, org.mockito.Mockito.never()).closeInventory();

        when(event.getRawSlot()).thenReturn(0);
        registry.onClick(event);
        verify(player).closeInventory();
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
