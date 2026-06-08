package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.features.revoke.RevokeFeature
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.util.Locale
import java.util.UUID

class RulersGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
) : ChestMenu(guiManager) {
    private val plugin: RuleGems
        get() = manager.plugin

    override fun getTitle(): String = msg("rulers.title_player")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.RULERS

    override fun onClick(
        player: Player,
        holder: GUIHolder,
        slot: Int,
        clicked: ItemStack,
        pdc: PersistentDataContainer,
        shiftClick: Boolean,
    ) {
        if (clicked.type != Material.PLAYER_HEAD) {
            return
        }
        val playerUuidStr = pdc.get(manager.playerUuidKey, PersistentDataType.STRING) ?: return
        try {
            val targetUuid = UUID.fromString(playerUuidStr)
            if (!shiftClick) {
                manager.openRulerAppointeesGUI(player, targetUuid, holder.isAdmin)
                return
            }
            if (holder.isAdmin) {
                val target = Bukkit.getPlayer(targetUuid)
                if (target != null && target.isOnline) {
                    player.closeInventory()
                    SchedulerUtil.safeTeleport(manager.plugin, player, target.location)
                    player.sendMessage(msg("rulers.teleported_to_player").replace("%player%", target.name))
                }
            }
        } catch (e: Exception) {
            manager.plugin.logger.fine("RulersGUI click error: " + e.message)
        }
    }

    fun open(player: Player, isAdmin: Boolean, page: Int) {
        val rulers = gemManager.currentRulers
        val rulerList = ArrayList(rulers.entries)

        val totalItems = rulerList.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / GUIManager.ITEMS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        var title = if (isAdmin) msg("rulers.title_admin") else msg("rulers.title_player")
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.RULERS, player.uniqueId, isAdmin, currentPage)
        val gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        gui.setItem(48, createProfileButton())
        gui.setItem(50, createCabinetButton(holder, player))

        if (rulers.isEmpty()) {
            gui.setItem(13, createNoRulersItem())
        } else {
            val startIndex = currentPage * GUIManager.ITEMS_PER_PAGE
            val endIndex = minOf(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems)
            for (i in startIndex until endIndex) {
                val slot = i - startIndex
                val entry = rulerList[i]
                gui.setItem(slot, createRulerItem(player, entry.key, entry.value, isAdmin))
            }
        }

        player.openInventory(gui)
    }

    private fun createNoRulersItem(): ItemStack = ItemBuilder(Material.BARRIER)
        .name("&c" + rawMsg("rulers.no_rulers"))
        .addLore("&7" + rawMsg("rulers.no_rulers_lore"))
        .build()

    private fun createRulerItem(viewer: Player, playerUuid: UUID, gemKeys: Set<String>, isAdmin: Boolean): ItemStack {
        val ruler = Bukkit.getPlayer(playerUuid)
        val playerName = gemManager.getCachedPlayerName(playerUuid)
        val isOnline = ruler != null && ruler.isOnline

        val isSupreme = gemKeys.contains("ALL")
        val gemCount = gemKeys.count { key -> key != "ALL" }

        val nameColor = when {
            isSupreme -> ChatColor.GOLD
            gemCount >= 3 -> ChatColor.LIGHT_PURPLE
            gemCount >= 2 -> ChatColor.AQUA
            else -> ChatColor.GREEN
        }

        val builder = ItemBuilder(Material.PLAYER_HEAD)
            .name("$nameColor✦ $playerName ✦")
            .data(manager.playerUuidKey, playerUuid.toString())

        if (ruler != null) {
            builder.skullOwner(ruler)
        } else {
            try {
                builder.skullOwner(Bukkit.getOfflinePlayer(playerUuid))
            } catch (e: Exception) {
                Bukkit.getLogger().fine("Failed to set skull owner for offline ruler: " + e.message)
            }
        }

        builder.addEmptyLore()

        if (isSupreme) {
            builder.addLore("&6★ " + rawMsg("rulers.supreme_ruler") + " ★")
                .addLore("&7" + rawMsg("rulers.supreme_ruler_desc"))
                .addEmptyLore()
        }

        builder.addLore("&e▸ " + rawMsg("rulers.holding_gems") + " &7($gemCount)")

        for (key in gemKeys) {
            if (key == "ALL") {
                continue
            }
            val definition: GemDefinition? = gemManager.findGemDefinitionByKey(key)
            val gemName = if (definition?.displayName != null) {
                ColorUtils.translateColorCodes(definition.displayName)
            } else {
                key
            }
            builder.addLore("&8  • $gemName")
        }

        builder.addEmptyLore()
        if (isOnline) {
            builder.addLore("&a● " + rawMsg("rulers.status_online"))
        } else {
            builder.addLore("&c● " + rawMsg("rulers.status_offline"))
        }

        val appointeeCount = getAppointeeCount(playerUuid)
        if (appointeeCount > 0) {
            builder.addEmptyLore()
            builder.addLore("&d▸ " + rawMsg("rulers.appointee_count").replace("%count%", appointeeCount.toString()))
        }
        val revokablePowers = getRevokablePowers(viewer, playerUuid, gemKeys)
        if (revokablePowers.isNotEmpty()) {
            builder.addEmptyLore()
            builder.addLore("&c▸ " + rawMsg("rulers.revokable_powers") + " &7(" + revokablePowers.size + ")")
            for (power in revokablePowers) {
                builder.addLore("&8  • $power")
            }
            builder.addLore("&7" + rawMsg("rulers.revoke_command_hint"))
        }

        if (isAdmin) {
            builder.addEmptyLore()
                .addLore("&8UUID: &7" + playerUuid.toString().substring(0, 8) + "...")
        }

        builder.addEmptyLore()
        builder.addLore("&e» " + rawMsg("rulers.click_view_appointees"))
        if (isAdmin && isOnline) {
            builder.addLore("&a» " + rawMsg("rulers.shift_click_tp"))
        }

        return builder.build()
    }

    private fun getAppointeeCount(rulerUuid: UUID): Int {
        val featureManager = plugin.featureManager ?: return 0
        val appointFeature: AppointFeature = featureManager.appointFeature ?: return 0
        if (!appointFeature.isEnabled) {
            return 0
        }
        return appointFeature.getAppointmentsByAppointer(rulerUuid).size
    }

    private fun getRevokablePowers(viewer: Player, targetUuid: UUID, gemKeys: Set<String>): List<String> {
        val featureManager = plugin.featureManager ?: return emptyList()
        val revokeFeature: RevokeFeature = featureManager.revokeFeature ?: return emptyList()
        return revokeFeature.getRevokablePowers(viewer, targetUuid, gemKeys)
    }

    private fun createProfileButton(): ItemStack = ItemBuilder(Material.BOOK)
        .name("&b" + rawMsg("menu.profile_title"))
        .addEmptyLore()
        .addLore("&7" + rawMsg("menu.profile_desc"))
        .addEmptyLore()
        .addLore("&a» " + rawMsg("menu.click_to_open"))
        .data(manager.navActionKey, "open_profile")
        .hideAttributes()
        .build()

    private fun createCabinetButton(holder: GUIHolder, viewer: Player): ItemStack {
        val featureManager = manager.plugin.featureManager
        val appointFeature = featureManager?.appointFeature
        val appointEnabled = appointFeature != null && appointFeature.isEnabled
        var canManageAppointments = appointEnabled && holder.isAdmin
        if (!canManageAppointments && appointEnabled && appointFeature != null) {
            canManageAppointments = appointFeature.getAppointDefinitions().keys.any { key ->
                viewer.hasPermission("rulegems.appoint.$key") ||
                    viewer.hasPermission("rulegems.appoint.${key.lowercase(Locale.ROOT)}")
            }
        }

        val builder = ItemBuilder(Material.WRITABLE_BOOK)
            .name("&d" + rawMsg("menu.cabinet_title"))
            .addEmptyLore()
            .addLore("&7" + rawMsg("menu.cabinet_desc"))
            .addEmptyLore()

        if (canManageAppointments) {
            builder.addLore("&a» " + rawMsg("menu.click_to_open"))
                .data(manager.navActionKey, "open_cabinet")
                .glow()
        } else {
            builder.addLore("&8" + rawMsg("menu.info_only"))
                .addLore("&7" + rawMsg("menu.cabinet_unavailable"))
                .hideAttributes()
        }
        return builder.build()
    }

    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")
}
