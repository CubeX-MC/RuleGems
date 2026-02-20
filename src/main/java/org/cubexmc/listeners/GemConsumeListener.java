package org.cubexmc.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.utils.SchedulerUtil;

/**
 * 长按右键消耗宝石进行兑换的监听器
 */
public class GemConsumeListener implements Listener {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;

    // 玩家正在进行的长按操作
    private final Map<UUID, ConsumeProgress> activeConsumers = new HashMap<>();

    // 配置常量
    private static final int CHECK_INTERVAL_TICKS = 2;    // 每2tick检查一次
    private static final int PROGRESS_BAR_LENGTH = 20;    // 进度条长度
    private static final long INTERACT_TIMEOUT_MS = 300;  // 右键释放检测超时

    /**
     * 获取配置的长按时长（tick）
     */
    private int getConsumeDurationTicks() {
        return gameplayConfig.getHoldToRedeemDurationTicks();
    }

    public GemConsumeListener(RuleGems plugin, GemManager gemManager, 
                              GameplayConfig gameplayConfig, LanguageManager languageManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
    }

    /**
     * 检查功能是否启用
     */
    private boolean isEnabled() {
        return gameplayConfig.isHoldToRedeemEnabled();
    }

    /**
     * 处理右键交互事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isEnabled()) return;
        
        // 只处理右键（空气或方块）
        if (event.getAction() != Action.RIGHT_CLICK_AIR && 
            event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 只处理主手
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // 检查是否是宝石
        if (!gemManager.isRuleGem(item)) return;

        // 根据配置检查下蹲状态
        boolean sneakToRedeem = gameplayConfig.isSneakToRedeem();
        boolean isSneaking = player.isSneaking();
        
        // sneakToRedeem=true: 需要下蹲才能兑换，不下蹲则允许放置
        // sneakToRedeem=false: 不下蹲才能兑换，下蹲则允许放置
        if (sneakToRedeem && !isSneaking) {
            // 配置要求下蹲兑换，但玩家没下蹲，允许正常放置
            return;
        }
        if (!sneakToRedeem && isSneaking) {
            // 配置要求普通兑换，但玩家在下蹲，允许正常放置
            return;
        }

        // 检查玩家是否有兑换权限
        if (!player.hasPermission("rulegems.redeem")) return;

        // 检查兑换功能是否启用
        if (!gameplayConfig.isRedeemEnabled()) return;

        UUID playerId = player.getUniqueId();

        // 如果已经在消耗中，更新最后交互时间
        if (activeConsumers.containsKey(playerId)) {
            ConsumeProgress progress = activeConsumers.get(playerId);
            progress.lastInteractTime = System.currentTimeMillis();
            return;
        }

        // 开始新的消耗过程
        startConsuming(player, item);
        
        // 阻止其他交互（如放置方块）
        event.setCancelled(true);
    }

    /**
     * 开始消耗宝石
     */
    private void startConsuming(Player player, ItemStack item) {
        UUID playerId = player.getUniqueId();
        UUID gemId = gemManager.getGemUUID(item);
        
        ConsumeProgress progress = new ConsumeProgress();
        progress.gemId = gemId;
        progress.startTime = System.currentTimeMillis();
        progress.lastInteractTime = System.currentTimeMillis();
        progress.itemSnapshot = item.clone();
        
        activeConsumers.put(playerId, progress);

        // 播放开始音效
        try {
            player.playSound(player.getLocation(), 
                org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.5f, 0.8f);
        } catch (Exception e) { plugin.getLogger().fine("Failed to play consume start sound: " + e.getMessage()); }

        // 启动进度检查任务
        scheduleProgressCheck(player);
    }

