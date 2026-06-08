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
import org.cubexmc.features.appoint.Appointment
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.utils.ColorUtils
import java.util.UUID

class CabinetMembersGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
    private val plugin: RuleGems,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("cabinet_members.title")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.CABINET_MEMBERS

    override fun onClick(
        player: Player,
        holder: GUIHolder,
        slot: Int,
        clicked: ItemStack,
        pdc: PersistentDataContainer,
        shiftClick: Boolean,
    ) {
        val appointKey = holder.getContext()
        val targetUuidText = pdc.get(manager.playerUuidKey, PersistentDataType.STRING)
        if (appointKey == null || targetUuidText == null) {
            return
        }

        val appointFeature = getAppointFeature()
        if (appointFeature == null || !appointFeature.isEnabled) {
            lang.sendMessage(player, "command.appoint.disabled")
            return
        }

        val targetUuid = try {
            UUID.fromString(targetUuidText)
        } catch (_: IllegalArgumentException) {
            return
        }

        val currentAppointments = appointFeature.getAppointmentsByAppointer(player.uniqueId, appointKey)
        val existing = currentAppointments.firstOrNull { appointment -> appointment.appointeeUuid == targetUuid }

        if (existing != null) {
            val success = appointFeature.dismiss(player, targetUuid, appointKey)
            if (success) {
                val placeholders = HashMap<String, String>()
                placeholders["player"] = gemManager.getCachedPlayerName(targetUuid)
                val definition = appointFeature.getAppointDefinition(appointKey)
                placeholders["perm_set"] = if (definition != null) {
                    ColorUtils.translateColorCodes(definition.displayName) ?: ""
                } else {
                    appointKey
                }
                lang.sendMessage(player, "command.dismiss.success", placeholders)
            } else {
                lang.sendMessage(player, "command.dismiss.failed")
            }
            manager.openCabinetMembersGUI(player, appointKey, holder.page)
            return
        }

        val target = Bukkit.getPlayer(targetUuid)
        if (target == null || !target.isOnline) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = gemManager.getCachedPlayerName(targetUuid)
            lang.sendMessage(player, "command.appoint.player_not_found", placeholders)
            manager.openCabinetMembersGUI(player, appointKey, holder.page)
            return
        }
        if (target == player) {
            lang.sendMessage(player, "command.appoint.cannot_self")
            manager.openCabinetMembersGUI(player, appointKey, holder.page)
            return
        }

        val definition = appointFeature.getAppointDefinition(appointKey)
        if (definition != null &&
            definition.maxAppointments > 0 &&
            appointFeature.getAppointmentCountBy(player.uniqueId, appointKey) >= definition.maxAppointments
        ) {
            val placeholders = HashMap<String, String>()
            placeholders["max"] = definition.maxAppointments.toString()
            lang.sendMessage(player, "command.appoint.max_reached", placeholders)
            manager.openCabinetMembersGUI(player, appointKey, holder.page)
            return
        }
        if (appointFeature.isAppointed(targetUuid, appointKey)) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = target.name
            placeholders["perm_set"] = if (definition != null) {
                ColorUtils.translateColorCodes(definition.displayName) ?: ""
            } else {
                appointKey
            }
            lang.sendMessage(player, "command.appoint.already_appointed", placeholders)
            manager.openCabinetMembersGUI(player, appointKey, holder.page)
            return
        }

        val success = appointFeature.appoint(player, target, appointKey)
        if (success) {
            val placeholders = HashMap<String, String>()
            placeholders["player"] = target.name
            placeholders["perm_set"] = if (definition != null) {
                ColorUtils.translateColorCodes(definition.displayName) ?: ""
            } else {
                appointKey
            }
            lang.sendMessage(player, "command.appoint.success", placeholders)
        } else {
            lang.sendMessage(player, "command.appoint.failed")
        }
        manager.openCabinetMembersGUI(player, appointKey, holder.page)
    }

    fun open(player: Player, appointKey: String, page: Int) {
        val appointFeature = getAppointFeature()
        val definition = appointFeature?.getAppointDefinition(appointKey)

        val entries = buildEntries(player, appointKey, appointFeature)
        val totalItems = entries.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / CONTENT_SIZE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        val titleBase = if (definition != null) {
            rawMsg("cabinet_members.title").replace(
                "%role%",
                ChatColor.stripColor(ColorUtils.translateColorCodes(definition.displayName) ?: "") ?: "",
            )
        } else {
            rawMsg("cabinet_members.title").replace("%role%", appointKey)
        }
        val title = ColorUtils.translateColorCodes(
            titleBase + if (totalPages > 1) " &8(${currentPage + 1}/$totalPages)" else "",
        ) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.CABINET_MEMBERS, player.uniqueId, player.hasPermission("rulegems.admin"), currentPage, appointKey)
        val gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        if (entries.isEmpty()) {
            gui.setItem(
                13,
                ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("cabinet_members.no_candidates"))
                    .addLore("&7" + rawMsg("cabinet_members.no_candidates_lore"))
                    .build(),
            )
        } else {
            val startIndex = currentPage * CONTENT_SIZE
            val endIndex = minOf(startIndex + CONTENT_SIZE, totalItems)
            for (i in startIndex until endIndex) {
                gui.setItem(i - startIndex, createEntryItem(entries[i]))
            }
        }

        player.openInventory(gui)
    }

    private fun createEntryItem(entry: Entry): ItemStack {
        val builder = ItemBuilder(Material.PLAYER_HEAD)
            .name((if (entry.appointed) "&c" else "&a") + "◆ " + entry.name)
            .data(manager.playerUuidKey, entry.uuid.toString())
            .hideAttributes()
        builder.skullOwner(Bukkit.getOfflinePlayer(entry.uuid))
        builder.addEmptyLore()
            .addLore(
                "&e▸ " + rawMsg("cabinet_members.status") + ": " + if (entry.appointed) {
                    rawMsg("cabinet_members.status_appointed")
                } else {
                    rawMsg("cabinet_members.status_available")
                },
            )

        if (entry.appointed) {
            builder.addLore("&7" + rawMsg("cabinet_members.dismiss_hint"))
        } else {
            builder.addLore("&7" + rawMsg("cabinet_members.appoint_hint"))
        }
        return builder.build()
    }

    private fun buildEntries(viewer: Player, appointKey: String, appointFeature: AppointFeature?): List<Entry> {
        val entries = ArrayList<Entry>()
        if (appointFeature == null || !appointFeature.isEnabled) {
            return entries
        }

        val seen = HashSet<UUID>()
        val currentAppointments = ArrayList(appointFeature.getAppointmentsByAppointer(viewer.uniqueId, appointKey))
        currentAppointments.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { appointment ->
            gemManager.getCachedPlayerName(appointment.appointeeUuid)
        })
        for (appointment in currentAppointments) {
            val uuid = appointment.appointeeUuid
            seen.add(uuid)
            entries.add(Entry(uuid, gemManager.getCachedPlayerName(uuid), true))
        }

        val candidates = ArrayList(Bukkit.getOnlinePlayers())
        candidates.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { player -> player.name })
        for (online in candidates) {
            if (online.uniqueId == viewer.uniqueId || seen.contains(online.uniqueId)) {
                continue
            }
            if (appointFeature.isAppointed(online.uniqueId, appointKey)) {
                continue
            }
            entries.add(Entry(online.uniqueId, online.name, false))
        }
        return entries
    }

    private fun getAppointFeature(): AppointFeature? = plugin.featureManager?.appointFeature

    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")

    private data class Entry(
        val uuid: UUID,
        val name: String,
        val appointed: Boolean,
    )

    companion object {
        private const val CONTENT_SIZE = 36
    }
}
