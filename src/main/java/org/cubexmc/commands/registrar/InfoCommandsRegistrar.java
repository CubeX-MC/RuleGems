package org.cubexmc.commands.registrar;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.features.GemNavigator;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.features.revoke.RevokeFeature;
import org.cubexmc.gui.GUIManager;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.LanguageManager;
import org.incendo.cloud.CommandManager;

public class InfoCommandsRegistrar implements CommandRegistrar {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final GUIManager guiManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;

    public InfoCommandsRegistrar(RuleGems plugin, GemManager gemManager, GUIManager guiManager,
            GameplayConfig gameplayConfig, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.guiManager = guiManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
    }

    @Override
    public void register(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .handler(ctx -> {
                    CommandSender sender = ctx.sender().sender();
                    Player player = ctx.sender().player();
                    if (player != null && guiManager != null) {
                        guiManager.openMainMenu(player, player.hasPermission("rulegems.admin"));
                        return;
                    }
                    sendHelp(sender);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("help")
                .permission("rulegems.help")
                .handler(ctx -> sendHelp(ctx.sender().sender())));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("gems")
                .permission("rulegems.gems")
                .handler(ctx -> {
                    CommandSender sender = ctx.sender().sender();
                    Player player = ctx.sender().player();
                    if (player != null && guiManager != null) {
                        guiManager.openGemsGUI(player, sender.hasPermission("rulegems.admin"));
                    } else {
                        gemManager.gemStatus(sender);
                    }
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("rulers")
                .permission("rulegems.rulers")
                .handler(ctx -> {
                    CommandSender sender = ctx.sender().sender();
                    Player player = ctx.sender().player();
                    if (player != null && guiManager != null) {
                        guiManager.openRulersGUI(player, sender.hasPermission("rulegems.admin"));
                    } else {
                        Map<UUID, Set<String>> holders = gemManager.getCurrentRulers();
                        if (holders.isEmpty()) {
                            languageManager.sendMessage(sender, "command.no_rulers");
                            return;
                        }
                        for (Map.Entry<UUID, Set<String>> e : holders.entrySet()) {
                            String name = gemManager.getCachedPlayerName(e.getKey());
                            String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                            Map<String, String> ph = new HashMap<>();
                            ph.put("player", name + " (" + extra + ")");
                            languageManager.sendMessage(sender, "command.rulers_status", ph);
                        }
                    }
                }));
    }

    public void sendHelp(CommandSender sender) {
        languageManager.sendMessage(sender, "command.help.header");
        languageManager.sendMessage(sender, "command.help.overview");

        boolean isPlayer = sender instanceof Player;
        boolean isAdmin = sender.hasPermission("rulegems.admin");
        boolean hasPlayerSection = false;

        if (isPlayer) {
            languageManager.sendMessage(sender, "command.help.section_player");
            hasPlayerSection = true;
            languageManager.sendMessage(sender, "command.help.gui");
        }
        if (sender.hasPermission("rulegems.gems")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.gems");
        }
        if (sender.hasPermission("rulegems.rulers")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.rulers");
        }
        if (isPlayer && sender.hasPermission("rulegems.profile")) {
            languageManager.sendMessage(sender, "command.help.profile");
        }
        if (gameplayConfig.isRedeemEnabled() && sender.hasPermission("rulegems.redeem")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.redeem");
        }
        if (gameplayConfig.isFullSetGrantsAllEnabled() && sender.hasPermission("rulegems.redeemall")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.redeemall");
        }
        if (gameplayConfig.isHoldToRedeemEnabled() && gameplayConfig.isRedeemEnabled()
                && sender.hasPermission("rulegems.redeem")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender,
                    gameplayConfig.isSneakToRedeem() ? "command.help.hold_redeem_sneak"
                            : "command.help.hold_redeem_normal");
        }
        if (gameplayConfig.isPlaceRedeemEnabled()) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.place_redeem");
        }

        GemNavigator navigator = plugin.getFeatureManager() != null ? plugin.getFeatureManager().getNavigator() : null;
        if (navigator != null && navigator.isEnabled() && sender.hasPermission("rulegems.navigate")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.navigate");
        }

        AppointFeature appointFeature = plugin.getFeatureManager() != null
                ? plugin.getFeatureManager().getAppointFeature()
                : null;
        if (appointFeature != null && appointFeature.isEnabled()
                && hasAnyAppointPermission(sender, appointFeature)) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.cabinet");
            languageManager.sendMessage(sender, "command.help.appoint");
            languageManager.sendMessage(sender, "command.help.dismiss");
        }

        RevokeFeature revokeFeature = plugin.getFeatureManager() != null
                ? plugin.getFeatureManager().getRevokeFeature()
                : null;
        if (revokeFeature != null && revokeFeature.isEnabled()
                && sender.hasPermission("rulegems.revoke")) {
            if (!hasPlayerSection) {
                languageManager.sendMessage(sender, "command.help.section_player");
                hasPlayerSection = true;
            }
            languageManager.sendMessage(sender, "command.help.revoke_power");
        }

        if (isAdmin) {
            languageManager.sendMessage(sender, "command.help.section_admin");
            languageManager.sendMessage(sender, "command.help.place");
            languageManager.sendMessage(sender, "command.help.tp");
            languageManager.sendMessage(sender, "command.help.revoke");
            languageManager.sendMessage(sender, "command.help.scatter");
            languageManager.sendMessage(sender, "command.help.history");
            languageManager.sendMessage(sender, "command.help.setaltar");
            languageManager.sendMessage(sender, "command.help.removealtar");
            languageManager.sendMessage(sender, "command.help.appointees");
            languageManager.sendMessage(sender, "command.help.doctor");
            languageManager.sendMessage(sender, "command.help.reload");
        }

        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.links", linkPlaceholders());
        languageManager.sendMessage(sender, "command.help.footer");
    }

    private Map<String, String> linkPlaceholders() {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("docs", plugin.getConfig().getString("links.documentation", "https://github.com/angushushu/RuleGems"));
        placeholders.put("discord", plugin.getConfig().getString("links.discord", "https://discord.com/invite/7tJeSZPZgv"));
        placeholders.put("qq", plugin.getConfig().getString("links.qq", "https://pd.qq.com/s/1n3hpe4e7?b=9"));
        return placeholders;
    }

    private boolean hasAnyAppointPermission(CommandSender sender, AppointFeature feature) {
        if (sender == null || feature == null) {
            return false;
        }
        if (sender.hasPermission("rulegems.admin")) {
            return true;
        }
        for (String key : feature.getAppointDefinitions().keySet()) {
            if (sender.hasPermission("rulegems.appoint." + key.toLowerCase(java.util.Locale.ROOT))
                    || sender.hasPermission("rulegems.appoint." + key)) {
                return true;
            }
        }
        return false;
    }
}
