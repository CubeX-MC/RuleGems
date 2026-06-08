package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.utils.ColorUtils
import java.util.Locale
import java.util.UUID

class GUIManager(
    val plugin: RuleGems,
    gemManager: GemManager,
    private val lang: LanguageManager,
) : Listener {
    val gemIdKey: NamespacedKey = NamespacedKey(plugin, "gem_id")
    val navActionKey: NamespacedKey = NamespacedKey(plugin, "nav_action")
    val playerUuidKey: NamespacedKey = NamespacedKey(plugin, "player_uuid")
    val appointKeyKey: NamespacedKey = NamespacedKey(plugin, "appoint_key")

    private val mainMenuGUI = MainMenuGUI(this, gemManager, lang)
    private val gemsGUI = GemsGUI(this, gemManager, lang)
    private val rulersGUI = RulersGUI(this, gemManager, lang)
    private val rulerAppointeesGUI = RulerAppointeesGUI(this, gemManager, lang, plugin)
    private val profileGUI = ProfileGUI(this, gemManager, lang, plugin)
    private val cabinetGUI = CabinetGUI(this, gemManager, lang, plugin)
    private val cabinetMembersGUI = CabinetMembersGUI(this, gemManager, lang, plugin)
    private val powerTogglesGUI = PowerTogglesGUI(this, gemManager, lang)

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    fun rawMsg(path: String): String = lang.getMessage("gui.$path")

    fun openMainMenu(player: Player, isAdmin: Boolean) {
        mainMenuGUI.open(player, isAdmin)
    }

    fun openGemsGUI(player: Player, isAdmin: Boolean) {
        openGemsGUI(player, isAdmin, 0, null)
    }

    fun openGemsGUI(player: Player, isAdmin: Boolean, page: Int, filter: String?) {
        if (!canOpenGems(player)) {
            lang.sendMessage(player, "command.no_permission")
            return
        }
        gemsGUI.open(player, isAdmin, page, filter)
    }

    fun openRulersGUI(player: Player, isAdmin: Boolean) {
        openRulersGUI(player, isAdmin, 0)
    }

    fun openRulersGUI(player: Player, isAdmin: Boolean, page: Int) {
        if (!canOpenRulers(player)) {
            lang.sendMessage(player, "command.no_permission")
            return
        }
        rulersGUI.open(player, isAdmin, page)
    }

    fun openRulerAppointeesGUI(player: Player, rulerUuid: UUID, isAdmin: Boolean) {
        openRulerAppointeesGUI(player, rulerUuid, isAdmin, 0)
    }

    fun openRulerAppointeesGUI(player: Player, rulerUuid: UUID, isAdmin: Boolean, page: Int) {
        rulerAppointeesGUI.open(player, rulerUuid, isAdmin, page)
    }

    fun openProfileGUI(player: Player) {
        openProfileGUI(player, 0)
    }

    fun openProfileGUI(player: Player, page: Int) {
        profileGUI.open(player, page)
    }

    fun openCabinetGUI(player: Player) {
        openCabinetGUI(player, 0)
    }

    fun openCabinetGUI(player: Player, page: Int) {
        cabinetGUI.open(player, page)
    }

    fun openCabinetMembersGUI(player: Player, appointKey: String) {
        openCabinetMembersGUI(player, appointKey, 0)
    }

    fun openCabinetMembersGUI(player: Player, appointKey: String, page: Int) {
        cabinetMembersGUI.open(player, appointKey, page)
    }

    fun openPowerTogglesGUI(player: Player, isAdmin: Boolean) {
        openPowerTogglesGUI(player, isAdmin, 0)
    }

    fun openPowerTogglesGUI(player: Player, isAdmin: Boolean, page: Int) {
        powerTogglesGUI.open(player, page)
    }

    fun openPowerTogglesGUI(player: Player) {
        openPowerTogglesGUI(player, player.hasPermission("rulegems.admin"), 0)
    }

    fun canOpenCabinet(player: Player?): Boolean {
        val appointFeature = plugin.featureManager?.appointFeature
        if (player == null || appointFeature == null || !appointFeature.isEnabled) {
            return false
        }
        if (player.hasPermission("rulegems.admin")) {
            return true
        }
        return appointFeature.getAppointDefinitions().keys.any { key ->
            player.hasPermission("rulegems.appoint.$key") ||
                player.hasPermission("rulegems.appoint.${key.lowercase(Locale.ROOT)}")
        }
    }

    fun canOpenGems(player: Player?): Boolean = player != null &&
        (player.hasPermission("rulegems.admin") || player.hasPermission("rulegems.gems"))

    fun canOpenRulers(player: Player?): Boolean = player != null &&
        (player.hasPermission("rulegems.admin") || player.hasPermission("rulegems.rulers"))

    fun fillDecoration(gui: Inventory) {
        val filler = ItemBuilder.filler()
        for (slot in 36..44) {
            gui.setItem(slot, filler)
        }
        gui.setItem(48, filler)
        gui.setItem(50, filler)
    }

    fun addControlBar(gui: Inventory, currentPage: Int, totalPages: Int, totalItems: Int, showFilter: Boolean, showBack: Boolean) {
        gui.setItem(SLOT_PREV, ItemBuilder.prevButton(currentPage, navActionKey, rawMsg("control.prev"), rawMsg("control.page")))
        gui.setItem(SLOT_BACK, if (showBack) ItemBuilder.backButton(navActionKey, rawMsg("control.back")) else ItemBuilder.filler())
        if (showFilter) {
            gui.setItem(
                SLOT_FILTER,
                ItemBuilder(Material.HOPPER)
                    .name("&e" + rawMsg("control.filter"))
                    .addLore("&7" + rawMsg("control.filter_hint"))
                    .data(navActionKey, "filter")
                    .hideAttributes()
                    .build(),
            )
        } else {
            gui.setItem(SLOT_FILTER, ItemBuilder.filler())
        }
        gui.setItem(SLOT_INFO, ItemBuilder.pageInfo(currentPage, totalPages, totalItems, rawMsg("control.page"), rawMsg("control.total")))
        gui.setItem(SLOT_REFRESH, ItemBuilder.refreshButton(navActionKey, rawMsg("control.refresh")))
        gui.setItem(SLOT_CLOSE, ItemBuilder.closeButton(navActionKey, rawMsg("control.close")))
        gui.setItem(SLOT_NEXT, ItemBuilder.nextButton(currentPage, totalPages, navActionKey, rawMsg("control.next"), rawMsg("control.page")))
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = GUIHolder.getHolder(event.inventory) ?: return
        event.isCancelled = true

        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR || clicked.type == Material.GRAY_STAINED_GLASS_PANE) {
            return
        }

        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val navAction = pdc.get(navActionKey, PersistentDataType.STRING)
        if (navAction != null) {
            handleNavigation(player, holder, navAction)
            return
        }

        getMenuForHolder(holder)?.onClick(player, holder, event.slot, clicked, pdc, event.isShiftClick)
    }

    private fun getMenuForHolder(holder: GUIHolder): ChestMenu? = when (holder.type) {
        GUIHolder.GUIType.GEMS -> gemsGUI
        GUIHolder.GUIType.RULERS -> rulersGUI
        GUIHolder.GUIType.RULER_APPOINTEES -> rulerAppointeesGUI
        GUIHolder.GUIType.PROFILE -> profileGUI
        GUIHolder.GUIType.CABINET -> cabinetGUI
        GUIHolder.GUIType.CABINET_MEMBERS -> cabinetMembersGUI
        GUIHolder.GUIType.POWER_TOGGLES -> powerTogglesGUI
        GUIHolder.GUIType.MAIN_MENU -> mainMenuGUI
    }

    private fun handleNavigation(player: Player, holder: GUIHolder, action: String) {
        when (action) {
            "prev" -> reopenCurrentGUI(player, holder, maxOf(0, holder.page - 1))
            "next" -> reopenCurrentGUI(player, holder, maxOf(0, holder.page + 1))
            "close" -> player.closeInventory()
            "refresh" -> reopenCurrentGUI(player, holder, holder.page)
            "back" -> openBackDestination(player, holder)
            "filter" -> cycleGemFilter(player, holder)
            "open_gems" -> openGemsGUI(player, holder.isAdmin)
            "open_rulers" -> openRulersGUI(player, holder.isAdmin)
            "show_redeem_help" -> sendRedeemGuide(player)
            "show_navigate_help" -> sendNavigateGuide(player)
            "open_profile" -> openProfileGUI(player)
            "open_cabinet" -> openCabinetFromMenu(player)
        }
    }

    private fun openCabinetFromMenu(player: Player) {
        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature == null || !appointFeature.isEnabled) {
            lang.sendMessage(player, "command.appoint.disabled")
            return
        }
        if (!canOpenCabinet(player)) {
            lang.sendMessage(player, "command.no_permission")
            return
        }
        openCabinetGUI(player)
    }

    private fun cycleGemFilter(player: Player, holder: GUIHolder) {
        if (holder.type != GUIHolder.GUIType.GEMS) {
            return
        }
        val current = holder.getContext()
        val next = when {
            current.isNullOrEmpty() -> "held"
            current.equals("held", ignoreCase = true) -> "placed"
            else -> null
        }
        openGemsGUI(player, holder.isAdmin, 0, next)
    }

    private fun sendRedeemGuide(player: Player) {
        lang.sendMessage(player, "command.help.section_player")
        if (plugin.gameplayConfig.isRedeemEnabled && player.hasPermission("rulegems.redeem")) {
            lang.sendMessage(player, "command.help.redeem")
        }
        if (plugin.gameplayConfig.isHoldToRedeemEnabled &&
            plugin.gameplayConfig.isRedeemEnabled &&
            player.hasPermission("rulegems.redeem")
        ) {
            lang.sendMessage(
                player,
                if (plugin.gameplayConfig.isSneakToRedeem) "command.help.hold_redeem_sneak" else "command.help.hold_redeem_normal",
            )
        }
        if (plugin.gameplayConfig.isPlaceRedeemEnabled) {
            lang.sendMessage(player, "command.help.place_redeem")
        }
        if (plugin.gameplayConfig.isFullSetGrantsAllEnabled && player.hasPermission("rulegems.redeemall")) {
            lang.sendMessage(player, "command.help.redeemall")
        }
    }

    private fun sendNavigateGuide(player: Player) {
        val navigator = plugin.featureManager?.getNavigator()
        if (navigator != null && navigator.isEnabled && player.hasPermission("rulegems.navigate")) {
            lang.sendMessage(player, "command.help.navigate")
        } else {
            player.sendMessage(ColorUtils.translateColorCodes(rawMsg("menu.navigate_disabled_chat")) ?: "")
        }
    }

    private fun openBackDestination(player: Player, holder: GUIHolder) {
        when (holder.type) {
            GUIHolder.GUIType.RULER_APPOINTEES -> openRulersGUI(player, holder.isAdmin)
            GUIHolder.GUIType.CABINET_MEMBERS -> openCabinetGUI(player)
            GUIHolder.GUIType.POWER_TOGGLES -> openProfileGUI(player)
            else -> openMainMenu(player, holder.isAdmin)
        }
    }

    private fun reopenCurrentGUI(player: Player, holder: GUIHolder, page: Int) {
        when (holder.type) {
            GUIHolder.GUIType.MAIN_MENU -> openMainMenu(player, holder.isAdmin)
            GUIHolder.GUIType.GEMS -> openGemsGUI(player, holder.isAdmin, page, holder.getContext())
            GUIHolder.GUIType.RULERS -> openRulersGUI(player, holder.isAdmin, page)
            GUIHolder.GUIType.RULER_APPOINTEES -> {
                val rulerUuid = parseContextUuid(holder, "GUI reopen")
                if (rulerUuid != null) {
                    openRulerAppointeesGUI(player, rulerUuid, holder.isAdmin, page)
                }
            }
            GUIHolder.GUIType.PROFILE -> openProfileGUI(player, page)
            GUIHolder.GUIType.CABINET -> openCabinetGUI(player, page)
            GUIHolder.GUIType.CABINET_MEMBERS -> {
                val context = holder.getContext()
                if (context != null) {
                    openCabinetMembersGUI(player, context, page)
                }
            }
            GUIHolder.GUIType.POWER_TOGGLES -> openPowerTogglesGUI(player, holder.isAdmin, page)
        }
    }

    private fun parseContextUuid(holder: GUIHolder, source: String): UUID? {
        val context = holder.getContext()
        if (context.isNullOrEmpty()) {
            return null
        }
        return try {
            UUID.fromString(context)
        } catch (e: Exception) {
            plugin.logger.fine("Failed to parse holder UUID context during $source: ${e.message}")
            null
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (GUIHolder.getHolder(event.inventory) != null) {
            event.isCancelled = true
        }
    }

    companion object {
        const val GUI_SIZE: Int = 54
        const val CONTENT_ROWS: Int = 4
        const val ITEMS_PER_PAGE: Int = 36
        const val SLOT_PREV: Int = 45
        const val SLOT_BACK: Int = 46
        const val SLOT_FILTER: Int = 47
        const val SLOT_INFO: Int = 49
        const val SLOT_REFRESH: Int = 51
        const val SLOT_CLOSE: Int = 52
        const val SLOT_NEXT: Int = 53
    }
}
