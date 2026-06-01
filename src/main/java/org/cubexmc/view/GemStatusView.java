package org.cubexmc.view;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.manager.GemStateManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.GemDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the formatting and sending of gem status information to players.
 * Separates view logic from the underlying state management.
 */
public class GemStatusView {

    private final GemStateManager stateManager;
    private final LanguageManager languageManager;

    public GemStatusView(GemStateManager stateManager, LanguageManager languageManager) {
        this.stateManager = stateManager;
        this.languageManager = languageManager;
    }

    public void sendStatus(CommandSender sender, int expectedCount, int placedCount, int heldCount) {
        Map<String, String> summary = new HashMap<>();
        summary.put("count", String.valueOf(expectedCount));
        summary.put("placed_count", String.valueOf(placedCount));
        summary.put("held_count", String.valueOf(heldCount));
        
        sender.sendMessage(languageManager.translateColorCodes(languageManager.formatMessage("gui.gem_status.total_expected", summary)));
        sender.sendMessage(languageManager.translateColorCodes(languageManager.formatMessage("gui.gem_status.total_counts", summary)));

        List<Map.Entry<UUID, String>> entries = new java.util.ArrayList<>(stateManager.getAllGemUuidsAndKeys());
        entries.sort((a, b) -> {
            String ka = a.getValue() != null ? a.getValue().toLowerCase() : "";
            String kb = b.getValue() != null ? b.getValue().toLowerCase() : "";
            int c = ka.compareTo(kb);
            if (c != 0) return c;
            return a.getKey().toString().compareTo(b.getKey().toString());
        });

        boolean isPlayerSender = sender instanceof Player;
        
        for (Map.Entry<UUID, String> ent : entries) {
            UUID gemId = ent.getKey();
            String gemKey = ent.getValue();
            GemDefinition def = gemKey != null ? stateManager.findGemDefinition(gemKey) : null;
            String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : "Gem";

            String statusText;
            Player holder = stateManager.getGemHolder(gemId);
            Location loc = stateManager.getGemLocation(gemId);

            if (holder != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("player", holder.getName());
                statusText = languageManager.formatMessage("gui.gem_status.status_held", ph);
            } else if (loc != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("x", String.valueOf(loc.getBlockX()));
                ph.put("y", String.valueOf(loc.getBlockY()));
                ph.put("z", String.valueOf(loc.getBlockZ()));
                ph.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "?");
                statusText = languageManager.formatMessage("gui.gem_status.status_placed", ph);
            } else {
                statusText = languageManager.getMessage("gui.gem_status.status_unknown");
            }

            Map<String, String> linePh = new HashMap<>();
            linePh.put("gem_key", gemKey != null ? gemKey : "?");
            linePh.put("gem_name", displayName);
            linePh.put("uuid", gemId.toString().substring(0, 8));
            linePh.put("status", statusText);
            String plain = languageManager.formatMessage("gui.gem_status.gem_line", linePh);

            if (isPlayerSender) {
                sendClickableGemStatus((Player) sender, gemId, plain, def);
            } else {
                sender.sendMessage(ChatColor.stripColor(languageManager.translateColorCodes(plain)));
            }
        }
    }

    private void sendClickableGemStatus(Player player, UUID gemId, String plain, GemDefinition def) {
        net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                        org.cubexmc.utils.ColorUtils.translateColorCodes(plain)));

        StringBuilder loreBuilder = new StringBuilder();
        if (def != null && def.getLore() != null && !def.getLore().isEmpty()) {
            for (String line : def.getLore()) {
                loreBuilder.append(org.cubexmc.utils.ColorUtils.translateColorCodes(line)).append("\n");
            }
        } else {
            String noMoreInfo = languageManager != null ? languageManager.getMessage("gui.no_more_info")
                    : "No more info";
            loreBuilder.append(ChatColor.GRAY).append(noMoreInfo);
        }

        net.md_5.bungee.api.chat.hover.content.Text text = new net.md_5.bungee.api.chat.hover.content.Text(
                loreBuilder.toString().trim());
        comp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, text));

        String clickCmd = "/rulegems tp " + gemId.toString();
        comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, clickCmd));

        player.spigot().sendMessage(comp);
    }
}