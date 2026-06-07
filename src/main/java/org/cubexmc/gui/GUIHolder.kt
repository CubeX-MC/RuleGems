package org.cubexmc.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

class GUIHolder @JvmOverloads constructor(
    val type: GUIType,
    val viewerId: UUID,
    val isAdmin: Boolean,
    val page: Int = 0,
    private val filter: String? = null,
) : InventoryHolder {
    private var heldInventory: Inventory? = null

    enum class GUIType {
        MAIN_MENU,
        GEMS,
        RULERS,
        PROFILE,
        CABINET,
        CABINET_MEMBERS,
        RULER_APPOINTEES,
        POWER_TOGGLES,
    }

    override fun getInventory(): Inventory = heldInventory
        ?: error("Inventory has not been assigned to this GUI holder.")

    fun setInventory(inventory: Inventory?) {
        heldInventory = inventory
    }

    fun getContext(): String? = filter

    fun getFilter(): String? = filter

    companion object {
        @JvmStatic
        fun getHolder(inventory: Inventory?): GUIHolder? {
            if (inventory == null) {
                return null
            }
            val holder = inventory.holder
            return if (holder is GUIHolder) holder else null
        }
    }
}
