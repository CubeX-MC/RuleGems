package org.cubexmc.rulegems.gui

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.cubexmc.core.CubexText
import org.cubexmc.gui.ItemBuilder
import org.cubexmc.gui.TextStyler

/** RuleGems button layout and labels; item mechanics live in cubex-gui. */
object GuiItems {
    fun item(material: Material): ItemBuilder =
        ItemBuilder(material, 1, TextStyler { CubexText.translateColorCodes(it).orEmpty() })

    @JvmStatic
    fun filler(): ItemStack = item(Material.GRAY_STAINED_GLASS_PANE)
        .name(" ")
        .hideDetails()
        .build()

    @JvmStatic
    fun prevButton(currentPage: Int, key: NamespacedKey?, label: String, pageLabel: String): ItemStack =
        if (currentPage > 0) {
            item(Material.ARROW)
                .name("&a« $label")
                .addLore("&7$pageLabel $currentPage")
                .data(key, "prev")
                .build()
        } else {
            item(Material.GRAY_STAINED_GLASS_PANE)
                .name("&8« $label")
                .hideDetails()
                .build()
        }

    @JvmStatic
    fun nextButton(
        currentPage: Int,
        totalPages: Int,
        key: NamespacedKey?,
        label: String,
        pageLabel: String,
    ): ItemStack =
        if (currentPage < totalPages - 1) {
            item(Material.ARROW)
                .name("&a$label »")
                .addLore("&7$pageLabel ${currentPage + 2}")
                .data(key, "next")
                .build()
        } else {
            item(Material.GRAY_STAINED_GLASS_PANE)
                .name("&8$label »")
                .hideDetails()
                .build()
        }

    @JvmStatic
    fun pageInfo(currentPage: Int, totalPages: Int, totalItems: Int, pageLabel: String, totalLabel: String): ItemStack =
        item(Material.PAPER)
            .name("&e$pageLabel &f${currentPage + 1}&7/&f$totalPages")
            .addLore("&7$totalLabel: &f$totalItems")
            .hideDetails()
            .build()

    @JvmStatic
    fun closeButton(key: NamespacedKey?, label: String): ItemStack = item(Material.BARRIER)
        .name("&c$label")
        .data(key, "close")
        .build()

    @JvmStatic
    fun backButton(key: NamespacedKey?, label: String): ItemStack = item(Material.OAK_DOOR)
        .name("&e$label")
        .data(key, "back")
        .hideDetails()
        .build()

    @JvmStatic
    fun filterButton(key: NamespacedKey?, label: String, currentFilter: String, vararg options: String): ItemStack {
        val builder = item(Material.HOPPER)
            .name("&e$label")
            .addLore("&7$currentFilter")
            .addEmptyLore()
        for (option in options) {
            builder.addLore("&8• $option")
        }
        return builder.data(key, "filter").hideDetails().build()
    }

    @JvmStatic
    fun refreshButton(key: NamespacedKey?, label: String): ItemStack = item(Material.SUNFLOWER)
        .name("&a$label")
        .data(key, "refresh")
        .hideDetails()
        .build()
}
