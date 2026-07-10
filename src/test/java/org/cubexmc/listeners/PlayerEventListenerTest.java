package org.cubexmc.listeners;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerEventListenerTest {

    @Test
    void cancelledDropIsIgnoredBeforeGemPlacement() {
        RuleGems plugin = mock(RuleGems.class);
        GemManager gemManager = mock(GemManager.class);
        PlayerEventListener listener = new PlayerEventListener(plugin, gemManager);
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);

        when(event.isCancelled()).thenReturn(true);

        listener.onPlayerDropItem(event);

        verify(gemManager, never()).handleGemDrop(
                org.mockito.ArgumentMatchers.any(Player.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Item.class),
                org.mockito.ArgumentMatchers.any(ItemStack.class));
        verify(gemManager, never()).recalculateGrants(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dropHandlerRunsAfterCancellationDecisionsAndSkipsCancelledEvents() throws Exception {
        Method method = PlayerEventListener.class.getDeclaredMethod("onPlayerDropItem", PlayerDropItemEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);

        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }
}
