package org.cubexmc.commands.sub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.LanguageManager;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

/**
 * /rulegems history [page] [player]
 */
public class HistorySubCommand implements SubCommand {

    private static final int PAGE_SIZE = 5;

    private final RuleGems plugin;
    private final LanguageManager languageManager;

    public HistorySubCommand(RuleGems plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.admin";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        org.cubexmc.manager.HistoryLogger historyLogger = plugin.getHistoryLogger();
        if (historyLogger == null) {
            languageManager.sendMessage(sender, "command.history.disabled");
            return true;
        }

        int page = 1;
        String playerFilter = null;

        if (args.length > 0) {
            if (isInteger(args[0])) {
                page = Math.max(1, Integer.parseInt(args[0]));
            } else {
                playerFilter = args[0];
            }
        }
        if (args.length > 1) {
            if (playerFilter == null && !isInteger(args[1])) {
                playerFilter = args[1];
            } else if (isInteger(args[1])) {
                page = Math.max(1, Integer.parseInt(args[1]));
            }
        }

        final int finalPage = page;
        final String finalPlayerFilter = playerFilter;
        org.cubexmc.utils.SchedulerUtil.asyncRun(plugin, () -> {
            org.cubexmc.manager.HistoryLogger.HistoryPage historyPage;
            if (finalPlayerFilter != null) {
                historyPage = historyLogger.getPlayerHistoryPage(finalPlayerFilter, finalPage, PAGE_SIZE);
            } else {
                historyPage = historyLogger.getRecentHistoryPage(finalPage, PAGE_SIZE);
            }
            org.cubexmc.utils.SchedulerUtil.globalRun(plugin, () ->
                    displayResult(sender, historyPage, finalPage, finalPlayerFilter), 0, -1);
        }, 0);

        return true;
    }

