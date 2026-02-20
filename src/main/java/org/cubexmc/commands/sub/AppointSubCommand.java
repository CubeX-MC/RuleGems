package org.cubexmc.commands.sub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.appoint.Appointment;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;

/**
 * /rulegems appoint &lt;perm_set&gt; &lt;player&gt;
 */
public class AppointSubCommand implements SubCommand {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public AppointSubCommand(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.appoint.usage");
            return true;
        }

        AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(sender, "command.appoint.disabled");
            return true;
        }

        String permSetKey = args[0].toLowerCase();
        String targetName = args[1];

        AppointDefinition def = appointFeature.getAppointDefinition(permSetKey);
        if (def == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("perm_set", args[0]);
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", ph);
            return true;
        }

        Player appointer = (Player) sender;
        if (!appointer.hasPermission("rulegems.appoint." + permSetKey) && !appointer.hasPermission("rulegems.admin")) {
            languageManager.sendMessage(sender, "command.no_permission");
            return true;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", targetName);
            languageManager.sendMessage(sender, "command.appoint.player_not_found", ph);
            return true;
        }

        if (target.equals(appointer)) {
            languageManager.sendMessage(sender, "command.appoint.cannot_self");
            return true;
        }

        if (appointFeature.isAppointed(target.getUniqueId(), permSetKey)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", target.getName());
            ph.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.appoint.already_appointed", ph);
            return true;
        }

        if (def.getMaxAppointments() > 0) {
            int currentCount = appointFeature.getAppointmentCountBy(appointer.getUniqueId(), permSetKey);
            if (currentCount >= def.getMaxAppointments()) {
                Map<String, String> ph = new HashMap<>();
                ph.put("max", String.valueOf(def.getMaxAppointments()));
                languageManager.sendMessage(sender, "command.appoint.max_reached", ph);
                return true;
            }
        }

        boolean success = appointFeature.appoint(appointer, target, permSetKey);
        if (success) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", target.getName());
            ph.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.appoint.success", ph);
        } else {
            languageManager.sendMessage(sender, "command.appoint.failed");
        }
        return true;
    }
}
