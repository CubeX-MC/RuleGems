package org.cubexmc.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

public class CabinetGUI extends ChestMenu {

    private final GemManager gemManager;
    private final LanguageManager lang;
    private final RuleGems plugin;

    public CabinetGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager, RuleGems plugin) {
        super(guiManager);
        this.gemManager = gemManager;
        this.lang = languageManager;
        this.plugin = plugin;
    }

    @Override
    protected String getTitle() {
        return msg("cabinet.title");
    }

    @Override
    protected int getSize() {
        return GUIManager.GUI_SIZE;
    }

    @Override
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.CABINET;
    }

    @Override
    public void onClick(Player player, GUIHolder holder, int slot, ItemStack clicked,
            org.bukkit.persistence.PersistentDataContainer pdc, boolean shiftClick) {
        String appointKey = pdc.get(manager.getAppointKeyKey(), PersistentDataType.STRING);
        if (appointKey == null || appointKey.isBlank()) {
            return;
        }
        manager.openCabinetMembersGUI(player, appointKey);
    }

    public void open(Player player, int page) {
        AppointFeature appointFeature = getAppointFeature();
        List<String> appointKeys = getAvailableAppointKeys(player, appointFeature);

        int totalItems = appointKeys.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) GUIManager.ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = msg("cabinet.title");
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = org.cubexmc.utils.ColorUtils.translateColorCodes(title);

        GUIHolder holder = new GUIHolder(GUIHolder.GUIType.CABINET, player.getUniqueId(), player.hasPermission("rulegems.admin"), page);
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);

        manager.fillDecoration(gui);
        manager.addControlBar(gui, page, totalPages, totalItems, false, true);

        if (appointKeys.isEmpty()) {
            gui.setItem(13, new ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("cabinet.no_roles"))
                    .addLore("&7" + rawMsg("cabinet.no_roles_lore"))
                    .build());
        } else {
            int startIndex = page * GUIManager.ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + GUIManager.ITEMS_PER_PAGE, totalItems);
            for (int i = startIndex; i < endIndex; i++) {
                String appointKey = appointKeys.get(i);
                AppointDefinition definition = appointFeature != null ? appointFeature.getAppointDefinition(appointKey) : null;
                gui.setItem(i - startIndex, createRoleItem(player, appointKey, definition, appointFeature));
            }
        }

        player.openInventory(gui);
    }

    private ItemStack createRoleItem(Player player, String appointKey, AppointDefinition definition, AppointFeature appointFeature) {
        String displayName = definition != null
                ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())
                : appointKey;
        int current = appointFeature != null ? appointFeature.getAppointmentCountBy(player.getUniqueId(), appointKey) : 0;
        int max = definition != null ? definition.getMaxAppointments() : -1;
        List<Appointment> appointments = appointFeature != null
                ? appointFeature.getAppointmentsByAppointer(player.getUniqueId(), appointKey)
                : java.util.Collections.emptyList();

        ItemBuilder builder = new ItemBuilder(Material.WRITABLE_BOOK)
                .name(displayName)
                .data(manager.getAppointKeyKey(), appointKey)
                .hideAttributes();

        builder.addEmptyLore()
                .addLore("&e▸ " + rawMsg("cabinet.current_count") + ": &f" + current + "/" + formatMax(max));

        if (definition != null && definition.getDescription() != null && !definition.getDescription().isBlank()) {
            builder.addLore("&7" + definition.getDescription());
        }

        builder.addEmptyLore()
                .addLore("&b▸ " + rawMsg("cabinet.current_members"));
        if (appointments.isEmpty()) {
            builder.addLore("&7" + rawMsg("cabinet.none"));
        } else {
            int shown = 0;
            for (Appointment appointment : appointments) {
                builder.addLore("&f- &e" + gemManager.getCachedPlayerName(appointment.getAppointeeUuid()));
                shown++;
                if (shown >= 3) {
                    break;
                }
            }
            if (appointments.size() > 3) {
                builder.addLore("&8" + rawMsg("cabinet.more_members")
                        .replace("%count%", String.valueOf(appointments.size() - 3)));
            }
        }

        builder.addEmptyLore()
                .addLore("&a» " + rawMsg("cabinet.click_manage"));

        if (current > 0 || max != 0) {
            builder.glow();
        }
        return builder.build();
    }

    private List<String> getAvailableAppointKeys(Player player, AppointFeature appointFeature) {
        if (appointFeature == null || !appointFeature.isEnabled()) {
            return java.util.Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (String key : appointFeature.getAppointDefinitions().keySet()) {
            if (player.hasPermission("rulegems.admin")
                    || player.hasPermission("rulegems.appoint." + key)
                    || player.hasPermission("rulegems.appoint." + key.toLowerCase(java.util.Locale.ROOT))) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.comparing(key -> {
            AppointDefinition definition = appointFeature.getAppointDefinition(key);
            return definition != null ? ChatColor.stripColor(org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())) : key;
        }, String.CASE_INSENSITIVE_ORDER));
        return keys;
    }

    private String formatMax(int max) {
        return max <= 0 ? rawMsg("cabinet.unlimited") : String.valueOf(max);
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
}
