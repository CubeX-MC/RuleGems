package org.cubexmc.commands.registrar;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.RuleGemsCommandActor;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.gui.GUIManager;
import org.cubexmc.manager.LanguageManager;
import org.incendo.cloud.CommandManager;

public class GuiCommandsRegistrar implements CommandRegistrar {

    private final RuleGems plugin;
    private final GUIManager guiManager;
    private final LanguageManager languageManager;

    public GuiCommandsRegistrar(RuleGems plugin, GUIManager guiManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.languageManager = languageManager;
    }

    @Override
    public void register(CommandManager<RuleGemsCommandActor> m) {
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("gui")
                .handler(ctx -> {
                    Player p = requirePlayer(ctx.sender());
                    if (p == null) return;
                    if (guiManager != null)
                        guiManager.openMainMenu(p, p.hasPermission("rulegems.admin"));
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("menu")
                .handler(ctx -> {
                    Player p = requirePlayer(ctx.sender());
                    if (p == null) return;
                    if (guiManager != null)
                        guiManager.openMainMenu(p, p.hasPermission("rulegems.admin"));
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("profile")
                .permission("rulegems.profile")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    if (guiManager != null)
                        guiManager.openProfileGUI(player);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("status")
                .permission("rulegems.profile")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    if (guiManager != null)
                        guiManager.openProfileGUI(player);
                }));
        m.command(m.commandBuilder("rulegems", "rg")
                .literal("cabinet")
                .handler(ctx -> {
                    Player player = requirePlayer(ctx.sender());
                    if (player == null) return;
                    AppointFeature appointFeature = plugin.getFeatureManager() != null
                            ? plugin.getFeatureManager().getAppointFeature()
                            : null;
                    if (appointFeature == null || !appointFeature.isEnabled()) {
                        languageManager.sendMessage(player, "command.appoint.disabled");
                        return;
                    }
                    if (guiManager == null || !guiManager.canOpenCabinet(player)) {
                        languageManager.sendMessage(player, "command.no_permission");
                        return;
                    }
                    guiManager.openCabinetGUI(player);
                }));
    }

    private Player requirePlayer(RuleGemsCommandActor actor) {
        Player player = actor.player();
        if (player == null) {
            languageManager.sendMessage(actor.sender(), "command.player_only");
        }
        return player;
    }
}
