package org.cubexmc.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuickShopHikariBridgeTest {

    private final RuleGems plugin = mock(RuleGems.class);
    private final GemManager gemManager = mock(GemManager.class);
    private final LanguageManager languageManager = mock(LanguageManager.class);
    private final ItemStack shopItem = mock(ItemStack.class);
    private final TestShop shop = new TestShop(shopItem);
    private QuickShopHikariBridge bridge;

    @BeforeEach
    void setUp() {
        when(plugin.getLogger()).thenReturn(Logger.getLogger("QuickShopHikariBridgeTest"));
        when(languageManager.getMessage("messages.inventory.container_denied"))
                .thenReturn("Rule Gems cannot be traded");
        bridge = new QuickShopHikariBridge(plugin, gemManager, languageManager);
    }

    @Test
    void purchaseIsCancelledBeforeQuickShopCanMoveMoneyOrItems() {
        when(gemManager.containsGem(shopItem)).thenReturn(true);
        TestPurchaseEvent event = new TestPurchaseEvent(shop);

        assertTrue(bridge.handlePurchaseEvent(event));
        assertTrue(event.isCancelled());
        verify(gemManager).containsGem(shopItem);
    }

    @Test
    void ordinaryShopItemsRemainTradable() {
        when(gemManager.containsGem(shopItem)).thenReturn(false);
        TestPurchaseEvent event = new TestPurchaseEvent(shop);

        assertFalse(bridge.handlePurchaseEvent(event));
        assertFalse(event.isCancelled());
    }

    @Test
    void shopCreationIsCancelledOnlyInQuickShopsCancellablePrePhase() {
        when(gemManager.containsGem(shopItem)).thenReturn(true);
        TestCreateEvent pre = new TestCreateEvent(shop, true);
        TestCreateEvent main = new TestCreateEvent(shop, false);

        assertTrue(bridge.handleCreateEvent(pre));
        assertTrue(pre.isCancelled());
        assertFalse(bridge.handleCreateEvent(main));
        assertFalse(main.isCancelled());
    }

    public static final class TestShop {
        private final ItemStack item;

        TestShop(ItemStack item) {
            this.item = item;
        }

        public ItemStack getItem() {
            return item;
        }
    }

    public static final class TestPhase {
        private final boolean cancellable;

        TestPhase(boolean cancellable) {
            this.cancellable = cancellable;
        }

        public boolean cancellable() {
            return cancellable;
        }
    }

    public static final class TestPurchaseEvent extends TestCancellableEvent {
        private final TestShop shop;

        TestPurchaseEvent(TestShop shop) {
            this.shop = shop;
        }

        public TestShop getShop() {
            return shop;
        }
    }

    public static final class TestCreateEvent extends TestCancellableEvent {
        private final TestShop shop;
        private final TestPhase phase;

        TestCreateEvent(TestShop shop, boolean cancellable) {
            this.shop = shop;
            this.phase = new TestPhase(cancellable);
        }

        public Optional<TestShop> shop() {
            return Optional.of(shop);
        }

        public TestPhase phase() {
            return phase;
        }
    }

    public abstract static class TestCancellableEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
