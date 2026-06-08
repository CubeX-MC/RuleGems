package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.features.appoint.Appointment
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.utils.ColorUtils
import java.util.Collections
import java.util.Locale

class CabinetGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
    private val plugin: RuleGems,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("cabinet.title")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.CABINET

    override fun onClick(
        player: Player,
        holder: GUIHolder,
        slot: Int,
        clicked: ItemStack,
        pdc: PersistentDataContainer,
        shiftClick: Boolean,
    ) {
        val appointKey = pdc.get(manager.appointKeyKey, PersistentDataType.STRING)
        if (appointKey.isNullOrBlank()) {
            return
        }
        manager.openCabinetMembersGUI(player, appointKey)
    }

    fun open(player: Player, page: Int) {
        val appointFeature = getAppointFeature()
        val appointKeys = getAvailableAppointKeys(player, appointFeature)

        val totalItems = appointKeys.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / GUIManager.ITEMS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        var title = msg("cabinet.title")
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.CABINET, player.uniqueId, player.hasPermission("rulegems.admin"), currentPage)
        val gui: Inventory = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        if (appointKeys.isEmpty()) {
            gui.setItem(
                13,
                ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("cabinet.no_roles"))
                    .addLore("&7" + rawMsg("cabinet.no_roles_lore"))
                    .build(),
            )
        } else {
            val startIndex = currentPage * GUIManager.ITEMS_PER_PAGE
            val endIndex = minOf(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems)
            for (i in startIndex until endIndex) {
                val appointKey = appointKeys[i]
                val definition = appointFeature?.getAppointDefinition(appointKey)
                gui.setItem(i - startIndex, createRoleItem(player, appointKey, definition, appointFeature))
            }
        }

        player.openInventory(gui)
    }

    private fun createRoleItem(
        player: Player,
        appointKey: String,
        definition: AppointDefinition?,
        appointFeature: AppointFeature?,
    ): ItemStack {
        val displayName = if (definition != null) {
            ColorUtils.translateColorCodes(definition.displayName) ?: ""
        } else {
            appointKey
        }
        val current = appointFeature?.getAppointmentCountBy(player.uniqueId, appointKey) ?: 0
        val max = definition?.maxAppointments ?: -1
        val appointments: List<Appointment> = appointFeature?.getAppointmentsByAppointer(player.uniqueId, appointKey)
            ?: Collections.emptyList()

        val builder = ItemBuilder(Material.WRITABLE_BOOK)
            .name(displayName)
            .data(manager.appointKeyKey, appointKey)
            .hideAttributes()

        builder.addEmptyLore()
            .addLore("&e▸ " + rawMsg("cabinet.current_count") + ": &f" + current + "/" + formatMax(max))

        if (definition != null && !definition.description.isNullOrBlank()) {
            builder.addLore("&7" + definition.description)
        }

        builder.addEmptyLore()
            .addLore("&b▸ " + rawMsg("cabinet.current_members"))
        if (appointments.isEmpty()) {
            builder.addLore("&7" + rawMsg("cabinet.none"))
        } else {
            var shown = 0
            for (appointment in appointments) {
                builder.addLore("&f- &e" + gemManager.getCachedPlayerName(appointment.appointeeUuid))
                shown++
                if (shown >= 3) {
                    break
                }
            }
            if (appointments.size > 3) {
                builder.addLore(
                    "&8" + rawMsg("cabinet.more_members")
                        .replace("%count%", (appointments.size - 3).toString()),
                )
            }
        }

        builder.addEmptyLore()
            .addLore("&a» " + rawMsg("cabinet.click_manage"))

        if (current > 0 || max != 0) {
            builder.glow()
        }
        return builder.build()
    }

    private fun getAvailableAppointKeys(player: Player, appointFeature: AppointFeature?): List<String> {
        if (appointFeature == null || !appointFeature.isEnabled) {
            return emptyList()
        }
        val keys = ArrayList<String>()
        for (key in appointFeature.getAppointDefinitions().keys) {
            if (player.hasPermission("rulegems.admin") ||
                player.hasPermission("rulegems.appoint.$key") ||
                player.hasPermission("rulegems.appoint." + key.lowercase(Locale.ROOT))
            ) {
                keys.add(key)
            }
        }
        keys.sortWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { key ->
                val definition = appointFeature.getAppointDefinition(key)
                if (definition != null) {
                    ChatColor.stripColor(ColorUtils.translateColorCodes(definition.displayName) ?: "") ?: key
                } else {
                    key
                }
            },
        )
        return keys
    }

    private fun formatMax(max: Int): String = if (max <= 0) rawMsg("cabinet.unlimited") else max.toString()

    private fun getAppointFeature(): AppointFeature? = plugin.featureManager?.appointFeature

    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")
}