    private void displayResult(CommandSender sender,
                               org.cubexmc.manager.HistoryLogger.HistoryPage historyPage,
                               int page, String playerFilter) {
        int totalPages;
        if (playerFilter != null) {
            if (historyPage.getTotalCount() == 0) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", playerFilter);
                languageManager.sendMessage(sender, "command.history.no_player_records", placeholders);
                return;
            }
            if (historyPage.getEntries().isEmpty()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("page", String.valueOf(page));
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders);
                return;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", playerFilter);
            placeholders.put("count", String.valueOf(historyPage.getEntries().size()));
            placeholders.put("total", String.valueOf(historyPage.getTotalCount()));
            totalPages = Math.max(1, (int) Math.ceil(historyPage.getTotalCount() / (double) PAGE_SIZE));
            placeholders.put("page", String.valueOf(page));
            placeholders.put("pages", String.valueOf(totalPages));
            languageManager.sendMessage(sender, "command.history.player_header", placeholders);
        } else {
            if (historyPage.getTotalCount() == 0) {
                languageManager.sendMessage(sender, "command.history.no_records");
                return;
            }
            if (historyPage.getEntries().isEmpty()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("page", String.valueOf(page));
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders);
                return;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(historyPage.getEntries().size()));
            placeholders.put("total", String.valueOf(historyPage.getTotalCount()));
            totalPages = Math.max(1, (int) Math.ceil(historyPage.getTotalCount() / (double) PAGE_SIZE));
            placeholders.put("page", String.valueOf(page));
            placeholders.put("pages", String.valueOf(totalPages));
            languageManager.sendMessage(sender, "command.history.recent_header", placeholders);
        }

        for (String line : historyPage.getEntries()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("line", line);
            languageManager.sendMessage(sender, "command.history.line", placeholders);
        }

        if (totalPages > 1) {
            sendNavigation(sender, page, totalPages, playerFilter);
        }
    }

    private void sendNavigation(CommandSender sender, int currentPage, int totalPages, String playerFilter) {
        int prevPage = currentPage > 1 ? currentPage - 1 : -1;
        int nextPage = currentPage < totalPages ? currentPage + 1 : -1;

        if (sender instanceof Player) {
            Player player = (Player) sender;
            List<BaseComponent> components = new ArrayList<>();

            Map<String, String> basePlaceholders = new HashMap<>();
            basePlaceholders.put("page", String.valueOf(currentPage));
            basePlaceholders.put("pages", String.valueOf(totalPages));

            String divider = safeFormat("command.history.page_nav_divider", basePlaceholders);

            if (prevPage > 0) {
                Map<String, String> prevPlaceholders = new HashMap<>(basePlaceholders);
                prevPlaceholders.put("target", String.valueOf(prevPage));
                prevPlaceholders.put("page", String.valueOf(prevPage));
                String prevLabel = safeFormat("command.history.page_nav_previous", prevPlaceholders);
                String prevHover = safeFormat("command.history.page_nav_hover", prevPlaceholders);
                appendInteractiveComponent(components, prevLabel, prevHover, buildCommand(prevPage, playerFilter));
            } else {
                String prevDisabled = safeFormat("command.history.page_nav_previous_disabled", basePlaceholders);
                appendStaticComponent(components, prevDisabled);
            }

            if (nextPage > 0) {
                Map<String, String> nextPlaceholders = new HashMap<>(basePlaceholders);
                nextPlaceholders.put("target", String.valueOf(nextPage));
                nextPlaceholders.put("page", String.valueOf(nextPage));
                String nextLabel = safeFormat("command.history.page_nav_next", nextPlaceholders);
                String nextHover = safeFormat("command.history.page_nav_hover", nextPlaceholders);
                if (!components.isEmpty() && !divider.isEmpty()) {
                    appendStaticComponent(components, divider);
                }
                appendInteractiveComponent(components, nextLabel, nextHover, buildCommand(nextPage, playerFilter));
            } else {
                String nextDisabled = safeFormat("command.history.page_nav_next_disabled", basePlaceholders);
                if (!nextDisabled.isEmpty()) {
                    if (!components.isEmpty() && !divider.isEmpty()) {
                        appendStaticComponent(components, divider);
                    }
                    appendStaticComponent(components, nextDisabled);
                }
            }

            if (!components.isEmpty()) {
                player.spigot().sendMessage(components.toArray(new BaseComponent[0]));
            }
        } else {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("page", String.valueOf(currentPage));
            placeholders.put("pages", String.valueOf(totalPages));
            placeholders.put("prev", prevPage > 0 ? String.valueOf(prevPage) : "-");
            placeholders.put("next", nextPage > 0 ? String.valueOf(nextPage) : "-");
            languageManager.sendMessage(sender, "command.history.pagination_hint", placeholders);
        }
    }

    private void appendInteractiveComponent(List<BaseComponent> components, String text, String hover, String command) {
        if (text == null || text.isEmpty()) return;
        BaseComponent[] parts = TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text));
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        HoverEvent hoverEvent = null;
        if (hover != null && !hover.isEmpty()) {
            hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(ChatColor.translateAlternateColorCodes('&', hover)));
        }
        for (BaseComponent part : parts) {
            part.setClickEvent(clickEvent);
            if (hoverEvent != null) part.setHoverEvent(hoverEvent);
            components.add(part);
        }
    }

    private void appendStaticComponent(List<BaseComponent> components, String text) {
        if (text == null || text.isEmpty()) return;
        for (BaseComponent part : TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text))) {
            components.add(part);
        }
    }

    private String buildCommand(int page, String playerFilter) {
        StringBuilder sb = new StringBuilder("/rulegems history ").append(page);
        if (playerFilter != null && !playerFilter.isEmpty()) {
            sb.append(' ').append(playerFilter);
        }
        return sb.toString();
    }

    private String safeFormat(String path, Map<String, String> placeholders) {
        String value = languageManager.formatMessage("messages." + path,
                placeholders != null ? placeholders : new HashMap<>());
        if (value == null || value.startsWith("Missing message")) return "";
        return value;
    }

    private boolean isInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        try { Integer.parseInt(value); return true; }
        catch (NumberFormatException ignored) { return false; }
    }
}
