package org.cubexmc.commands.sub;

import org.bukkit.command.CommandSender;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.util.HashMap;
import java.util.Map;

public class RemoveAltarSubCommand implements SubCommand {

    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public RemoveAltarSubCommand(GemManager gemManager, LanguageManager languageManager) {
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.admin";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            languageManager.sendMessage(sender, "command.removealtar.usage");
            return true;
        }
        String gemKey = args[0].toLowerCase();
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[0]);
            languageManager.sendMessage(sender, "command.removealtar.gem_not_found", placeholders);
            return true;
        }
        if (def.getAltarLocation() == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", gemKey);
            placeholders.put("gem_name", def.getDisplayName());
            languageManager.sendMessage(sender, "command.removealtar.no_altar", placeholders);
            return true;
        }
        gemManager.removeGemAltarLocation(gemKey);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        languageManager.sendMessage(sender, "command.removealtar.success", placeholders);
        return true;
    }
}
