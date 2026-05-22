package org.cubexmc.commands.sub;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.features.revoke.RevokeFeature;
import org.cubexmc.features.revoke.RevokeResult;
import org.cubexmc.manager.LanguageManager;

public class RevokePowerSubCommand implements SubCommand {

    private final RuleGems plugin;
    private final LanguageManager languageManager;

    public RevokePowerSubCommand(RuleGems plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.revoke";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.player_only");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(getPermission())) {
            languageManager.sendMessage(player, "command.no_permission");
            return true;
        }
        RevokeFeature feature = plugin.getFeatureManager() != null ? plugin.getFeatureManager().getRevokeFeature() : null;
        if (feature == null) {
            languageManager.sendMessage(player, "command.revoke_power.disabled");
            return true;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            languageManager.sendMessage(player, "command.revoke_power.usage");
            return true;
        }
        RevokeResult result;
        if ("confirm".equalsIgnoreCase(args[0])) {
            result = feature.confirm(player);
        } else if ("cancel".equalsIgnoreCase(args[0])) {
            result = feature.cancel(player);
        } else if ("list".equalsIgnoreCase(args[0])) {
            result = feature.listRules();
        } else {
            if (args.length < 3) {
                languageManager.sendMessage(player, "command.revoke_power.usage");
                return true;
            }
            result = feature.requestRevoke(player, args[0], args[1], args[2]);
        }
        sendResult(player, result);
        return true;
    }

    private void sendResult(Player actor, RevokeResult result) {
        Map<String, String> placeholders = new HashMap<>(result.getPlaceholders());
        switch (result.getStatus()) {
            case SUCCESS:
                languageManager.sendMessage(actor, "command.revoke_power.success", placeholders);
                if ("true".equalsIgnoreCase(placeholders.get("broadcast"))) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.equals(actor)) {
                            languageManager.sendMessage(online, "command.revoke_power.broadcast", placeholders);
                        }
                    }
                }
                break;
            case CONFIRMATION_REQUIRED:
                languageManager.sendMessage(actor, "command.revoke_power.confirm_prompt", placeholders);
                languageManager.sendMessage(actor, "command.revoke_power.confirm_hint", placeholders);
                break;
            case LIST:
                if (placeholders.getOrDefault("rules", "").isBlank()) {
                    languageManager.sendMessage(actor, "command.revoke_power.list_empty");
                } else {
                    languageManager.sendMessage(actor, "command.revoke_power.list_header");
                    for (String line : placeholders.get("rules").split("\\n")) {
                        languageManager.sendMessage(actor, "command.revoke_power.list_line", Map.of("line", line));
                    }
                }
                break;
            case DISABLED:
                languageManager.sendMessage(actor, "command.revoke_power.disabled");
                break;
            case RULE_NOT_FOUND:
                languageManager.sendMessage(actor, "command.revoke_power.rule_not_found", placeholders);
                break;
            case POWER_NOT_ALLOWED:
                languageManager.sendMessage(actor, "command.revoke_power.power_not_allowed", placeholders);
                break;
            case TARGET_NOT_FOUND:
                languageManager.sendMessage(actor, "command.revoke_power.target_not_found", placeholders);
                break;
            case TARGET_OFFLINE_NOT_ALLOWED:
                languageManager.sendMessage(actor, "command.revoke_power.target_offline_not_allowed", placeholders);
                break;
            case TARGET_HAS_NO_POWER:
                languageManager.sendMessage(actor, "command.revoke_power.target_has_no_power", placeholders);
                break;
            case MISSING_TRIGGER:
                languageManager.sendMessage(actor, "command.revoke_power.missing_trigger", placeholders);
                break;
            case COOLDOWN:
                languageManager.sendMessage(actor, "command.revoke_power.cooldown", placeholders);
                break;
            case NO_PENDING_CONFIRMATION:
                languageManager.sendMessage(actor, "command.revoke_power.no_pending_confirmation");
                break;
            case CANCELLED:
                languageManager.sendMessage(actor, "command.revoke_power.cancelled");
                break;
            case CONFIRMATION_EXPIRED:
                languageManager.sendMessage(actor, "command.revoke_power.confirmation_expired");
                break;
            default:
                languageManager.sendMessage(actor, "command.revoke_power.failed");
                break;
        }
    }
}
