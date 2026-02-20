package org.cubexmc.commands.sub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.appoint.Appointment;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;

/**
 * /rulegems appointees [perm_set]
 */
public class AppointeesSubCommand implements SubCommand {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public AppointeesSubCommand(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(sender, "command.appoint.disabled");
            return true;
        }

        if (args.length < 1) {
            showAll(sender, appointFeature);
        } else {
            showForPermSet(sender, appointFeature, args[0]);
        }
        return true;
    }

    private void showAll(CommandSender sender, AppointFeature appointFeature) {
        languageManager.sendMessage(sender, "command.appointees.header");

        Map<String, AppointDefinition> definitions = appointFeature.getAppointDefinitions();
        if (definitions.isEmpty()) {
            languageManager.sendMessage(sender, "command.appointees.no_perm_sets");
            return;
        }

        for (AppointDefinition def : definitions.values()) {
            showPermSetBlock(sender, appointFeature, def);
        }
    }

    private void showForPermSet(CommandSender sender, AppointFeature appointFeature, String rawKey) {
        String permSetKey = rawKey.toLowerCase();
        AppointDefinition def = appointFeature.getAppointDefinition(permSetKey);

        if (def == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("perm_set", rawKey);
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", ph);
            return;
        }
        showPermSetBlock(sender, appointFeature, def);
    }

    private void showPermSetBlock(CommandSender sender, AppointFeature appointFeature, AppointDefinition def) {
        List<Appointment> appointees = appointFeature.getAppointees(def.getKey());
        Map<String, String> ph = new HashMap<>();
        ph.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
        ph.put("count", String.valueOf(appointees.size()));
        languageManager.sendMessage(sender, "command.appointees.set_header", ph);

        if (appointees.isEmpty()) {
            languageManager.sendMessage(sender, "command.appointees.none");
        } else {
            for (Appointment appt : appointees) {
                String appointeeName = gemManager.getCachedPlayerName(appt.getAppointeeUuid());
                String appointerName = appt.getAppointerUuid() != null
                        ? gemManager.getCachedPlayerName(appt.getAppointerUuid()) : "System";
                Map<String, String> linePh = new HashMap<>();
                linePh.put("appointee", appointeeName);
                linePh.put("appointer", appointerName);
                languageManager.sendMessage(sender, "command.appointees.entry", linePh);
            }
        }
    }
}
