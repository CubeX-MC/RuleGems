package org.cubexmc.listeners;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;

class GemDisplayListenerTest {

    @Test
    void leftClickOnDisplayIsProtectedAndDelegatesPickup() {
        GemManager gemManager = mock(GemManager.class);
        Player player = mock(Player.class);
        ArmorStand display = mock(ArmorStand.class);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(display);
        when(event.getDamager()).thenReturn(player);
        when(gemManager.isDisplayedGem(display)).thenReturn(true);

        new GemDisplayListener(gemManager).onEntityDamage(event);

        verify(event).setCancelled(true);
        verify(gemManager).handleDisplayedGemHit(player, display);
    }
}
