package org.cubexmc.commands.sub;

import java.util.HashMap;
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
 * /rulegems dismiss &lt;perm_set&gt; &lt;player&gt;
 */
public class DismissSubCommand implements SubCommand {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public DismissSubCommand(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
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
            languageManager.sendMessage(sender, "command.dismiss.usage");
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

        Player target = Bukkit.getPlayer(targetName);
        UUID targetUuid = null;
        if (target != null) {
            targetUuid = target.getUniqueId();
        } else {
            for (Appointment appointment : appointFeature.getAppointees(permSetKey)) {
                String cachedName = gemManager.getCachedPlayerName(appointment.getAppointeeUuid());
                if (cachedName.equalsIgnoreCase(targetName)) {
                    targetUuid = appointment.getAppointeeUuid();
                    break;
                }
            }
        }

        if (targetUuid == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", targetName);
            languageManager.sendMessage(sender, "command.dismiss.not_appointed", ph);
            return true;
        }

        Player dismisser = (Player) sender;
        boolean success = appointFeature.dismiss(dismisser, targetUuid, permSetKey);
        if (success) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", targetName);
            ph.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.dismiss.success", ph);
        } else {
            languageManager.sendMessage(sender, "command.dismiss.failed");
        }
        return true;
    }
}
