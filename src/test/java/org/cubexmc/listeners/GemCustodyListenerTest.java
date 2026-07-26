package org.cubexmc.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.junit.jupiter.api.Test;

class GemCustodyListenerTest {

    private static final UUID GEM_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final GemManager gemManager = mock(GemManager.class);
    private final LanguageManager languageManager = mock(LanguageManager.class);
    private final GemCustodyListener listener = new GemCustodyListener(gemManager, languageManager);

    @Test
    void gemItemEntityIsNeverAllowedToExistAndIsRecovered() {
        // 容器被破坏吐出宝石走的是这条路径，PlayerDropItemEvent 覆盖不到。
        ItemStack stack = mock(ItemStack.class);
        Location location = mock(Location.class);
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        when(entity.getLocation()).thenReturn(location);
        when(gemManager.containsGem(stack)).thenReturn(true);
        when(gemManager.collectGemIds(stack)).thenReturn(Collections.singletonList(GEM_ID));
        when(gemManager.claimItemCustody(entity.getUniqueId())).thenReturn(true);

        ItemSpawnEvent event = mock(ItemSpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemSpawn(event);

        verify(event).setCancelled(true);
        verify(gemManager).recoverStrayGem(GEM_ID, location);
    }

    @Test
    void theSameItemEntityIsRecoveredOnlyOnceAcrossDuplicateCallbacks() {
        ItemStack stack = mock(ItemStack.class);
        Location location = mock(Location.class);
        Item entity = mock(Item.class);
        UUID entityId = UUID.fromString("21000000-0000-0000-0000-000000000002");
        when(entity.getUniqueId()).thenReturn(entityId);
        when(entity.getItemStack()).thenReturn(stack);
        when(entity.getLocation()).thenReturn(location);
        when(gemManager.containsGem(stack)).thenReturn(true);
        when(gemManager.collectGemIds(stack)).thenReturn(Collections.singletonList(GEM_ID));
        when(gemManager.claimItemCustody(entityId)).thenReturn(true, false);

        ItemSpawnEvent event = mock(ItemSpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemSpawn(event);
        listener.onItemSpawn(event);

        verify(gemManager).recoverStrayGem(GEM_ID, location);
    }

    @Test
    void ordinaryItemEntitiesAreLeftAlone() {
        ItemStack stack = mock(ItemStack.class);
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        when(gemManager.containsGem(stack)).thenReturn(false);

        ItemSpawnEvent event = mock(ItemSpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemSpawn(event);

        verify(event, never()).setCancelled(true);
        verify(gemManager, never()).recoverStrayGem(any(), any());
    }

    @Test
    void hopperCannotSwallowAGemItem() {
        ItemStack stack = mock(ItemStack.class);
        Location location = mock(Location.class);
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        when(entity.getLocation()).thenReturn(location);
        when(gemManager.containsGem(stack)).thenReturn(true);
        when(gemManager.collectGemIds(stack)).thenReturn(Collections.singletonList(GEM_ID));
        when(gemManager.claimItemCustody(entity.getUniqueId())).thenReturn(true);

        InventoryPickupItemEvent event = mock(InventoryPickupItemEvent.class);
        when(event.getItem()).thenReturn(entity);

        listener.onInventoryPickupItem(event);

        verify(event).setCancelled(true);
        verify(entity).remove();
        verify(gemManager).recoverStrayGem(GEM_ID, location);
    }

    @Test
    void holdingAGemBlocksInteractionWithAStorageEntity() {
        StorageMinecart minecart = mock(StorageMinecart.class);
        ItemStack gem = mock(ItemStack.class);
        Player player = playerHolding(gem);
        when(gemManager.isDisplayedGem(minecart)).thenReturn(false);
        when(gemManager.containsGem(gem)).thenReturn(true);

        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRightClicked()).thenReturn(minecart);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);

        listener.onInteractEntity(event);

        verify(event).setCancelled(true);
        verify(languageManager).sendMessage(eq(player), eq("inventory.container_denied"));
    }

    @Test
    void holdingAGemDoesNotBlockInteractionWithANonStorageEntity() {
        Cow cow = mock(Cow.class);
        ItemStack gem = mock(ItemStack.class);
        Player player = playerHolding(gem);
        when(gemManager.isDisplayedGem(cow)).thenReturn(false);

        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRightClicked()).thenReturn(cow);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);

        listener.onInteractEntity(event);

        verify(event, never()).setCancelled(true);
    }

    private Player playerHolding(ItemStack mainHand) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        return player;
    }
}
