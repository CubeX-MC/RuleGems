package org.cubexmc.commands.sub;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TpSubCommand implements SubCommand {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public TpSubCommand(RuleGems plugin, GemManager gemManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            languageManager.sendMessage(player, "command.tp.usage");
            return true;
        }
        UUID gemId = gemManager.resolveGemIdentifier(args[0]);
        if (gemId == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("input", args[0]);
            languageManager.sendMessage(player, "command.tp.not_found", placeholders);
            return true;
        }
        // Prefer teleporting to holder, otherwise to placed location
        Player realHolder = gemManager.getGemHolder(gemId);
        if (realHolder != null && realHolder.isOnline()) {
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, player, realHolder.getLocation());
            return true;
        }
        Location loc = gemManager.getGemLocation(gemId);
        if (loc != null) {
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, player, loc.clone().add(0.5, 1.0, 0.5));
            return true;
        }
        languageManager.sendMessage(player, "command.tp.unavailable");
        return true;
    }
}
