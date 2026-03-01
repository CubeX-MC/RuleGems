package org.cubexmc.commands.sub;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.util.HashMap;
import java.util.Map;

public class SetAltarSubCommand implements SubCommand {

    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public SetAltarSubCommand(GemManager gemManager, LanguageManager languageManager) {
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
            languageManager.sendMessage(player, "command.setaltar.usage");
            return true;
        }
        String gemKey = args[0].toLowerCase();
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[0]);
            languageManager.sendMessage(player, "command.setaltar.gem_not_found", placeholders);
            return true;
        }
        Location loc = player.getLocation().getBlock().getLocation();
        gemManager.setGemAltarLocation(gemKey, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        placeholders.put("x", String.valueOf(loc.getBlockX()));
        placeholders.put("y", String.valueOf(loc.getBlockY()));
        placeholders.put("z", String.valueOf(loc.getBlockZ()));
        placeholders.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
        languageManager.sendMessage(player, "command.setaltar.success", placeholders);
        return true;
    }
}
