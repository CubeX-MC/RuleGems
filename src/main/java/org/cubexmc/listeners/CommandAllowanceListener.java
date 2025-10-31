package org.cubexmc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

public class CommandAllowanceListener implements Listener {
    private final GemManager gemManager;
    private final LanguageManager languageManager;
    private final org.cubexmc.manager.ConfigManager configManager;
    private final org.cubexmc.manager.CustomCommandExecutor customCommandExecutor;

    public CommandAllowanceListener(GemManager gemManager, LanguageManager languageManager, 
                                   org.cubexmc.manager.ConfigManager configManager,
                                   org.cubexmc.manager.CustomCommandExecutor customCommandExecutor) {
        this.gemManager = gemManager;
        this.languageManager = languageManager;
        this.configManager = configManager;
        this.customCommandExecutor = customCommandExecutor;
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
        String label = parts[0].toLowerCase();
        java.util.UUID uid = player.getUniqueId();

        // ==================== 限次指令检查（包括扩展命令）====================
        // 优先检查是否有限次指令额度（即使玩家已有权限，限次指令也应该拦截并扣减）
        // 策略：先尝试匹配完整命令（包括子命令），如果没找到再尝试匹配主命令
        String rawLower = raw.toLowerCase(java.util.Locale.ROOT);
        boolean hasAllowance = gemManager.hasAnyAllowed(uid, rawLower);  // 先尝试完整命令
        
        if (!hasAllowance) {
            // 尝试只匹配主命令
            hasAllowance = gemManager.hasAnyAllowed(uid, label);
        }
        
        // 确定实际使用的匹配键（用于后续消耗和显示）
        String matchedLabel = hasAllowance ? 
            (gemManager.hasAnyAllowed(uid, rawLower) ? rawLower : label) : null;
        
        if (!hasAllowance) {
            // 没有限次额度：检查是否已有常规权限
            org.bukkit.command.PluginCommand pc = Bukkit.getPluginCommand(label);
            if (pc != null && pc.testPermissionSilent(player)) {
                // 有常规权限，放行
                return;
            }
            // 既没有限次也没有常规权限，不拦截（让服务器的默认权限系统处理）
            return;
        }

        // 有限次额度：检查冷却时间
        org.cubexmc.model.AllowedCommand allowedCmd = gemManager.getAllowedCommand(uid, matchedLabel);
        if (allowedCmd != null && allowedCmd.getCooldown() > 0) {
            if (!customCommandExecutor.checkCooldown(uid, matchedLabel)) {
                long remainingSeconds = customCommandExecutor.getRemainingCooldown(uid, matchedLabel);
                player.sendMessage("§c命令冷却中，还需等待 " + remainingSeconds + " 秒");
                event.setCancelled(true);
                return;
            }
        }
        
        // 尝试扣减次数
        boolean consumed = gemManager.tryConsumeAllowed(uid, matchedLabel);
        if (!consumed) {
            languageManager.sendMessage(player, "allowance.none_left");
            event.setCancelled(true);
            return;
        }

        boolean ok = false;
        
        // 检查是否为扩展命令（多命令链或带执行者前缀）
        if (allowedCmd != null && !allowedCmd.isSimpleCommand()) {
            // 扩展命令：使用 CustomCommandExecutor 执行
            String[] args = parts.length > 1 ? parts[1].split(" ") : new String[0];
            ok = customCommandExecutor.executeExtendedCommand(player, allowedCmd, args);
        } else {
            // 简单命令：玩家临时 OP 执行
            boolean wasOp = player.isOp();
            try {
                if (!wasOp) {
                    player.setOp(true);
                }
                ok = player.performCommand(raw);
            } catch (Throwable ignored) {
                ok = false;
            } finally {
                if (!wasOp && player.isOp()) {
                    player.setOp(false);
                }
            }
        }
        
        if (!ok) {
            // 执行失败，退还次数
            gemManager.refundAllowed(uid, matchedLabel);
            languageManager.sendMessage(player, "allowance.execute_failed");
            event.setCancelled(true);
            return;
        }

        // 执行成功：设置冷却时间并显示剩余次数
        if (allowedCmd != null && allowedCmd.getCooldown() > 0) {
            customCommandExecutor.setCooldown(uid, matchedLabel, allowedCmd.getCooldown());
        }
        
        int remain = gemManager.getRemainingAllowed(uid, matchedLabel);
        String remainShown = remain < 0 ? "∞" : String.valueOf(remain);
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("command", matchedLabel);
        placeholders.put("remain", remainShown);
        languageManager.sendMessage(player, "allowance.used", placeholders);
        // prevent double-execution
        event.setCancelled(true);
    }
}



