package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer

abstract class ChestMenu protected constructor(@JvmField protected val manager: GUIManager) {
    protected abstract fun getTitle(): String

    protected abstract fun getSize(): Int

    protected open fun populate(inv: Inventory, holder: GUIHolder) {
    }

    protected open fun populate(inv: Inventory, holder: GUIHolder, player: Player) {
        populate(inv, holder)
    }

    open fun onClick(
        player: Player,
        holder: GUIHolder,
        slot: Int,
        clicked: ItemStack,
        pdc: PersistentDataContainer,
        shiftClick: Boolean,
    ) {
    }

    open fun open(player: Player, isAdmin: Boolean) {
        val holder = GUIHolder(getHolderType(), player.uniqueId, isAdmin)
        val inv = Bukkit.createInventory(holder, getSize(), getTitle())
        holder.setInventory(inv)
        populate(inv, holder, player)
        player.openInventory(inv)
    }

    protected open fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.MAIN_MENU
}
