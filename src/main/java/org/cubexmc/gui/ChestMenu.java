package org.cubexmc.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Abstract base class for all RuleGems chest GUIs.
 *
 * <p>
 * Subclasses must implement {@link #getTitle()}, {@link #getSize()}, and
 * {@link #populate(Inventory)}. Click routing is handled in
 * {@link #onClick(Player, int, ItemStack, PersistentDataContainer, boolean)}.
 *
 * <p>
 * Usage:
 * 
 * <pre>
 *     new MyGUI(manager, ...).open(player, isAdmin);
 * </pre>
 */
public abstract class ChestMenu {

    protected final GUIManager manager;

    protected ChestMenu(GUIManager manager) {
        this.manager = manager;
    }

    // ======================================================================
    // Abstract contract
    // ======================================================================

    /** Human-readable inventory title (supports colour codes via §). */
    protected abstract String getTitle();

    /** Inventory size — must be a multiple of 9, between 9 and 54. */
    protected abstract int getSize();

    /**
     * Fills the inventory with buttons and items.
     * Called on {@link #open} <em>after</em> the inventory is created.
     *
     * @param inv the freshly created inventory
     */
    protected abstract void populate(Inventory inv, GUIHolder holder);

    /**
     * Handles a click on a non-navigation item.
     * Navigation items (prev / next / close / refresh / back) are
     * intercepted by {@link GUIManager} before this method is called.
     *
     * @param player     the clicking player
     * @param slot       the slot index that was clicked
     * @param clicked    the itemstack at that slot
     * @param pdc        persistent data of the clicked item's meta
     * @param shiftClick whether shift was held
     */
    public void onClick(Player player, GUIHolder holder, int slot,
            ItemStack clicked, PersistentDataContainer pdc,
            boolean shiftClick) {
        // Default: no-op. Subclasses override as needed.
    }

    // ======================================================================
    // Open helpers (concrete)
    // ======================================================================

    /**
     * Opens the GUI for a player, creating a fresh {@link GUIHolder}.
     *
     * @param player  the target player
     * @param isAdmin whether the player has admin privileges in this GUI
     */
    public void open(Player player, boolean isAdmin) {
        GUIHolder holder = new GUIHolder(getHolderType(), player.getUniqueId(), isAdmin);
        openWith(player, holder);
    }

    /**
     * Opens the GUI with an existing holder (preserves page / filter state).
     */
    protected void openWith(Player player, GUIHolder holder) {
        Inventory inv = Bukkit.createInventory(holder, getSize(), getTitle());
        holder.setInventory(inv);
        populate(inv, holder);
        player.openInventory(inv);
    }

    /**
     * The {@link GUIHolder.GUIType} that identifies this menu.
     * Subclasses must override this if they handle click events.
     */
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.MAIN_MENU;
    }
}