    /**
     * 调度进度检查
     */
    private void scheduleProgressCheck(Player player) {
        UUID playerId = player.getUniqueId();

        SchedulerUtil.entityRun(plugin, player, () -> {
            if (!activeConsumers.containsKey(playerId)) return;
            
            ConsumeProgress progress = activeConsumers.get(playerId);
            
            // 检查玩家是否还在线
            if (!player.isOnline()) {
                cancelConsuming(player, false);
                return;
            }

            // 检查是否超时（玩家停止右键）
            long timeSinceLastInteract = System.currentTimeMillis() - progress.lastInteractTime;
            if (timeSinceLastInteract > INTERACT_TIMEOUT_MS) {
                cancelConsuming(player, true);
                return;
            }

            // 检查手中物品是否还是同一个宝石
            ItemStack currentItem = player.getInventory().getItemInMainHand();
            if (!gemManager.isRuleGem(currentItem)) {
                cancelConsuming(player, true);
                return;
            }
            UUID currentGemId = gemManager.getGemUUID(currentItem);
            if (progress.gemId != null && !progress.gemId.equals(currentGemId)) {
                cancelConsuming(player, true);
                return;
            }

            // 计算进度
            long elapsed = System.currentTimeMillis() - progress.startTime;
            long requiredMs = (getConsumeDurationTicks() * 50); // ticks to ms
            float progressPercent = Math.min(1.0f, (float) elapsed / requiredMs);

            // 显示进度条
            showProgressBar(player, progressPercent);

            // 播放进度音效
            if (progress.tickCount % 10 == 0) {
                try {
                    player.playSound(player.getLocation(),
                        org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.3f, 0.8f + progressPercent * 0.4f);
                } catch (Exception e) { plugin.getLogger().fine("Failed to play consume progress sound: " + e.getMessage()); }
            }
            progress.tickCount++;

            // 检查是否完成
            if (progressPercent >= 1.0f) {
                completeConsuming(player);
                return;
            }

            // 继续检查
            SchedulerUtil.entityRun(plugin, player, () -> scheduleProgressCheck(player), 
                CHECK_INTERVAL_TICKS, -1L);
        }, CHECK_INTERVAL_TICKS, -1L);
    }

    /**
     * 显示进度条
     */
    private void showProgressBar(Player player, float progress) {
        int filled = (int) (progress * PROGRESS_BAR_LENGTH);
        int empty = PROGRESS_BAR_LENGTH - filled;

        StringBuilder bar = new StringBuilder();
        bar.append(ChatColor.GREEN);
        for (int i = 0; i < filled; i++) {
            bar.append("█");
        }
        bar.append(ChatColor.GRAY);
        for (int i = 0; i < empty; i++) {
            bar.append("░");
        }

        // 计算百分比
        int percent = (int) (progress * 100);
        
        // 使用语言文件中的格式
        String message = languageManager.getMessage("messages.hold_redeem.progress_bar")
            .replace("%bar%", bar.toString())
            .replace("%percent%", String.valueOf(percent));
        message = ChatColor.translateAlternateColorCodes('&', message);

        player.spigot().sendMessage(
            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message)
        );
    }

    /**
     * 完成消耗，触发兑换
     */
    private void completeConsuming(Player player) {
        UUID playerId = player.getUniqueId();
        activeConsumers.remove(playerId);

        // 清除进度条
        player.spigot().sendMessage(
            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
            net.md_5.bungee.api.chat.TextComponent.fromLegacyText("")
        );

        // 播放完成音效
        try {
            player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_PLAYER_BURP, 1.0f, 1.2f);
        } catch (Exception e) { plugin.getLogger().fine("Failed to play consume complete sound: " + e.getMessage()); }

        // 触发兑换
        boolean success = gemManager.redeemGemInHand(player);
        
        if (success) {
            // 播放成功特效
            try {
                player.playSound(player.getLocation(),
                    org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } catch (Exception e) { plugin.getLogger().fine("Failed to play redeem success sound: " + e.getMessage()); }
        }
    }

    /**
     * 取消消耗
     */
    private void cancelConsuming(Player player, boolean showMessage) {
        UUID playerId = player.getUniqueId();
        activeConsumers.remove(playerId);

        if (player.isOnline()) {
            // 清除进度条，显示取消消息
            String cancelledMsg = languageManager.getMessage("messages.hold_redeem.cancelled");
            cancelledMsg = ChatColor.translateAlternateColorCodes('&', cancelledMsg);
            player.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(cancelledMsg)
            );

            // 延迟清除消息
            SchedulerUtil.entityRun(plugin, player, () -> {
                if (player.isOnline()) {
                    player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText("")
                    );
                }
            }, 20L, -1L);
        }
    }

    /**
     * 切换手持物品时取消
     */
    @EventHandler
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (activeConsumers.containsKey(player.getUniqueId())) {
            cancelConsuming(player, true);
        }
    }

    /**
     * 受到伤害时取消
     */
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (activeConsumers.containsKey(player.getUniqueId())) {
            cancelConsuming(player, true);
        }
    }

    /**
     * 玩家退出时清理
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeConsumers.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 消耗进度数据类
     */
    private static class ConsumeProgress {
        UUID gemId;
        long startTime;
        long lastInteractTime;
        @SuppressWarnings("unused")
        ItemStack itemSnapshot; // 保留用于未来的物品比较验证
        int tickCount = 0;
    }
}

