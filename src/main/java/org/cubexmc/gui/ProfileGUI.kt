package org.cubexmc.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.cubexmc.RuleGems
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.features.appoint.Appointment
import org.cubexmc.features.revoke.RevokeFeature
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.model.AppointDefinition
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils

class ProfileGUI(
    guiManager: GUIManager,
    private val gemManager: GemManager,
    private val lang: LanguageManager,
    private val plugin: RuleGems,
) : ChestMenu(guiManager) {
    override fun getTitle(): String = msg("profile.title")

    override fun getSize(): Int = GUIManager.GUI_SIZE

    override fun getHolderType(): GUIHolder.GUIType = GUIHolder.GUIType.PROFILE

    fun open(player: Player, page: Int) {
        val commands = buildCommandEntries(player)
        val totalItems = commands.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / COMMANDS_PER_PAGE.toDouble()).toInt())
        val currentPage = page.coerceIn(0, totalPages - 1)

        var title = msg("profile.title")
        if (totalPages > 1) {
            title += " &8(${currentPage + 1}/$totalPages)"
        }
        title = ColorUtils.translateColorCodes(title) ?: ""

        val holder = GUIHolder(GUIHolder.GUIType.PROFILE, player.uniqueId, player.hasPermission("rulegems.admin"), currentPage)
        val gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title)
        holder.setInventory(gui)

        manager.fillDecoration(gui)
        manager.addControlBar(gui, currentPage, totalPages, totalItems, false, true)

        gui.setItem(0, createIdentityItem(player))
        gui.setItem(1, createRulerItem(player))
        gui.setItem(2, createAppointmentsItem(player))
        gui.setItem(3, createCommandsSummaryItem(commands.size))
        gui.setItem(4, createManagePowersItem(player))
        gui.setItem(5, createRevokeRulesItem(player))

        if (commands.isEmpty()) {
            gui.setItem(
                22,
                ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("profile.no_commands"))
                    .addLore("&7" + rawMsg("profile.no_commands_lore"))
                    .build(),
            )
        } else {
            val startIndex = currentPage * COMMANDS_PER_PAGE
            val endIndex = minOf(startIndex + COMMANDS_PER_PAGE, commands.size)
            for (i in startIndex until endIndex) {
                gui.setItem(COMMAND_SLOTS[i - startIndex], createCommandItem(commands[i]))
            }
        }

        player.openInventory(gui)
    }

    private fun createIdentityItem(player: Player): ItemStack = ItemBuilder(Material.PLAYER_HEAD)
        .name("&a" + rawMsg("profile.identity_title"))
        .skullOwner(player)
        .addEmptyLore()
        .addLore("&e▸ " + rawMsg("profile.player_name") + ": &f" + player.name)
        .addLore("&e▸ " + rawMsg("profile.player_uuid") + ": &7" + player.uniqueId)
        .hideAttributes()
        .build()

    private fun createRulerItem(player: Player): ItemStack {
        val rulerKeys = gemManager.currentRulers.getOrDefault(player.uniqueId, emptySet())
        val builder = ItemBuilder(Material.GOLDEN_HELMET)
            .name("&6" + rawMsg("profile.ruler_title"))
            .hideAttributes()
        builder.addEmptyLore()
        if (rulerKeys.isEmpty()) {
            builder.addLore("&7" + rawMsg("profile.ruler_none"))
            return builder.build()
        }

        val fullSet = rulerKeys.contains("ALL")
        if (fullSet) {
            builder.addLore("&6" + rawMsg("profile.ruler_full_set"))
        }
        rulerKeys
            .filter { key -> key != "ALL" }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { key ->
                val definition: GemDefinition? = gemManager.findGemDefinitionByKey(key)
                val display = if (definition != null) {
                    ColorUtils.translateColorCodes(definition.displayName) ?: ""
                } else {
                    key
                }
                builder.addLore("&f- $display")
            }
        builder.glow()
        return builder.build()
    }

    private fun createAppointmentsItem(player: Player): ItemStack {
        val appointFeature = getAppointFeature()
        val appointments: MutableList<Appointment> = if (appointFeature != null) {
            ArrayList(appointFeature.getPlayerAppointments(player.uniqueId))
        } else {
            ArrayList()
        }

        val builder = ItemBuilder(Material.WRITABLE_BOOK)
            .name("&d" + rawMsg("profile.appointments_title"))
            .hideAttributes()
            .addEmptyLore()
        if (appointments.isEmpty()) {
            builder.addLore("&7" + rawMsg("profile.appointments_none"))
            return builder.build()
        }

        appointments.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { appointment -> appointment.permSetKey })
        for (appointment in appointments) {
            val definition: AppointDefinition? = appointFeature?.getAppointDefinition(appointment.permSetKey)
            val display = if (definition != null) {
                ColorUtils.translateColorCodes(definition.displayName) ?: ""
            } else {
                appointment.permSetKey
            }
            val appointer = appointment.appointerUuid?.let { uuid -> gemManager.getCachedPlayerName(uuid) } ?: rawMsg("profile.system")
            builder.addLore("&f- $display &7($appointer)")
        }
        return builder.build()
    }

    private fun createCommandsSummaryItem(commandCount: Int): ItemStack = ItemBuilder(Material.COMPASS)
        .name("&b" + rawMsg("profile.commands_title"))
        .addEmptyLore()
        .addLore("&e▸ " + rawMsg("profile.command_count") + ": &f" + commandCount)
        .addLore("&7" + rawMsg("profile.commands_summary"))
        .hideAttributes()
        .build()

    private fun createManagePowersItem(player: Player): ItemStack = ItemBuilder(Material.REDSTONE_TORCH)
        .name("&c" + rawMsg("profile.manage_powers_title"))
        .addEmptyLore()
        .addLore("&7" + rawMsg("profile.manage_powers_lore"))
        .hideAttributes()
        .build()

    private fun createRevokeRulesItem(player: Player): ItemStack {
        val revokeFeature = getRevokeFeature()
        val rules = revokeFeature?.getAvailableRules(player) ?: emptyList()
        val builder = ItemBuilder(if (rules.isEmpty()) Material.GRAY_DYE else Material.NETHER_STAR)
            .name("&c" + rawMsg("profile.revoke_title"))
            .addEmptyLore()
        if (rules.isEmpty()) {
            return builder.addLore("&7" + rawMsg("profile.revoke_none"))
                .hideAttributes()
                .build()
        }
        builder.addLore("&e▸ " + rawMsg("profile.revoke_count") + ": &f" + rules.size)
        for (rule in rules) {
            builder.addLore("&f- " + rule.key + " &7→ " + rule.targetPowers.joinToString(", "))
        }
        return builder.addEmptyLore()
            .addLore("&7" + rawMsg("profile.revoke_hint"))
            .glow()
            .hideAttributes()
            .build()
    }

    private fun createCommandItem(entry: CommandEntry): ItemStack {
        val builder = ItemBuilder(if (entry.cooldownSeconds > 0) Material.CLOCK else Material.PAPER)
            .name("&e/" + entry.label)
            .hideAttributes()
            .addEmptyLore()
            .addLore("&e▸ " + rawMsg("profile.remaining_uses") + ": &f" + entry.remainingDisplay)
        if (entry.cooldownSeconds > 0) {
            builder.addLore("&e▸ " + rawMsg("profile.cooldown") + ": &f" + entry.cooldownSeconds + "s")
        } else {
            builder.addLore("&e▸ " + rawMsg("profile.cooldown") + ": &a" + rawMsg("profile.cooldown_ready"))
        }
        if (entry.cooldownSeconds > 0) {
            builder.glow()
        }
        return builder.build()
    }

    private fun buildCommandEntries(player: Player): List<CommandEntry> {
        val allowanceManager = gemManager.allowanceManager
        val executor = plugin.customCommandExecutor
        val labels = ArrayList(allowanceManager.getAvailableCommandLabels(player.uniqueId))
        labels.sortWith(String.CASE_INSENSITIVE_ORDER)

        val entries = ArrayList<CommandEntry>()
        for (label in labels) {
            val remaining = allowanceManager.getRemainingAllowed(player.uniqueId, label)
            val cooldown = executor.getRemainingCooldown(player.uniqueId, label)
            entries.add(CommandEntry(label, if (remaining < 0) rawMsg("profile.unlimited") else remaining.toString(), cooldown))
        }
        return entries
    }

    private fun getAppointFeature(): AppointFeature? = plugin.featureManager?.appointFeature

    private fun getRevokeFeature(): RevokeFeature? = plugin.featureManager?.revokeFeature

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
        if (slot == 4) {
            manager.openPowerTogglesGUI(player, holder.isAdmin)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
        }
    }

    private data class CommandEntry(
        val label: String,
        val remainingDisplay: String,
        val cooldownSeconds: Long,
    )

    companion object {
        private const val COMMANDS_PER_PAGE = 27
        private val COMMAND_SLOTS = intArrayOf(
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
        )
    }
}
