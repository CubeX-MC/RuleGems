package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AllowedCommand
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.util.Locale
import java.util.UUID

class GemsGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("gems.title_player")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.GEMS

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
        if (!holder.isAdmin) {
            return
        }
        val gemIdStr = pdc.get(manager.gemIdKey, PersistentDataType.STRING) ?: return
        try {
            val gemId = UUID.fromString(gemIdStr)
            val gemHolder = gemManager.getGemHolder(gemId)
            if (gemHolder != null && gemHolder.isOnline) {
                player.closeInventory()
                SchedulerUtil.safeTeleport(manager.plugin, player, gemHolder.location)
                player.sendMessage(msg("gems.teleported_to_holder").replace("%player%", gemHolder.name))
            } else {
                val location = gemManager.getGemLocation(gemId)
                if (location != null) {
                    player.closeInventory()
                    SchedulerUtil.safeTeleport(manager.plugin, player, location.clone().add(0.5, 1.0, 0.5))
                    player.sendMessage(msg("gems.teleported_to_location"))
                }
            }
        } catch (e: Exception) {
            manager.plugin.logger.fine("GemsGUI click error: " + e.message)
        }
    }

    fun open(player: Player, isAdmin: Boolean, page: Int, filter: String?) {
        var allGemIds = ArrayList(gemManager.allGemUuids)
        if (!filter.isNullOrEmpty()) {
            allGemIds = ArrayList(filterGems(allGemIds, filter))
        }

        val totalItems = allGemIds.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / GUIManager.ITEMS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        var title = if (isAdmin) msg("gems.title_admin") else msg("gems.title_player")
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.GEMS, player.uniqueId, isAdmin, currentPage, filter)
        val gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, true, true)
        gui.setItem(
            GUIManager.SLOT_FILTER,
            ItemBuilder.filterButton(
                manager.navActionKey,
                rawMsg("control.filter"),
                rawMsg("control.filter_hint") + ": &f" + currentFilterLabel(filter),
                rawMsg("gems.filter_all"),
                rawMsg("gems.status_held"),
                rawMsg("gems.status_placed"),
            ),
        )
        gui.setItem(48, createRedeemGuideButton())

        val startIndex = currentPage * GUIManager.ITEMS_PER_PAGE
        val endIndex = minOf(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems)
        for (i in startIndex until endIndex) {
            gui.setItem(i - startIndex, createGemItem(allGemIds[i], isAdmin))
        }

        player.openInventory(gui)
    }

    private fun filterGems(gems: List<UUID>, filter: String): List<UUID> {
        val lowerFilter = filter.lowercase(Locale.ROOT)
        return gems.filter { gemId ->
            val gemKey = gemManager.getGemKey(gemId)
            if (gemKey != null && gemKey.lowercase(Locale.ROOT).contains(lowerFilter)) {
                return@filter true
            }

            val definition = if (gemKey != null) gemManager.findGemDefinitionByKey(gemKey) else null
            if (definition?.displayName != null) {
                val stripped = ChatColor.stripColor(ColorUtils.translateColorCodes(definition.displayName) ?: "") ?: ""
                if (stripped.lowercase(Locale.ROOT).contains(lowerFilter)) {
                    return@filter true
                }
            }

            if (lowerFilter == "held" || lowerFilter == "持有") {
                return@filter gemManager.getGemHolder(gemId) != null
            }
            if (lowerFilter == "placed" || lowerFilter == "放置") {
                return@filter gemManager.getGemLocation(gemId) != null
            }
            false
        }
    }

    private fun currentFilterLabel(filter: String?): String {
        if (filter.isNullOrEmpty()) {
            return rawMsg("gems.filter_all")
        }
        if (filter.equals("held", ignoreCase = true)) {
            return rawMsg("gems.status_held")
        }
        if (filter.equals("placed", ignoreCase = true)) {
            return rawMsg("gems.status_placed")
        }
        return filter
    }

    private fun createGemItem(gemId: UUID, isAdmin: Boolean): ItemStack {
        val gemKey = gemManager.getGemKey(gemId)
        val definition = if (gemKey != null) gemManager.findGemDefinitionByKey(gemKey) else null
        val material = definition?.material ?: Material.RED_STAINED_GLASS
        val displayName = definition?.displayName ?: rawMsg("gems.default_name")

        val builder = ItemBuilder(material)
            .name(displayName)
            .data(manager.gemIdKey, gemId.toString())

        val holder = gemManager.getGemHolder(gemId)
        val location = gemManager.getGemLocation(gemId)

        if (isAdmin) {
            buildAdminLore(builder, gemId, gemKey, definition, holder, location)
        } else {
            buildPlayerLore(builder, definition, holder, location)
        }

        if (definition?.isEnchanted == true) {
            builder.glow()
        }

        return builder.hideAttributes().build()
    }

    private fun buildAdminLore(
        builder: ItemBuilder,
        gemId: UUID,
        gemKey: String?,
        definition: GemDefinition?,
        holder: Player?,
        location: Location?,
    ) {
        builder.addEmptyLore()
            .addLore("&e▸ " + rawMsg("gems.section_id"))
            .addLore("&8  Key: &f" + (gemKey ?: "N/A"))
            .addLore("&8  UUID: &7" + gemId.toString().substring(0, 8) + "...")

        builder.addEmptyLore()
            .addLore("&e▸ " + rawMsg("gems.section_status"))

        if (holder != null) {
            builder.addLore("&a  ● " + rawMsg("gems.status_held"))
                .addLore("&8  → &b" + holder.name)
        } else if (location != null) {
            builder.addLore("&6  ● " + rawMsg("gems.status_placed"))
                .addLore("&8  → &f" + formatLocation(location))
        } else {
            builder.addLore("&c  ● " + rawMsg("gems.status_unknown"))
        }

        if (definition != null) {
            val hasPerms = definition.permissions.isNotEmpty() || !definition.vaultGroup.isNullOrEmpty()
            if (hasPerms) {
                builder.addEmptyLore()
                    .addLore("&e▸ " + rawMsg("gems.section_permissions"))
                for (permission in definition.permissions) {
                    builder.addLore("&8  • &a$permission")
                }
                if (!definition.vaultGroup.isNullOrEmpty()) {
                    builder.addLore("&8  Group: &d" + definition.vaultGroup)
                }
            }

            if (definition.allowedCommands.isNotEmpty()) {
                builder.addEmptyLore()
                    .addLore("&e▸ " + rawMsg("gems.section_commands"))
                for (command: AllowedCommand in definition.allowedCommands) {
                    val uses = if (command.uses < 0) rawMsg("gems.uses_unlimited") else command.uses.toString()
                    builder.addLore("&8  • &b/" + command.label + " &7(" + uses + ")")
                }
            }

            val altar = definition.altarLocation
            if (altar != null) {
                builder.addEmptyLore()
                    .addLore("&e▸ " + rawMsg("gems.section_altar"))
                    .addLore("&8  → &d" + formatLocation(altar))
            }
        }

        builder.addEmptyLore()
        if (holder != null) {
            builder.addLore("&a» " + rawMsg("gems.click_tp_holder"))
        } else if (location != null) {
            builder.addLore("&a» " + rawMsg("gems.click_tp_location"))
        }
    }

    private fun buildPlayerLore(builder: ItemBuilder, definition: GemDefinition?, holder: Player?, location: Location?) {
        if (definition?.lore != null && definition.lore.isNotEmpty()) {
            builder.addEmptyLore()
            for (line in definition.lore) {
                builder.addLore(line)
            }
        }

        builder.addEmptyLore()
        if (holder != null) {
            builder.addLore("&7" + rawMsg("gems.status_held") + ": &b" + holder.name)
        } else if (location != null) {
            builder.addLore("&7" + rawMsg("gems.hidden_in_world"))
        } else {
            builder.addLore("&7" + rawMsg("gems.status_unknown"))
        }
    }

    private fun formatLocation(location: Location): String {
        val world = location.world?.name ?: "?"
        return String.format("%d, %d, %d (%s)", location.blockX, location.blockY, location.blockZ, world)
    }

    private fun createRedeemGuideButton(): ItemStack {
        val redeemEnabled = manager.plugin.gameplayConfig.isRedeemEnabled
        val redeemAllEnabled = manager.plugin.gameplayConfig.isFullSetGrantsAllEnabled
        val holdEnabled = manager.plugin.gameplayConfig.isHoldToRedeemEnabled
        val placeEnabled = manager.plugin.gameplayConfig.isPlaceRedeemEnabled
        val sneakToRedeem = manager.plugin.gameplayConfig.isSneakToRedeem

        val builder = ItemBuilder(Material.EMERALD)
            .name("&a" + rawMsg("menu.redeem_title"))
            .data(manager.navActionKey, "show_redeem_help")
            .glow()

        builder.addEmptyLore()
            .addLore("&7" + rawMsg("menu.redeem_desc"))
            .addEmptyLore()

        if (redeemEnabled) {
            builder.addLore("&e▸ " + rawMsg("menu.redeem_command"))
        } else {
            builder.addLore("&c▸ " + rawMsg("menu.redeem_disabled"))
        }

        if (holdEnabled) {
            builder.addLore("&e▸ " + rawMsg(if (sneakToRedeem) "menu.redeem_hold_sneak" else "menu.redeem_hold_normal"))
        }
        if (placeEnabled) {
            builder.addLore("&e▸ " + rawMsg("menu.redeem_altar"))
        }
        if (redeemAllEnabled) {
            builder.addLore("&e▸ " + rawMsg("menu.redeem_all"))
        }

        builder.addEmptyLore()
            .addLore("&8" + rawMsg("menu.info_only"))
        return builder.build()
    }
}
