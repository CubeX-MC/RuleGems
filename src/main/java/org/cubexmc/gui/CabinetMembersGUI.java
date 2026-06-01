package org.cubexmc.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.appoint.Appointment;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;

public class CabinetMembersGUI extends ChestMenu {

    private static final int CONTENT_SIZE = 36;

    private final GemManager gemManager;
    private final LanguageManager lang;
    private final RuleGems plugin;

    public CabinetMembersGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager, RuleGems plugin) {
        super(guiManager);
        this.gemManager = gemManager;
        this.lang = languageManager;
        this.plugin = plugin;
    }

    @Override
    protected String getTitle() {
        return msg("cabinet_members.title");
    }

    @Override
    protected int getSize() {
        return GUIManager.GUI_SIZE;
    }

    @Override
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.CABINET_MEMBERS;
    }

    @Override
    public void onClick(Player player, GUIHolder holder, int slot, ItemStack clicked,
            org.bukkit.persistence.PersistentDataContainer pdc, boolean shiftClick) {
        String appointKey = holder.getContext();
        String targetUuidText = pdc.get(manager.getPlayerUuidKey(), PersistentDataType.STRING);
        if (appointKey == null || targetUuidText == null) {
            return;
        }

        AppointFeature appointFeature = getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            lang.sendMessage(player, "command.appoint.disabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetUuidText);
        } catch (IllegalArgumentException ex) {
            return;
        }

        List<Appointment> currentAppointments = appointFeature.getAppointmentsByAppointer(player.getUniqueId(), appointKey);
        Appointment existing = currentAppointments.stream()
                .filter(appointment -> appointment.getAppointeeUuid().equals(targetUuid))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            boolean success = appointFeature.dismiss(player, targetUuid, appointKey);
            if (success) {
                java.util.Map<String, String> placeholders = new java.util.HashMap<>();
                placeholders.put("player", gemManager.getCachedPlayerName(targetUuid));
                AppointDefinition definition = appointFeature.getAppointDefinition(appointKey);
                placeholders.put("perm_set", definition != null
                        ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())
                        : appointKey);
                lang.sendMessage(player, "command.dismiss.success", placeholders);
            } else {
                lang.sendMessage(player, "command.dismiss.failed");
            }
            manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
            return;
        }

        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            java.util.Map<String, String> placeholders = new java.util.HashMap<>();
            placeholders.put("player", gemManager.getCachedPlayerName(targetUuid));
            lang.sendMessage(player, "command.appoint.player_not_found", placeholders);
            manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
            return;
        }
        if (target.equals(player)) {
            lang.sendMessage(player, "command.appoint.cannot_self");
            manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
            return;
        }

        AppointDefinition definition = appointFeature.getAppointDefinition(appointKey);
        if (definition != null && definition.getMaxAppointments() > 0
                && appointFeature.getAppointmentCountBy(player.getUniqueId(), appointKey) >= definition.getMaxAppointments()) {
            java.util.Map<String, String> placeholders = new java.util.HashMap<>();
            placeholders.put("max", String.valueOf(definition.getMaxAppointments()));
            lang.sendMessage(player, "command.appoint.max_reached", placeholders);
            manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
            return;
        }
        if (appointFeature.isAppointed(targetUuid, appointKey)) {
            java.util.Map<String, String> placeholders = new java.util.HashMap<>();
            placeholders.put("player", target.getName());
            placeholders.put("perm_set", definition != null
                    ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())
                    : appointKey);
            lang.sendMessage(player, "command.appoint.already_appointed", placeholders);
            manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
            return;
        }

        boolean success = appointFeature.appoint(player, target, appointKey);
        if (success) {
            java.util.Map<String, String> placeholders = new java.util.HashMap<>();
            placeholders.put("player", target.getName());
            placeholders.put("perm_set", definition != null
                    ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())
                    : appointKey);
            lang.sendMessage(player, "command.appoint.success", placeholders);
        } else {
            lang.sendMessage(player, "command.appoint.failed");
        }
        manager.openCabinetMembersGUI(player, appointKey, holder.getPage());
    }

    public void open(Player player, String appointKey, int page) {
        AppointFeature appointFeature = getAppointFeature();
        AppointDefinition definition = appointFeature != null ? appointFeature.getAppointDefinition(appointKey) : null;

        List<Entry> entries = buildEntries(player, appointKey, appointFeature);
        int totalItems = entries.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) CONTENT_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String titleBase = definition != null
                ? rawMsg("cabinet_members.title").replace("%role%",
                        ChatColor.stripColor(org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())))
                : rawMsg("cabinet_members.title").replace("%role%", appointKey);
        String title = org.cubexmc.utils.ColorUtils.translateColorCodes(titleBase + (totalPages > 1 ? " &8(" + (page + 1) + "/" + totalPages + ")" : ""));

        GUIHolder holder = new GUIHolder(GUIHolder.GUIType.CABINET_MEMBERS, player.getUniqueId(),
                player.hasPermission("rulegems.admin"), page, appointKey);
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);

        manager.fillDecoration(gui);
        manager.addControlBar(gui, page, totalPages, totalItems, false, true);

        if (entries.isEmpty()) {
            gui.setItem(13, new ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("cabinet_members.no_candidates"))
                    .addLore("&7" + rawMsg("cabinet_members.no_candidates_lore"))
                    .build());
        } else {
            int startIndex = page * CONTENT_SIZE;
            int endIndex = Math.min(startIndex + CONTENT_SIZE, totalItems);
            for (int i = startIndex; i < endIndex; i++) {
                gui.setItem(i - startIndex, createEntryItem(entries.get(i)));
            }
        }

        player.openInventory(gui);
    }

    private ItemStack createEntryItem(Entry entry) {
        ItemBuilder builder = new ItemBuilder(Material.PLAYER_HEAD)
                .name((entry.appointed ? "&c" : "&a") + "◆ " + entry.name)
                .data(manager.getPlayerUuidKey(), entry.uuid.toString())
                .hideAttributes();
        builder.skullOwner(Bukkit.getOfflinePlayer(entry.uuid));
        builder.addEmptyLore()
                .addLore("&e▸ " + rawMsg("cabinet_members.status") + ": " + (entry.appointed
                        ? rawMsg("cabinet_members.status_appointed")
                        : rawMsg("cabinet_members.status_available")));

        if (entry.appointed) {
            builder.addLore("&7" + rawMsg("cabinet_members.dismiss_hint"));
        } else {
            builder.addLore("&7" + rawMsg("cabinet_members.appoint_hint"));
        }
        return builder.build();
    }

    private List<Entry> buildEntries(Player viewer, String appointKey, AppointFeature appointFeature) {
        List<Entry> entries = new ArrayList<>();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            return entries;
        }

        Set<UUID> seen = new HashSet<>();
        List<Appointment> currentAppointments = appointFeature.getAppointmentsByAppointer(viewer.getUniqueId(), appointKey);
        currentAppointments.sort(Comparator.comparing(appointment -> gemManager.getCachedPlayerName(appointment.getAppointeeUuid()),
                String.CASE_INSENSITIVE_ORDER));
        for (Appointment appointment : currentAppointments) {
            UUID uuid = appointment.getAppointeeUuid();
            seen.add(uuid);
            entries.add(new Entry(uuid, gemManager.getCachedPlayerName(uuid), true));
        }

        List<Player> candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player online : candidates) {
            if (online.getUniqueId().equals(viewer.getUniqueId()) || seen.contains(online.getUniqueId())) {
                continue;
            }
            if (appointFeature.isAppointed(online.getUniqueId(), appointKey)) {
                continue;
            }
            entries.add(new Entry(online.getUniqueId(), online.getName(), false));
        }
        return entries;
    }

    private AppointFeature getAppointFeature() {
        return plugin.getFeatureManager() != null ? plugin.getFeatureManager().getAppointFeature() : null;
    }

    private String msg(String path) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    private record Entry(UUID uuid, String name, boolean appointed) {
    }
}
