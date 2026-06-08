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
import org.cubexmc.model.AllowedCommand
import org.cubexmc.model.AppointDefinition
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class RulerAppointeesGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
    private val plugin: RuleGems,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("appointees.title")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.RULER_APPOINTEES

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
            val target = Bukkit.getPlayer(targetUuid)
            if (shiftClick && holder.isAdmin) {
                val rulerUuidStr = holder.getContext()
                if (rulerUuidStr != null) {
                    val rulerUuid = UUID.fromString(rulerUuidStr)
                    if (dismissAppointee(player, rulerUuid, targetUuid)) {
                        manager.openRulerAppointeesGUI(player, rulerUuid, holder.isAdmin, holder.page)
                    }
                }
                return
            }
            if (holder.isAdmin && target != null && target.isOnline) {
                player.closeInventory()
                SchedulerUtil.safeTeleport(plugin, player, target.location)
                player.sendMessage(msg("appointees.teleported_to_player").replace("%player%", target.name))
            }
        } catch (e: Exception) {
            plugin.logger.fine("AppointeesGUI click error: " + e.message)
        }
    }

    private fun dismissAppointee(admin: Player, rulerUuid: UUID, appointeeUuid: UUID): Boolean {
        val appointFeature = getAppointFeature()
        if (appointFeature == null || !appointFeature.isEnabled) {
            return false
        }
        val appointments = appointFeature.getAppointmentsByAppointer(rulerUuid)
        for (appointment in appointments) {
            if (appointment.appointeeUuid == appointeeUuid) {
                val result = appointFeature.dismiss(admin, appointeeUuid, appointment.permSetKey)
                if (result) {
                    admin.sendMessage(
                        msg("appointees.dismissed")
                            .replace("%player%", gemManager.getCachedPlayerName(appointeeUuid)),
                    )
                    return true
                }
            }
        }
        return false
    }

    fun open(player: Player, rulerUuid: UUID, isAdmin: Boolean, page: Int) {
        val appointFeature = getAppointFeature()

        val allAppointments: List<Appointment> = if (appointFeature != null && appointFeature.isEnabled) {
            appointFeature.getAppointmentsByAppointer(rulerUuid)
        } else {
            emptyList()
        }

        val totalItems = allAppointments.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / GUIManager.ITEMS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        val rulerName = gemManager.getCachedPlayerName(rulerUuid)

        var title = msg("appointees.title").replace("%ruler%", rulerName)
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(
            GUIHolder.GUIType.RULER_APPOINTEES,
            player.uniqueId,
            isAdmin,
            currentPage,
            rulerUuid.toString(),
        )

        val gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        if (allAppointments.isEmpty()) {
            gui.setItem(13, createNoAppointeesItem())
        } else {
            val startIndex = currentPage * GUIManager.ITEMS_PER_PAGE
            val endIndex = minOf(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems)

            for (i in startIndex until endIndex) {
                val slot = i - startIndex
                val appointment = allAppointments[i]
                gui.setItem(slot, createAppointeeItem(appointment, appointFeature, isAdmin))
            }
        }

        player.openInventory(gui)
    }

    private fun createNoAppointeesItem(): ItemStack = ItemBuilder(Material.BARRIER)
        .name("&c" + rawMsg("appointees.no_appointees"))
        .addLore("&7" + rawMsg("appointees.no_appointees_lore"))
        .build()

    private fun createAppointeeItem(appointment: Appointment, appointFeature: AppointFeature?, isAdmin: Boolean): ItemStack {
        val appointeeUuid = appointment.appointeeUuid
        val appointee = Bukkit.getPlayer(appointeeUuid)
        val playerName = gemManager.getCachedPlayerName(appointeeUuid)
        val isOnline = appointee != null && appointee.isOnline

        val definition: AppointDefinition? = appointFeature?.getAppointDefinition(appointment.permSetKey)
        val displayName = if (definition != null) {
            ColorUtils.translateColorCodes(definition.displayName)
        } else {
            appointment.permSetKey
        }

        val nameColor = if (isOnline) ChatColor.GREEN else ChatColor.GRAY

        val builder = ItemBuilder(Material.PLAYER_HEAD)
            .name("$nameColor◆ $playerName")
            .data(manager.playerUuidKey, appointeeUuid.toString())

        if (appointee != null) {
            builder.skullOwner(appointee)
        } else {
            try {
                builder.skullOwner(Bukkit.getOfflinePlayer(appointeeUuid))
            } catch (e: Exception) {
                plugin.logger.fine("Failed to set skull owner for offline appointee: " + e.message)
            }
        }

        builder.addEmptyLore()
        builder.addLore("&e▸ " + rawMsg("appointees.position") + " &f" + displayName)

        if (definition != null && !definition.description.isNullOrEmpty()) {
            builder.addLore("&7  " + definition.description)
        }

        val powerStructure = definition?.powerStructure
        if (powerStructure != null && powerStructure.permissions.isNotEmpty()) {
            builder.addEmptyLore()
            builder.addLore("&b▸ " + rawMsg("appointees.permissions"))
            var count = 0
            val permissions = powerStructure.permissions
            for (permission in permissions) {
                if (count >= 5) {
                    val remaining = permissions.size - 5
                    builder.addLore("&8  ... " + rawMsg("appointees.and_more").replace("%count%", remaining.toString()))
                    break
                }
                builder.addLore("&8  • &7$permission")
                count++
            }
        }

        if (powerStructure != null && powerStructure.allowedCommands.isNotEmpty()) {
            builder.addEmptyLore()
            builder.addLore("&d▸ " + rawMsg("appointees.allowed_commands"))
            var count = 0
            for (command: AllowedCommand in powerStructure.allowedCommands) {
                if (count >= 3) {
                    val remaining = powerStructure.allowedCommands.size - 3
                    builder.addLore("&8  ... " + rawMsg("appointees.and_more").replace("%count%", remaining.toString()))
                    break
                }
                var commandText = "/" + command.label
                if (command.uses > 0) {
                    commandText += " &7(" + command.uses + "x)"
                }
                builder.addLore("&8  • &7$commandText")
                count++
            }
        }

        val canAppoint = ArrayList<String>()
        if (powerStructure != null) {
            val appoints = powerStructure.appoints
            if (appoints != null) {
                canAppoint.addAll(appoints.keys)
            }
            for (permission in powerStructure.permissions) {
                if (permission.startsWith("rulegems.appoint.")) {
                    val key = permission.substring("rulegems.appoint.".length)
                    if (!canAppoint.contains(key)) {
                        canAppoint.add(key)
                    }
                }
            }
        }

        if (canAppoint.isNotEmpty()) {
            builder.addEmptyLore()
            builder.addLore("&6▸ " + rawMsg("appointees.delegate_permissions"))
            for (key in canAppoint) {
                val delegateDefinition = appointFeature?.getAppointDefinition(key)
                val delegateName = if (delegateDefinition != null) {
                    ColorUtils.translateColorCodes(delegateDefinition.displayName)
                } else {
                    key
                }
                builder.addLore("&8  • &7" + rawMsg("appointees.can_appoint") + " " + delegateName)
            }
        }

        builder.addEmptyLore()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val appointedTime = dateFormat.format(Date(appointment.appointedAt))
        builder.addLore("&7" + rawMsg("appointees.appointed_at") + " &f" + appointedTime)

        builder.addEmptyLore()
        if (isOnline) {
            builder.addLore("&a● " + rawMsg("rulers.status_online"))
        } else {
            builder.addLore("&c● " + rawMsg("rulers.status_offline"))
        }

        if (isAdmin) {
            builder.addEmptyLore()
            if (isOnline) {
                builder.addLore("&a» " + rawMsg("appointees.click_tp"))
            }
            builder.addLore("&c» " + rawMsg("appointees.shift_click_dismiss"))
        }

        return builder.build()
    }

    private fun getAppointFeature(): AppointFeature? {
        val featureManager = plugin.featureManager ?: return null
        return featureManager.appointFeature
    }

    private fun msg(path: String): String = ColorUtils.translateColorCodes(lang.getMessage("gui.$path")) ?: ""

    private fun rawMsg(path: String): String = lang.getMessage("gui.$path")
}
