package org.cubexmc.listeners;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.junit.jupiter.api.Test;

class GemInventoryListenerTest {

    @Test
    void offhandSwapCannotPutAGemIntoAnExternalContainer() {
        GemManager gemManager = mock(GemManager.class);
        LanguageManager languageManager = mock(LanguageManager.class);
        GemInventoryListener listener = new GemInventoryListener(gemManager, languageManager);
        Player player = mock(Player.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        Inventory top = mock(Inventory.class);
        Inventory bottom = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack offhandGem = mock(ItemStack.class);

        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(top.getType()).thenReturn(InventoryType.CHEST);
        when(event.getClickedInventory()).thenReturn(top);
        when(event.getClick()).thenReturn(ClickType.SWAP_OFFHAND);
        when(player.getInventory()).thenReturn(playerInventory);
        when(playerInventory.getItemInOffHand()).thenReturn(offhandGem);
        when(gemManager.containsGem(offhandGem)).thenReturn(true);

        listener.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(languageManager).sendMessage(player, "inventory.container_denied");
    }
}
