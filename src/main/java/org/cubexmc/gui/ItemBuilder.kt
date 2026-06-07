package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.utils.ColorUtils
import java.util.ArrayList

class ItemBuilder {
    private val item: ItemStack
    private val meta: ItemMeta?

    constructor(material: Material) {
        item = ItemStack(material)
        meta = item.itemMeta
    }

    constructor(material: Material, amount: Int) {
        item = ItemStack(material, amount)
        meta = item.itemMeta
    }

    constructor(item: ItemStack) {
        this.item = item.clone()
        meta = this.item.itemMeta
    }

    fun name(name: String?): ItemBuilder {
        if (meta != null && name != null) {
            meta.setDisplayName(ColorUtils.translateColorCodes(name) ?: "")
        }
        return this
    }

    fun lore(vararg lore: String?): ItemBuilder {
        if (meta != null) {
            val coloredLore = ArrayList<String>()
            for (line in lore) {
                coloredLore.add(ColorUtils.translateColorCodes(line) ?: "")
            }
            meta.lore = coloredLore
        }
        return this
    }

    fun lore(lore: List<String>?): ItemBuilder {
        if (meta != null && lore != null) {
            val coloredLore = ArrayList<String>()
            for (line in lore) {
                coloredLore.add(ColorUtils.translateColorCodes(line) ?: "")
            }
            meta.lore = coloredLore
        }
        return this
    }

    fun addLore(line: String?): ItemBuilder {
        if (meta != null && line != null) {
            val lore = meta.lore ?: ArrayList()
            lore.add(ColorUtils.translateColorCodes(line) ?: "")
            meta.lore = lore
        }
        return this
    }

    fun addLore(vararg lines: String?): ItemBuilder {
        if (meta != null) {
            val lore = meta.lore ?: ArrayList()
            for (line in lines) {
                lore.add(ColorUtils.translateColorCodes(line) ?: "")
            }
            meta.lore = lore
        }
        return this
    }

    fun addEmptyLore(): ItemBuilder = addLore("")

    fun addSeparator(): ItemBuilder = addLore("&8─────────────────")

    fun glow(): ItemBuilder {
        if (meta != null) {
            applyGlowEffect(meta)
        }
        return this
    }

    fun enchant(enchantment: Enchantment, level: Int): ItemBuilder {
        meta?.addEnchant(enchantment, level, true)
        return this
    }

    fun hideAttributes(): ItemBuilder {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
            try {
                meta.addItemFlags(ItemFlag.valueOf("HIDE_POTION_EFFECTS"))
            } catch (e: Throwable) {
                Bukkit.getLogger().fine("HIDE_POTION_EFFECTS not available on this server version: " + e.message)
            }
        }
        return this
    }

    fun flags(vararg flags: ItemFlag): ItemBuilder {
        meta?.addItemFlags(*flags)
        return this
    }

    fun amount(amount: Int): ItemBuilder {
        item.amount = amount
        return this
    }

    fun data(key: NamespacedKey?, value: String?): ItemBuilder {
        if (meta != null && key != null && value != null) {
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, value)
        }
        return this
    }

    fun data(key: NamespacedKey?, value: Int): ItemBuilder {
        if (meta != null && key != null) {
            meta.persistentDataContainer.set(key, PersistentDataType.INTEGER, value)
        }
        return this
    }

    fun skullOwner(player: OfflinePlayer?): ItemBuilder {
        if (meta is SkullMeta && player != null) {
            meta.owningPlayer = player
        }
        return this
    }

    fun customModelData(data: Int): ItemBuilder {
        if (meta != null) {
            try {
                meta.setCustomModelData(data)
            } catch (_: Throwable) {
            }
        }
        return this
    }

    fun build(): ItemStack {
        if (meta != null) {
            item.itemMeta = meta
        }
        return item
    }

    companion object {
        @JvmStatic
        fun applyGlowEffect(meta: ItemMeta?) {
            if (meta == null) {
                return
            }
            try {
                val glintMethod = meta.javaClass.getMethod("setEnchantmentGlintOverride", Boolean::class.javaPrimitiveType)
                glintMethod.invoke(meta, true)
                return
            } catch (_: Throwable) {
            }
            try {
                val glintEnchant = Enchantment.getByKey(NamespacedKey.minecraft("luck_of_the_sea"))
                if (glintEnchant != null) {
                    meta.addEnchant(glintEnchant, 1, true)
                }
            } catch (e: Throwable) {
                Bukkit.getLogger().fine("Failed to apply enchantment glint fallback: " + e.message)
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        @JvmStatic
        fun filler(): ItemStack = ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
            .name(" ")
            .hideAttributes()
            .build()

        @JvmStatic
        fun prevButton(currentPage: Int, key: NamespacedKey?, label: String, pageLabel: String): ItemStack =
            if (currentPage > 0) {
                ItemBuilder(Material.ARROW)
                    .name("&a« $label")
                    .addLore("&7$pageLabel $currentPage")
                    .data(key, "prev")
                    .build()
            } else {
                ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name("&8« $label")
                    .hideAttributes()
                    .build()
            }

        @JvmStatic
        fun nextButton(currentPage: Int, totalPages: Int, key: NamespacedKey?, label: String, pageLabel: String): ItemStack =
            if (currentPage < totalPages - 1) {
                ItemBuilder(Material.ARROW)
                    .name("&a$label »")
                    .addLore("&7$pageLabel ${currentPage + 2}")
                    .data(key, "next")
                    .build()
            } else {
                ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name("&8$label »")
                    .hideAttributes()
                    .build()
            }

        @JvmStatic
        fun pageInfo(currentPage: Int, totalPages: Int, totalItems: Int, pageLabel: String, totalLabel: String): ItemStack =
            ItemBuilder(Material.PAPER)
                .name("&e$pageLabel &f${currentPage + 1}&7/&f$totalPages")
                .addLore("&7$totalLabel: &f$totalItems")
                .hideAttributes()
                .build()

        @JvmStatic
        fun closeButton(key: NamespacedKey?, label: String): ItemStack = ItemBuilder(Material.BARRIER)
            .name("&c$label")
            .data(key, "close")
            .build()

        @JvmStatic
        fun backButton(key: NamespacedKey?, label: String): ItemStack = ItemBuilder(Material.OAK_DOOR)
            .name("&e$label")
            .data(key, "back")
            .hideAttributes()
            .build()

        @JvmStatic
        fun filterButton(key: NamespacedKey?, label: String, currentFilter: String, vararg options: String): ItemStack {
            val builder = ItemBuilder(Material.HOPPER)
                .name("&e$label")
                .addLore("&7$currentFilter")
                .addEmptyLore()
            for (option in options) {
                builder.addLore("&8• $option")
            }
            return builder.data(key, "filter").hideAttributes().build()
        }

        @JvmStatic
        fun refreshButton(key: NamespacedKey?, label: String): ItemStack = ItemBuilder(Material.SUNFLOWER)
            .name("&a$label")
            .data(key, "refresh")
            .hideAttributes()
            .build()
    }
}
