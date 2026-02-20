package org.cubexmc.listeners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;

import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.LanguageManager;

public class CommandAllowanceListener implements Listener {
    private final GemAllowanceManager allowanceManager;
    private final LanguageManager languageManager;
    private final org.cubexmc.manager.CustomCommandExecutor customCommandExecutor;
    private final org.cubexmc.manager.GameplayConfig gameplayConfig;
    private final Set<String> proxyLabels = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bypassPlayers = ConcurrentHashMap.newKeySet();

    public CommandAllowanceListener(GemAllowanceManager allowanceManager, LanguageManager languageManager,
                                   org.cubexmc.manager.CustomCommandExecutor customCommandExecutor,
                                   org.cubexmc.manager.GameplayConfig gameplayConfig) {
        this.allowanceManager = allowanceManager;
        this.languageManager = languageManager;
        this.customCommandExecutor = customCommandExecutor;
        this.gameplayConfig = gameplayConfig;
    }

    /**
     * Update the set of command labels that are backed by dedicated proxy commands.
     */
    public void updateProxyLabels(Set<String> labels) {
        proxyLabels.clear();
        if (labels != null) {
            for (String label : labels) {
                if (label == null || label.isEmpty()) {
                    continue;
                }
                proxyLabels.add(label.toLowerCase(Locale.ROOT));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        Set<String> allowed = allowanceManager.getAvailableCommandLabels(player.getUniqueId());
        if (allowed.isEmpty()) {
            return;
        }
        Collection<String> commands = event.getCommands();
        for (String label : allowed) {
            if (label == null || label.isEmpty()) {
                continue;
            }
            commands.add(label);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player)) {
            return;
        }
        String buffer = event.getBuffer();
        if (buffer == null || buffer.isEmpty() || buffer.charAt(0) != '/') {
            return;
        }
        Player player = (Player) event.getSender();
        Set<String> allowed = allowanceManager.getAvailableCommandLabels(player.getUniqueId());
        if (allowed.isEmpty()) {
            return;
        }

        String withoutSlash = buffer.substring(1);
        boolean trailingSpace = buffer.endsWith(" ");
        String[] parts = withoutSlash.split(" ", -1);
        if (parts.length == 0) {
            return;
        }

        List<String> completions = new ArrayList<>(event.getCompletions());
        if (parts.length == 1 && !trailingSpace) {
            String partial = parts[0].toLowerCase(Locale.ROOT);
            for (String label : allowed) {
                if (partial.isEmpty() || label.startsWith(partial)) {
                    completions.add(label);
                }
            }
            if (!completions.isEmpty()) {
                event.setCompletions(distinct(completions));
            }
            return;
        }

        String base = parts[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(base)) {
            return;
        }

        if (parts.length == 1 && trailingSpace) {
            // command typed exactly, no argument suggestions available yet
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        Player player = event.getPlayer();
        if (msg == null || msg.isEmpty() || player == null) return;
        if (msg.charAt(0) != '/') return;

        String raw = msg.substring(1).trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split(" ", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);

        if (proxyLabels.contains(label)) {
            // Proxied command: let the registered command handle execution.
            return;
        }

        if (bypassPlayers.remove(player.getUniqueId())) {
            return;
        }

        String[] args = parts.length > 1 ? parts[1].split(" ") : new String[0];
        boolean handled = handleAllowedCommand(player, raw, label, args, true);
        if (handled) {
            event.setCancelled(true);
        }
    }

    public void handleProxyExecution(Player player, String raw, String label, String[] args) {
        handleAllowedCommand(player, raw, label, args, false);
    }

    public List<String> suggestProxyTab(Player player, String alias, String[] args) {
        if (player == null) {
            return Collections.emptyList();
        }
        Set<String> allowed = allowanceManager.getAvailableCommandLabels(player.getUniqueId());
        if (allowed.isEmpty()) {
            return Collections.emptyList();
        }
        if (args.length == 0) {
            return allowed.contains(alias) ? Collections.singletonList(alias) : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private boolean handleAllowedCommand(Player player,
                                         String raw,
                                         String baseLabel,
                                         String[] args,
                                         boolean allowDelegate) {
        if (player == null || raw == null || raw.isEmpty()) {
            return false;
        }

        UUID uid = player.getUniqueId();
        String rawLower = raw.toLowerCase(Locale.ROOT);
        String labelLower = baseLabel.toLowerCase(Locale.ROOT);

        boolean fullMatch = allowanceManager.hasAnyAllowed(uid, rawLower);
        boolean baseMatch = fullMatch || allowanceManager.hasAnyAllowed(uid, labelLower);

        if (!baseMatch) {
            if (!allowDelegate) {
                languageManager.sendMessage(player, "command.no_permission");
                return true;
            }
            org.bukkit.command.PluginCommand pc = Bukkit.getPluginCommand(labelLower);
            if (pc != null && pc.testPermissionSilent(player)) {
                return false;
            }
            return false;
        }

        String matchedLabel = fullMatch ? rawLower : labelLower;
        org.cubexmc.model.AllowedCommand allowedCmd = allowanceManager.getAllowedCommand(uid, matchedLabel);
        if (allowedCmd != null && allowedCmd.getCooldown() > 0) {
            if (!customCommandExecutor.checkCooldown(uid, matchedLabel)) {
                long remainingSeconds = customCommandExecutor.getRemainingCooldown(uid, matchedLabel);
                java.util.Map<String, String> cooldownPlaceholders = new java.util.HashMap<>();
                cooldownPlaceholders.put("seconds", String.valueOf(remainingSeconds));
                languageManager.sendMessage(player, "allowance.cooldown", cooldownPlaceholders);
                return true;
            }
        }

        boolean consumed = allowanceManager.tryConsumeAllowed(uid, matchedLabel);
        if (!consumed) {
            languageManager.sendMessage(player, "allowance.none_left");
            return true;
        }

        boolean ok;
        if (allowedCmd != null && !allowedCmd.isSimpleCommand()) {
            ok = customCommandExecutor.executeExtendedCommand(player, allowedCmd, args);
        } else {
            boolean useOp = gameplayConfig != null && gameplayConfig.isOpEscalationAllowed();
            if (useOp) {
                // OP 提权模式（管理员显式启用）
                boolean wasOp = player.isOp();
                try {
                    if (!wasOp) {
                        player.setOp(true);
                    }
                    bypassPlayers.add(uid);
                    ok = player.performCommand(raw);
                } catch (Throwable ignored) {
                    ok = false;
                } finally {
                    bypassPlayers.remove(uid);
                    if (!wasOp && player.isOp()) {
                        player.setOp(false);
                    }
                }
            } else {
                // 安全模式：以控制台身份在全局线程执行（Folia 安全）
                ok = customCommandExecutor.dispatchAsConsole(raw);
            }
        }

        if (!ok) {
            allowanceManager.refundAllowed(uid, matchedLabel);
            languageManager.sendMessage(player, "allowance.execute_failed");
            return true;
        }

        if (allowedCmd != null && allowedCmd.getCooldown() > 0) {
            customCommandExecutor.setCooldown(uid, matchedLabel, allowedCmd.getCooldown());
        }

        int remain = allowanceManager.getRemainingAllowed(uid, matchedLabel);
        String remainShown = remain < 0 ? "∞" : String.valueOf(remain);
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("command", matchedLabel);
        placeholders.put("remain", remainShown);
        languageManager.sendMessage(player, "allowance.used", placeholders);
        return true;
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}



