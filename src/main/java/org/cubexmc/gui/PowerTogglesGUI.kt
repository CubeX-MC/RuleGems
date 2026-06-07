package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.GemPermissionManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import java.util.Collections

class PowerTogglesGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("power_toggles.title")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.POWER_TOGGLES

    fun open(player: Player, page: Int) {
        val permManager = gemManager.permissionManager
        val rulerKeys = gemManager.currentRulers.getOrDefault(player.uniqueId, Collections.emptySet())

        val ownedGems = rulerKeys
            .filter { key -> key != "ALL" }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        val totalItems = ownedGems.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / ITEMS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        var title = msg("power_toggles.title")
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.POWER_TOGGLES, player.uniqueId, player.hasPermission("rulegems.admin"), currentPage)
        val gui: Inventory = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        gui.setItem(
            45,
            ItemBuilder(Material.ARROW)
                .name("&a" + rawMsg("power_toggles.back_to_profile"))
                .build(),
        )

        if (ownedGems.isEmpty()) {
            gui.setItem(
                22,
                ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("power_toggles.no_gems"))
                    .addLore("&7" + rawMsg("power_toggles.no_gems_lore"))
                    .build(),
            )
        } else {
            val startIndex = currentPage * ITEMS_PER_PAGE
            val endIndex = minOf(startIndex + ITEMS_PER_PAGE, ownedGems.size)
            for (i in startIndex until endIndex) {
                gui.setItem(ITEM_SLOTS[i - startIndex], createGemToggleItem(player, ownedGems[i], permManager))
            }
        }

        player.openInventory(gui)
    }

    private fun createGemToggleItem(player: Player, gemKey: String, permManager: GemPermissionManager): ItemStack {
        val definition: GemDefinition? = gemManager.findGemDefinitionByKey(gemKey)
        val isOff = permManager.isGemToggledOff(player.uniqueId, gemKey)

        val displayName = definition?.displayName ?: gemKey
        val material = definition?.material ?: Material.EMERALD

        val builder = ItemBuilder(material)
            .name(ColorUtils.translateColorCodes("&f$displayName") ?: "")
            .hideAttributes()
            .addEmptyLore()

        if (isOff) {
            builder.addLore("&c" + rawMsg("power_toggles.status_off"))
            builder.addLore("&7" + rawMsg("power_toggles.click_to_enable"))
        } else {
            builder.glow()
            builder.addLore("&a" + rawMsg("power_toggles.status_on"))
            builder.addLore("&7" + rawMsg("power_toggles.click_to_disable"))
        }

        return builder.build()
    }

    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")

    override fun onClick(
        player: Player,
        holder: GUIHolder,
        slot: Int,
        clicked: ItemStack,
        pdc: PersistentDataContainer,
        shiftClick: Boolean,
    ) {
        if (slot == 45) {
            manager.openProfileGUI(player)
            return
        }

        var index = -1
        for (i in ITEM_SLOTS.indices) {
            if (ITEM_SLOTS[i] == slot) {
                index = i
                break
            }
        }
        if (index == -1) {
            return
        }

        val rulerKeys = gemManager.currentRulers.getOrDefault(player.uniqueId, Collections.emptySet())
        val ownedGems = rulerKeys
            .filter { key -> key != "ALL" }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        val itemIndex = holder.page * ITEMS_PER_PAGE + index
        if (itemIndex >= 0 && itemIndex < ownedGems.size) {
            val gemKey = ownedGems[itemIndex]
            val permManager = gemManager.permissionManager
            val currentlyOff = permManager.isGemToggledOff(player.uniqueId, gemKey)

            permManager.toggleGemPower(player, gemKey, currentlyOff)

            if (currentlyOff) {
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f)
            } else {
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f)
            }

            open(player, holder.page)
        }
    }

    companion object {
        private const val ITEMS_PER_PAGE = 27
        private val ITEM_SLOTS = intArrayOf(
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
        )
    }
}
