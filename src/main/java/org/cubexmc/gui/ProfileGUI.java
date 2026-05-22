package org.cubexmc.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.appoint.Appointment;
import org.cubexmc.features.revoke.RevokeFeature;
import org.cubexmc.features.revoke.RevokeRule;
import org.cubexmc.manager.CustomCommandExecutor;
import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;
import org.cubexmc.model.GemDefinition;

public class ProfileGUI extends ChestMenu {

    private static final int COMMANDS_PER_PAGE = 27;
    private static final int[] COMMAND_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    };

    private final GemManager gemManager;
    private final LanguageManager lang;
    private final RuleGems plugin;

    public ProfileGUI(GUIManager guiManager, GemManager gemManager, LanguageManager languageManager, RuleGems plugin) {
        super(guiManager);
        this.gemManager = gemManager;
        this.lang = languageManager;
        this.plugin = plugin;
    }

    @Override
    protected String getTitle() {
        return msg("profile.title");
    }

    @Override
    protected int getSize() {
        return GUIManager.GUI_SIZE;
    }

    @Override
    protected GUIHolder.GUIType getHolderType() {
        return GUIHolder.GUIType.PROFILE;
    }

    public void open(Player player, int page) {
        List<CommandEntry> commands = buildCommandEntries(player);
        int totalItems = commands.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) COMMANDS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = msg("profile.title");
        if (totalPages > 1) {
            title += " &8(" + (page + 1) + "/" + totalPages + ")";
        }
        title = org.cubexmc.utils.ColorUtils.translateColorCodes(title);

        GUIHolder holder = new GUIHolder(GUIHolder.GUIType.PROFILE, player.getUniqueId(), player.hasPermission("rulegems.admin"), page);
        Inventory gui = Bukkit.createInventory(holder, GUIManager.GUI_SIZE, title);
        holder.setInventory(gui);

        manager.fillDecoration(gui);
        manager.addControlBar(gui, page, totalPages, totalItems, false, true);

        gui.setItem(0, createIdentityItem(player));
        gui.setItem(1, createRulerItem(player));
        gui.setItem(2, createAppointmentsItem(player));
        gui.setItem(3, createCommandsSummaryItem(commands.size()));
        gui.setItem(4, createManagePowersItem(player));
        gui.setItem(5, createRevokeRulesItem(player));

        if (commands.isEmpty()) {
            gui.setItem(22, new ItemBuilder(Material.BARRIER)
                    .name("&c" + rawMsg("profile.no_commands"))
                    .addLore("&7" + rawMsg("profile.no_commands_lore"))
                    .build());
        } else {
            int startIndex = page * COMMANDS_PER_PAGE;
            int endIndex = Math.min(startIndex + COMMANDS_PER_PAGE, commands.size());
            for (int i = startIndex; i < endIndex; i++) {
                gui.setItem(COMMAND_SLOTS[i - startIndex], createCommandItem(commands.get(i)));
            }
        }

        player.openInventory(gui);
    }

    private ItemStack createIdentityItem(Player player) {
        return new ItemBuilder(Material.PLAYER_HEAD)
                .name("&a" + rawMsg("profile.identity_title"))
                .skullOwner(player)
                .addEmptyLore()
                .addLore("&e▸ " + rawMsg("profile.player_name") + ": &f" + player.getName())
                .addLore("&e▸ " + rawMsg("profile.player_uuid") + ": &7" + player.getUniqueId())
                .hideAttributes()
                .build();
    }

    private ItemStack createRulerItem(Player player) {
        Set<String> rulerKeys = gemManager.getCurrentRulers().getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());
        ItemBuilder builder = new ItemBuilder(Material.GOLDEN_HELMET)
                .name("&6" + rawMsg("profile.ruler_title"))
                .hideAttributes();
        builder.addEmptyLore();
        if (rulerKeys.isEmpty()) {
            builder.addLore("&7" + rawMsg("profile.ruler_none"));
            return builder.build();
        }

        boolean fullSet = rulerKeys.contains("ALL");
        if (fullSet) {
            builder.addLore("&6" + rawMsg("profile.ruler_full_set"));
        }
        for (String key : rulerKeys.stream().filter(key -> !"ALL".equals(key)).sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList())) {
            GemDefinition definition = gemManager.findGemDefinitionByKey(key);
            String display = definition != null ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName()) : key;
            builder.addLore("&f- " + display);
        }
        builder.glow();
        return builder.build();
    }

    private ItemStack createAppointmentsItem(Player player) {
        AppointFeature appointFeature = getAppointFeature();
        List<Appointment> appointments = appointFeature != null
                ? appointFeature.getPlayerAppointments(player.getUniqueId())
                : java.util.Collections.emptyList();

        ItemBuilder builder = new ItemBuilder(Material.WRITABLE_BOOK)
                .name("&d" + rawMsg("profile.appointments_title"))
                .hideAttributes()
                .addEmptyLore();
        if (appointments.isEmpty()) {
            builder.addLore("&7" + rawMsg("profile.appointments_none"));
            return builder.build();
        }

        appointments.sort(Comparator.comparing(Appointment::getPermSetKey, String.CASE_INSENSITIVE_ORDER));
        for (Appointment appointment : appointments) {
            AppointDefinition definition = appointFeature.getAppointDefinition(appointment.getPermSetKey());
            String display = definition != null
                    ? org.cubexmc.utils.ColorUtils.translateColorCodes(definition.getDisplayName())
                    : appointment.getPermSetKey();
            String appointer = appointment.getAppointerUuid() != null
                    ? gemManager.getCachedPlayerName(appointment.getAppointerUuid())
                    : rawMsg("profile.system");
            builder.addLore("&f- " + display + " &7(" + appointer + ")");
        }
        return builder.build();
    }

    private ItemStack createCommandsSummaryItem(int commandCount) {
        return new ItemBuilder(Material.COMPASS)
                .name("&b" + rawMsg("profile.commands_title"))
                .addEmptyLore()
                .addLore("&e▸ " + rawMsg("profile.command_count") + ": &f" + commandCount)
                .addLore("&7" + rawMsg("profile.commands_summary"))
                .hideAttributes()
                .build();
    }

    private ItemStack createManagePowersItem(Player player) {
        return new ItemBuilder(Material.REDSTONE_TORCH)
                .name("&c" + rawMsg("profile.manage_powers_title"))
                .addEmptyLore()
                .addLore("&7" + rawMsg("profile.manage_powers_lore"))
                .hideAttributes()
                .build();
    }

    private ItemStack createRevokeRulesItem(Player player) {
        RevokeFeature revokeFeature = getRevokeFeature();
        List<RevokeRule> rules = revokeFeature != null
                ? revokeFeature.getAvailableRules(player)
                : java.util.Collections.emptyList();
        ItemBuilder builder = new ItemBuilder(rules.isEmpty() ? Material.GRAY_DYE : Material.NETHER_STAR)
                .name("&c" + rawMsg("profile.revoke_title"))
                .addEmptyLore();
        if (rules.isEmpty()) {
            return builder.addLore("&7" + rawMsg("profile.revoke_none"))
                    .hideAttributes()
                    .build();
        }
        builder.addLore("&e▸ " + rawMsg("profile.revoke_count") + ": &f" + rules.size());
        for (RevokeRule rule : rules) {
            builder.addLore("&f- " + rule.getKey() + " &7→ " + String.join(", ", rule.getTargetPowers()));
        }
        return builder.addEmptyLore()
                .addLore("&7" + rawMsg("profile.revoke_hint"))
                .glow()
                .hideAttributes()
                .build();
    }

    private ItemStack createCommandItem(CommandEntry entry) {
        ItemBuilder builder = new ItemBuilder(entry.cooldownSeconds > 0 ? Material.CLOCK : Material.PAPER)
                .name("&e/" + entry.label)
                .hideAttributes()
                .addEmptyLore()
                .addLore("&e▸ " + rawMsg("profile.remaining_uses") + ": &f" + entry.remainingDisplay);
        if (entry.cooldownSeconds > 0) {
            builder.addLore("&e▸ " + rawMsg("profile.cooldown") + ": &f" + entry.cooldownSeconds + "s");
        } else {
            builder.addLore("&e▸ " + rawMsg("profile.cooldown") + ": &a" + rawMsg("profile.cooldown_ready"));
        }
        if (entry.cooldownSeconds > 0) {
            builder.glow();
        }
        return builder.build();
    }

    private List<CommandEntry> buildCommandEntries(Player player) {
        GemAllowanceManager allowanceManager = gemManager.getAllowanceManager();
        CustomCommandExecutor executor = plugin.getCustomCommandExecutor();
        List<String> labels = new ArrayList<>(allowanceManager.getAvailableCommandLabels(player.getUniqueId()));
        labels.sort(String.CASE_INSENSITIVE_ORDER);

        List<CommandEntry> entries = new ArrayList<>();
        for (String label : labels) {
            int remaining = allowanceManager.getRemainingAllowed(player.getUniqueId(), label);
            long cooldown = executor != null ? executor.getRemainingCooldown(player.getUniqueId(), label) : 0L;
            entries.add(new CommandEntry(label, remaining < 0 ? rawMsg("profile.unlimited") : String.valueOf(remaining), cooldown));
        }
        return entries;
    }

    private AppointFeature getAppointFeature() {
        return plugin.getFeatureManager() != null ? plugin.getFeatureManager().getAppointFeature() : null;
    }

    private RevokeFeature getRevokeFeature() {
        return plugin.getFeatureManager() != null ? plugin.getFeatureManager().getRevokeFeature() : null;
    }

    private String msg(String path) {
        return org.cubexmc.utils.ColorUtils.translateColorCodes(lang.getMessage("gui." + path));
    }

    private String rawMsg(String path) {
        return lang.getMessage("gui." + path);
    }

    private record CommandEntry(String label, String remainingDisplay, long cooldownSeconds) {
    }

    @Override
    public void onClick(Player player, GUIHolder holder, int slot, ItemStack clicked, org.bukkit.persistence.PersistentDataContainer pdc, boolean isShiftClick) {
        if (slot == 4) {
            manager.openPowerTogglesGUI(player, holder.isAdmin());
            // Play a click sound
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }
}
