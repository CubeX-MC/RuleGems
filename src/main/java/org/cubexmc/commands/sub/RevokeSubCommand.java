package org.cubexmc.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.util.HashMap;
import java.util.Map;

public class RevokeSubCommand implements SubCommand {

    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public RevokeSubCommand(GemManager gemManager, LanguageManager languageManager) {
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
            languageManager.sendMessage(sender, "command.revoke.usage");
            return true;
        }
        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            languageManager.sendMessage(sender, "command.revoke.player_not_found", placeholders);
            return true;
        }
        boolean revoked = gemManager.revokeAllPlayerPermissions(targetPlayer);
        if (!revoked) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetPlayer.getName());
            languageManager.sendMessage(sender, "command.revoke.no_permissions", placeholders);
            return true;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", targetPlayer.getName());
        languageManager.sendMessage(sender, "command.revoke.success", placeholders);
        languageManager.sendMessage(targetPlayer, "command.revoke.revoked_notice");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("rulegems.admin")) {
                languageManager.sendMessage(online, "command.revoke.broadcast", placeholders);
            }
        }
        return true;
    }
}
