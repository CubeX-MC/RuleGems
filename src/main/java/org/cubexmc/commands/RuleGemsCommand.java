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
import org.cubexmc.gui.GUIManager;
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
    private final GUIManager guiManager;

    public RuleGemsCommand(RuleGems plugin, GemManager gemManager, ConfigManager configManager, LanguageManager languageManager, GUIManager guiManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.configManager = configManager;
        this.languageManager = languageManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!cmd.getName().equalsIgnoreCase("rulegems")) {
            return false;
        }

        if (args.length == 0) {
            // 无参数时直接显示帮助
            sendHelp(sender);
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
            case "gui":
            case "menu":
                // 打开主菜单 GUI
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.gui.player_only");
                    return true;
                }
                if (guiManager != null) {
                    boolean isAdmin = sender.hasPermission("rulegems.admin");
                    guiManager.openMainMenu((Player) sender, isAdmin);
                }
                return true;
            case "rulers":
                if (require(sender, "rulegems.rulers")) return true;
                // 如果是玩家，打开 GUI；否则输出文字
                if (sender instanceof Player && guiManager != null) {
                    boolean isAdmin = sender.hasPermission("rulegems.admin");
                    guiManager.openRulersGUI((Player) sender, isAdmin);
                } else {
                    java.util.Map<java.util.UUID, java.util.Set<String>> holders = gemManager.getCurrentRulers();
                    if (holders.isEmpty()) {
                        languageManager.sendMessage(sender, "command.no_rulers");
                        return true;
                    }
                    for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> e : holders.entrySet()) {
                        // 使用缓存获取玩家名称（支持离线玩家）
                        String name = gemManager.getCachedPlayerName(e.getKey());
                        String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("player", name + " (" + extra + ")");
                        languageManager.sendMessage(sender, "command.rulers_status", placeholders);
                    }
                }
                return true;
            case "gems":
                // gems 指令需要权限检查
                if (require(sender, "rulegems.gems")) return true;
                // 对玩家打开 GUI
                if (sender instanceof Player && guiManager != null) {
                    boolean isAdmin = sender.hasPermission("rulegems.admin");
                    guiManager.openGemsGUI((Player) sender, isAdmin);
                } else {
                    // 控制台显示详细文字信息
                    gemManager.gemStatus(sender);
                }
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
            case "setaltar":
                if (require(sender, "rulegems.admin")) return true;
                return handleSetAltarCommand(sender, args);
            case "removealtar":
                if (require(sender, "rulegems.admin")) return true;
                return handleRemoveAltarCommand(sender, args);
            case "appoint":
                return handleAppointCommand(sender, args);
            case "dismiss":
                return handleDismissCommand(sender, args);
            case "appointees":
                return handleAppointeesCommand(sender, args);
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
        languageManager.sendMessage(sender, "command.help.gui");
        languageManager.sendMessage(sender, "command.help.place");
        languageManager.sendMessage(sender, "command.help.revoke");
        languageManager.sendMessage(sender, "command.help.reload");
        languageManager.sendMessage(sender, "command.help.rulers");
        languageManager.sendMessage(sender, "command.help.gems");
        languageManager.sendMessage(sender, "command.help.scatter");
        languageManager.sendMessage(sender, "command.help.redeem");
        languageManager.sendMessage(sender, "command.help.redeemall");
        languageManager.sendMessage(sender, "command.help.history");
        languageManager.sendMessage(sender, "command.help.setaltar");
        languageManager.sendMessage(sender, "command.help.removealtar");
        languageManager.sendMessage(sender, "command.help.appoint");
        languageManager.sendMessage(sender, "command.help.dismiss");
        languageManager.sendMessage(sender, "command.help.appointees");
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

    private boolean handleSetAltarCommand(CommandSender sender, String[] args) {
        // /rulegems setaltar <gemKey>
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.setaltar.player_only");
            return true;
        }
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.setaltar.usage");
            return true;
        }

        Player player = (Player) sender;
        String gemKey = args[1].toLowerCase();

        // 检查宝石是否存在
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[1]);
            languageManager.sendMessage(sender, "command.setaltar.gem_not_found", placeholders);
            return true;
        }

        // 设置祭坛位置为玩家当前位置
        Location loc = player.getLocation().getBlock().getLocation();
        gemManager.setGemAltarLocation(gemKey, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        placeholders.put("x", String.valueOf(loc.getBlockX()));
        placeholders.put("y", String.valueOf(loc.getBlockY()));
        placeholders.put("z", String.valueOf(loc.getBlockZ()));
        placeholders.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
        languageManager.sendMessage(sender, "command.setaltar.success", placeholders);
        return true;
    }

    private boolean handleRemoveAltarCommand(CommandSender sender, String[] args) {
        // /rulegems removealtar <gemKey>
        if (args.length < 2) {
            languageManager.sendMessage(sender, "command.removealtar.usage");
            return true;
        }

        String gemKey = args[1].toLowerCase();

        // 检查宝石是否存在
        org.cubexmc.model.GemDefinition def = gemManager.findGemDefinitionByKey(gemKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", args[1]);
            languageManager.sendMessage(sender, "command.removealtar.gem_not_found", placeholders);
            return true;
        }

        // 检查是否有祭坛设置
        if (def.getAltarLocation() == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("gem_key", gemKey);
            placeholders.put("gem_name", def.getDisplayName());
            languageManager.sendMessage(sender, "command.removealtar.no_altar", placeholders);
            return true;
        }

        // 移除祭坛位置
        gemManager.removeGemAltarLocation(gemKey);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_key", gemKey);
        placeholders.put("gem_name", def.getDisplayName());
        languageManager.sendMessage(sender, "command.removealtar.success", placeholders);
        return true;
    }

    // ==================== 委任功能命令 ====================

    private boolean handleAppointCommand(CommandSender sender, String[] args) {
        // /rulegems appoint <perm_set> <player>
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.appoint.player_only");
            return true;
        }
        
        if (args.length < 3) {
            languageManager.sendMessage(sender, "command.appoint.usage");
            return true;
        }
        
        org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(sender, "command.appoint.disabled");
            return true;
        }
        
        String permSetKey = args[1].toLowerCase();
        String targetName = args[2];
        
        // 检查权限集是否存在
        org.cubexmc.model.AppointDefinition def = appointFeature.getAppointDefinition(permSetKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("perm_set", args[1]);
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", placeholders);
            return true;
        }
        
        // 检查任命者权限
        Player appointer = (Player) sender;
        if (!appointer.hasPermission("rulegems.appoint." + permSetKey) && !appointer.hasPermission("rulegems.admin")) {
            languageManager.sendMessage(sender, "command.no_permission");
            return true;
        }
        
        // 查找目标玩家
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            languageManager.sendMessage(sender, "command.appoint.player_not_found", placeholders);
            return true;
        }
        
        // 不能任命自己
        if (target.equals(appointer)) {
            languageManager.sendMessage(sender, "command.appoint.cannot_self");
            return true;
        }
        
        // 检查是否已被任命
        if (appointFeature.isAppointed(target.getUniqueId(), permSetKey)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", target.getName());
            placeholders.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.appoint.already_appointed", placeholders);
            return true;
        }
        
        // 检查任命数量限制
        if (def.getMaxAppointments() > 0) {
            int currentCount = appointFeature.getAppointmentCountBy(appointer.getUniqueId(), permSetKey);
            if (currentCount >= def.getMaxAppointments()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("max", String.valueOf(def.getMaxAppointments()));
                languageManager.sendMessage(sender, "command.appoint.max_reached", placeholders);
                return true;
            }
        }
        
        // 执行任命
        boolean success = appointFeature.appoint(appointer, target, permSetKey);
        if (success) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", target.getName());
            placeholders.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.appoint.success", placeholders);
        } else {
            languageManager.sendMessage(sender, "command.appoint.failed");
        }
        
        return true;
    }

    private boolean handleDismissCommand(CommandSender sender, String[] args) {
        // /rulegems dismiss <perm_set> <player>
        if (!(sender instanceof Player)) {
            languageManager.sendMessage(sender, "command.dismiss.player_only");
            return true;
        }
        
        if (args.length < 3) {
            languageManager.sendMessage(sender, "command.dismiss.usage");
            return true;
        }
        
        org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(sender, "command.appoint.disabled");
            return true;
        }
        
        String permSetKey = args[1].toLowerCase();
        String targetName = args[2];
        
        // 检查权限集是否存在
        org.cubexmc.model.AppointDefinition def = appointFeature.getAppointDefinition(permSetKey);
        if (def == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("perm_set", args[1]);
            languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", placeholders);
            return true;
        }
        
        // 查找目标玩家（支持离线）
        Player target = Bukkit.getPlayer(targetName);
        UUID targetUuid = null;
        if (target != null) {
            targetUuid = target.getUniqueId();
        } else {
            // 尝试从已任命列表中查找
            for (org.cubexmc.features.appoint.Appointment appointment : appointFeature.getAppointees(permSetKey)) {
                String cachedName = gemManager.getCachedPlayerName(appointment.getAppointeeUuid());
                if (cachedName.equalsIgnoreCase(targetName)) {
                    targetUuid = appointment.getAppointeeUuid();
                    break;
                }
            }
        }
        
        if (targetUuid == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            languageManager.sendMessage(sender, "command.dismiss.not_appointed", placeholders);
            return true;
        }
        
        // 执行撤销
        Player dismisser = (Player) sender;
        boolean success = appointFeature.dismiss(dismisser, targetUuid, permSetKey);
        if (success) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", targetName);
            placeholders.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            languageManager.sendMessage(sender, "command.dismiss.success", placeholders);
        } else {
            languageManager.sendMessage(sender, "command.dismiss.failed");
        }
        
        return true;
    }

    private boolean handleAppointeesCommand(CommandSender sender, String[] args) {
        // /rulegems appointees [perm_set]
        org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager().getAppointFeature();
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(sender, "command.appoint.disabled");
            return true;
        }
        
        if (args.length < 2) {
            // 显示所有权限集的被任命者
            languageManager.sendMessage(sender, "command.appointees.header");
            
            Map<String, org.cubexmc.model.AppointDefinition> definitions = appointFeature.getAppointDefinitions();
            if (definitions.isEmpty()) {
                languageManager.sendMessage(sender, "command.appointees.no_perm_sets");
                return true;
            }
            
            for (org.cubexmc.model.AppointDefinition def : definitions.values()) {
                List<org.cubexmc.features.appoint.Appointment> appointees = appointFeature.getAppointees(def.getKey());
                
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
                placeholders.put("count", String.valueOf(appointees.size()));
                languageManager.sendMessage(sender, "command.appointees.set_header", placeholders);
                
                if (appointees.isEmpty()) {
                    languageManager.sendMessage(sender, "command.appointees.none");
                } else {
                    for (org.cubexmc.features.appoint.Appointment appointment : appointees) {
                        String appointeeName = gemManager.getCachedPlayerName(appointment.getAppointeeUuid());
                        String appointerName = appointment.getAppointerUuid() != null ? 
                            gemManager.getCachedPlayerName(appointment.getAppointerUuid()) : "System";
                        
                        Map<String, String> linePh = new HashMap<>();
                        linePh.put("appointee", appointeeName);
                        linePh.put("appointer", appointerName);
                        languageManager.sendMessage(sender, "command.appointees.entry", linePh);
                    }
                }
            }
        } else {
            // 显示特定权限集的被任命者
            String permSetKey = args[1].toLowerCase();
            org.cubexmc.model.AppointDefinition def = appointFeature.getAppointDefinition(permSetKey);
            
            if (def == null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("perm_set", args[1]);
                languageManager.sendMessage(sender, "command.appoint.invalid_perm_set", placeholders);
                return true;
            }
            
            List<org.cubexmc.features.appoint.Appointment> appointees = appointFeature.getAppointees(permSetKey);
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("perm_set", ChatColor.translateAlternateColorCodes('&', def.getDisplayName()));
            placeholders.put("count", String.valueOf(appointees.size()));
            languageManager.sendMessage(sender, "command.appointees.set_header", placeholders);
            
            if (appointees.isEmpty()) {
                languageManager.sendMessage(sender, "command.appointees.none");
            } else {
                for (org.cubexmc.features.appoint.Appointment appointment : appointees) {
                    String appointeeName = gemManager.getCachedPlayerName(appointment.getAppointeeUuid());
                    String appointerName = appointment.getAppointerUuid() != null ? 
                        gemManager.getCachedPlayerName(appointment.getAppointerUuid()) : "System";
                    
                    Map<String, String> linePh = new HashMap<>();
                    linePh.put("appointee", appointeeName);
                    linePh.put("appointer", appointerName);
                    languageManager.sendMessage(sender, "command.appointees.entry", linePh);
                }
            }
        }
        
        return true;
    }
}
