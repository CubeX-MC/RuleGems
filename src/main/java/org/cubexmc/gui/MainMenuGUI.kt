package org.cubexmc.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.utils.ColorUtils

class MainMenuGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
) : ChestMenu(guiManager) {
    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")

    override fun getTitle(): String = msg("menu.title")

    override fun getSize(): Int = GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.MAIN_MENU

    override fun populate(gui: Inventory, holder: GUIHolder, player: Player) {
        val filler = ItemBuilder.filler()
        for (i in 0 until GUI_SIZE) {
            gui.setItem(i, filler)
        }

        val isAdmin = player.hasPermission("rulegems.admin")
        if (manager.canOpenGems(player)) {
            gui.setItem(SLOT_GEMS, createGemsButton(gemManager.allGemUuids.size, isAdmin))
        }
        if (manager.canOpenRulers(player)) {
            gui.setItem(SLOT_RULERS, createRulersButton(gemManager.currentRulers.size, isAdmin))
        }
        gui.setItem(SLOT_CLOSE, ItemBuilder.closeButton(manager.navActionKey, rawMsg("control.close")))
    }

    private fun createGemsButton(gemCount: Int, isAdmin: Boolean): ItemStack {
        val builder = ItemBuilder(Material.DIAMOND)
            .name("&b" + rawMsg("menu.gems_title"))
            .data(manager.navActionKey, "open_gems")
            .glow()

        builder.addEmptyLore()
            .addLore("&7" + rawMsg("menu.gems_desc"))
            .addEmptyLore()
            .addLore("&e▸ " + rawMsg("menu.gem_count") + ": &f" + gemCount)

        if (isAdmin) {
            builder.addEmptyLore()
                .addLore("&8" + rawMsg("menu.admin_view"))
        }

        builder.addEmptyLore()
            .addLore("&a» " + rawMsg("menu.click_to_open"))

        return builder.build()
    }

    private fun createRulersButton(rulerCount: Int, isAdmin: Boolean): ItemStack {
        val builder = ItemBuilder(Material.GOLDEN_HELMET)
            .name("&6" + rawMsg("menu.rulers_title"))
            .data(manager.navActionKey, "open_rulers")
            .hideAttributes()
            .glow()

        builder.addEmptyLore()
            .addLore("&7" + rawMsg("menu.rulers_desc"))
            .addEmptyLore()
            .addLore("&e▸ " + rawMsg("menu.ruler_count") + ": &f" + rulerCount)

        if (isAdmin) {
            builder.addEmptyLore()
                .addLore("&8" + rawMsg("menu.admin_view"))
        }

        builder.addEmptyLore()
            .addLore("&a» " + rawMsg("menu.click_to_open"))

        return builder.build()
    }

    companion object {
        private const val GUI_SIZE = 27
        private const val SLOT_GEMS = 11
        private const val SLOT_RULERS = 15
        private const val SLOT_CLOSE = 22
    }
}
