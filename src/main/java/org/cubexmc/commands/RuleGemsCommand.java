package org.cubexmc.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.manager.ConfigManager;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

public class RuleGemsCommand implements CommandExecutor {
    private static final int HISTORY_PAGE_SIZE = 5;
    private final RuleGems plugin;
    private final GemManager gemManager;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;

    public RuleGemsCommand(RuleGems plugin, GemManager gemManager, ConfigManager configManager, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.configManager = configManager;
        this.languageManager = languageManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!cmd.getName().equalsIgnoreCase("rulegems")) {
            return false;
        }

        if (args.length == 0) {
            languageManager.sendMessage(sender, "command.usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.saveGems();
                plugin.loadPlugin();
                plugin.refreshAllowedCommandProxies();
                languageManager.sendMessage(sender, "command.reload_success");
                return true;
            case "rulers":
                if (require(sender, "rulegems.rulers")) return true;
                java.util.Map<java.util.UUID, java.util.Set<String>> holders = gemManager.getCurrentRulers();
                if (holders.isEmpty()) {
                    languageManager.sendMessage(sender, "command.no_rulers");
                    return true;
                }
                for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> e : holders.entrySet()) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    String name = p != null ? p.getName() : e.getKey().toString();
                    String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", name + " (" + extra + ")");
                    languageManager.sendMessage(sender, "command.rulers_status", placeholders);
                }
                return true;
            case "gems":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.gemStatus(sender);
                return true;
            case "tp":
                if (require(sender, "rulegems.admin")) return true;
                return handleTpCommand(sender, args);
            case "scatter":
                if (require(sender, "rulegems.admin")) return true;
                gemManager.scatterGems();
                languageManager.sendMessage(sender, "command.scatter_success");
                return true;
            case "redeem":
                if (require(sender, "rulegems.redeem")) return true;
                if (!configManager.isRedeemEnabled()) {
                    languageManager.sendMessage(sender, "command.redeem.disabled");
                    return true;
                }
                return handleRedeem(sender, args);
            case "redeemall":
                if (require(sender, "rulegems.redeemall")) return true;
                if (!configManager.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(sender, "command.redeemall.disabled");
                    return true;
                }
                return handleRedeemAll(sender);
            case "place":
                if (require(sender, "rulegems.admin")) return true;
                return handlePlaceCommand(sender, args);
            case "revoke":
                if (require(sender, "rulegems.admin")) return true;
                return handleRevokeCommand(sender, args);
            case "history":
                if (require(sender, "rulegems.admin")) return true;
                return handleHistoryCommand(sender, args);
            case "help":
                sendHelp(sender);
                return true;
            default:
                languageManager.sendMessage(sender, "command.unknown_subcommand");
                return true;
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            languageManager.sendMessage(sender, "command.no_permission");
            return true;
        }
        return false;
    }

    private void sendHelp(CommandSender sender) {
        languageManager.sendMessage(sender, "command.help.header");
        languageManager.sendMessage(sender, "command.help.place");
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
        languageManager.sendMessage(sender, "command.help.rulers");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.history");
        languageManager.sendMessage(sender, "command.help.help");
        languageManager.sendMessage(sender, "command.help.footer");
    }

    private boolean handleTpCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.tp.player_only");
            return true;
        }
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.tp.usage");
            return true;
        }
        Player p = (Player) sender;
        UUID gemId = gemManager.resolveGemIdentifier(args[1]);
        if (gemId == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("input", args[1]);
            languageManager.sendMessage(sender, "command.tp.not_found", placeholders);
            return true;
        }
        // 优先传送到持有者，否则传送到放置位置
        Player realHolder = gemManager.getGemHolder(gemId);
        if (realHolder != null && realHolder.isOnline()) {
            Location dest = realHolder.getLocation();
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        Location loc = gemManager.getGemLocation(gemId);
        if (loc != null) {
            Location dest = loc.clone().add(0.5, 1.0, 0.5);
            org.cubexmc.utils.SchedulerUtil.safeTeleport(plugin, p, dest);
            return true;
        }
        languageManager.sendMessage(sender, "command.tp.unavailable");
        return true;
    }

    private boolean handlePlaceCommand(CommandSender sender, String[] args) {
        // /rulegems place <gemId> <x|~> <y|~> <z|~>
        if (args.length < 5) {
            languageManager.sendMessage(sender, "command.place.usage");
            return true;
        }
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.place.player_only");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        String gemIdentifier = args[1];

        // 处理 ~ 坐标（基于玩家当前位置）
        if (args[2].equals("~")) args[2] = String.valueOf(player.getLocation().getX());
        if (args[3].equals("~")) args[3] = String.valueOf(player.getLocation().getY());
        if (args[4].equals("~")) args[4] = String.valueOf(player.getLocation().getZ());

        double x, y, z;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            languageManager.sendMessage(sender, "command.place.invalid_coordinates");
            return true;
        }

        // 解析 gem 标识（UUID 或 gem key/name）
        UUID gemId = gemManager.resolveGemIdentifier(gemIdentifier);
        if (gemId == null) {
            languageManager.sendMessage(sender, "command.place.invalid_gem");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        // 强制移动该宝石（若被持有则移除背包，若已放置则先清除），确保全服唯一
        // 支撑性预检查：某些方块（如 sculk_catalyst）需要下面有方块
        org.bukkit.Material m = gemManager.getGemMaterial(gemId);
        if (gemManager.isSupportRequired(m) && !gemManager.hasBlockSupport(loc)) {
            languageManager.sendMessage(sender, "command.place.failed_unsupported");
            return true;
        }
        gemManager.forcePlaceGem(gemId, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("x", String.valueOf(x));
        placeholders.put("y", String.valueOf(y));
        placeholders.put("z", String.valueOf(z));
        languageManager.sendMessage(sender, "command.place.success", placeholders);
        return true;
    }

    private boolean handleRedeem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.redeem.player_only");
            return true;
        }
        Player player = (Player) sender;
        // 新行为：仅支持手持兑换
        org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == org.bukkit.Material.AIR) {
            languageManager.sendMessage(sender, "command.redeem.no_item_in_hand");
            return true;
        }
        if (!gemManager.isRuleGem(inHand)) {
            languageManager.sendMessage(sender, "command.redeem.not_a_gem");
            return true;
        }
        boolean ok = gemManager.redeemGemInHand(player);
        if (!ok) {
            languageManager.sendMessage(sender, "command.redeem.failed");
            return true;
        }
        languageManager.sendMessage(sender, "command.redeem.success");
        return true;
    }

    private boolean handleRedeemAll(CommandSender sender) {
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.redeem.player_only");
            return true;
        }
        Player player = (Player) sender;
        boolean ok = gemManager.redeemAll(player);
        if (!ok) {
            languageManager.sendMessage(sender, "command.redeemall.failed");
            return true;
        }
        languageManager.sendMessage(sender, "command.redeemall.success");
        return true;
    }

    private boolean handleRevokeCommand(CommandSender sender, String[] args) {
        // /rulegems revoke <player> - 强制撤销指定玩家的所有宝石权限
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.revoke.usage");
            return true;
        }
        
        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        
        if (targetPlayer == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            languageManager.sendMessage(sender, "command.revoke.player_not_found", placeholders);
            return true;
        }
        
        // 调用 GemManager 撤销该玩家的所有权限
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
        
        // 向目标玩家发送通知
        languageManager.sendMessage(targetPlayer, "command.revoke.revoked_notice");
        
        // 全服广播（可选）
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("rulegems.admin")) {
                languageManager.sendMessage(online, "command.revoke.broadcast", placeholders);
            }
        }
        
        return true;
    }

    private boolean handleHistoryCommand(CommandSender sender, String[] args) {
        org.cubexmc.manager.HistoryLogger historyLogger = plugin.getHistoryLogger();
        if (historyLogger == null) {
            languageManager.sendMessage(sender, "command.history.disabled");
            return true;
        }

        int page = 1;
        String playerFilter = null;

        // 解析参数: /rulegems history [页码] [玩家名]
        if (args.length > 1) {
            if (isInteger(args[1])) {
                page = Math.max(1, Integer.parseInt(args[1]));
            } else {
                playerFilter = args[1];
            }
        }
        if (args.length > 2) {
            if (playerFilter == null && !isInteger(args[2])) {
                playerFilter = args[2];
            } else if (isInteger(args[2])) {
                page = Math.max(1, Integer.parseInt(args[2]));
            }
        }

        org.cubexmc.manager.HistoryLogger.HistoryPage historyPage;
        int totalPages = 1;
        if (playerFilter != null) {
            historyPage = historyLogger.getPlayerHistoryPage(playerFilter, page, HISTORY_PAGE_SIZE);
            if (historyPage.getTotalCount() == 0) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", playerFilter);
                languageManager.sendMessage(sender, "command.history.no_player_records", placeholders);
                return true;
            }
            if (historyPage.getEntries().isEmpty()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("page", String.valueOf(page));
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders);
                return true;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", playerFilter);
            placeholders.put("count", String.valueOf(historyPage.getEntries().size()));
            placeholders.put("total", String.valueOf(historyPage.getTotalCount()));
            totalPages = Math.max(1, (int) Math.ceil(historyPage.getTotalCount() / (double) HISTORY_PAGE_SIZE));
            placeholders.put("page", String.valueOf(page));
            placeholders.put("pages", String.valueOf(totalPages));
            languageManager.sendMessage(sender, "command.history.player_header", placeholders);
        } else {
            historyPage = historyLogger.getRecentHistoryPage(page, HISTORY_PAGE_SIZE);
            if (historyPage.getTotalCount() == 0) {
                languageManager.sendMessage(sender, "command.history.no_records");
                return true;
            }
            if (historyPage.getEntries().isEmpty()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("page", String.valueOf(page));
                languageManager.sendMessage(sender, "command.history.page_out_of_range", placeholders);
                return true;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(historyPage.getEntries().size()));
            placeholders.put("total", String.valueOf(historyPage.getTotalCount()));
            totalPages = Math.max(1, (int) Math.ceil(historyPage.getTotalCount() / (double) HISTORY_PAGE_SIZE));
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
            sendHistoryNavigation(sender, page, totalPages, playerFilter);
        }

        return true;
    }

    private boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void sendHistoryNavigation(CommandSender sender, int currentPage, int totalPages, String playerFilter) {
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
                appendInteractiveComponent(components, prevLabel, prevHover, buildHistoryCommand(prevPage, playerFilter));
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
                appendInteractiveComponent(components, nextLabel, nextHover, buildHistoryCommand(nextPage, playerFilter));
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
        if (text == null || text.isEmpty()) {
            return;
        }
        BaseComponent[] parts = TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text));
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        HoverEvent hoverEvent = null;
        if (hover != null && !hover.isEmpty()) {
            String hoverText = ChatColor.translateAlternateColorCodes('&', hover);
            hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverText));
        }
        for (BaseComponent part : parts) {
            part.setClickEvent(clickEvent);
            if (hoverEvent != null) {
                part.setHoverEvent(hoverEvent);
            }
            components.add(part);
        }
    }

    private void appendStaticComponent(List<BaseComponent> components, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        BaseComponent[] parts = TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text));
        for (BaseComponent part : parts) {
            components.add(part);
        }
    }

    private String buildHistoryCommand(int page, String playerFilter) {
        StringBuilder command = new StringBuilder("/rulegems history ").append(page);
        if (playerFilter != null && !playerFilter.isEmpty()) {
            command.append(' ').append(playerFilter);
        }
        return command.toString();
    }

    private String safeFormat(String path, Map<String, String> placeholders) {
        String value = languageManager.formatMessage("messages." + path, placeholders != null ? placeholders : new HashMap<>());
        if (value == null || value.startsWith("Missing message")) {
            return "";
        }
        return value;
    }
}
