package org.cubexmc.listeners

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.cubexmc.RuleGems
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.SchedulerUtil
import java.util.UUID
import kotlin.math.min

/**
 * 长按右键消耗宝石进行兑换的监听器
 */
class GemConsumeListener(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val gameplayConfig: GameplayConfig,
    private val languageManager: LanguageManager,
) : Listener {
    // 玩家正在进行的长按操作
    private val activeConsumers: MutableMap<UUID, ConsumeProgress> = HashMap()

    /**
     * 获取配置的长按时长（tick）
     */
    private val consumeDurationTicks: Int
        get() = gameplayConfig.holdToRedeemDurationTicks

    /**
     * 检查功能是否启用
     */
    private fun isEnabled(): Boolean = gameplayConfig.isHoldToRedeemEnabled

    /**
     * 处理右键交互事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!isEnabled()) {
            return
        }

        // 只处理右键（空气或方块）
        if (event.action != Action.RIGHT_CLICK_AIR &&
            event.action != Action.RIGHT_CLICK_BLOCK
        ) {
            return
        }

        // 只处理主手
        if (event.hand != EquipmentSlot.HAND) {
            return
        }

        val player = event.player
        val item = player.inventory.itemInMainHand

        // 检查是否是宝石
        if (!gemManager.isRuleGem(item)) {
            return
        }

        // 根据配置检查下蹲状态
        val sneakToRedeem = gameplayConfig.isSneakToRedeem
        val isSneaking = player.isSneaking

        // sneakToRedeem=true: 需要下蹲才能兑换，不下蹲则允许放置
        // sneakToRedeem=false: 不下蹲才能兑换，下蹲则允许放置
        if (sneakToRedeem && !isSneaking) {
            // 配置要求下蹲兑换，但玩家没下蹲，允许正常放置
            return
        }
        if (!sneakToRedeem && isSneaking) {
            // 配置要求普通兑换，但玩家在下蹲，允许正常放置
            return
        }

        // 检查玩家是否有兑换权限
        if (!player.hasPermission("rulegems.redeem")) {
            return
        }

        // 检查兑换功能是否启用
        if (!gameplayConfig.isRedeemEnabled) {
            return
        }

        val playerId = player.uniqueId

        // 如果已经在消耗中，更新最后交互时间
        if (activeConsumers.containsKey(playerId)) {
            val progress = activeConsumers[playerId]
            if (progress != null) {
                progress.lastInteractTime = System.currentTimeMillis()
            }
            return
        }

        // 开始新的消耗过程
        startConsuming(player, item)

        // 阻止其他交互（如放置方块）
        event.isCancelled = true
    }

    /**
     * 开始消耗宝石
     */
    private fun startConsuming(player: Player, item: ItemStack) {
        val playerId = player.uniqueId
        val gemId = gemManager.getGemUUID(item)

        val progress = ConsumeProgress()
        progress.gemId = gemId
        progress.startTime = System.currentTimeMillis()
        progress.lastInteractTime = System.currentTimeMillis()
        progress.itemSnapshot = item.clone()

        activeConsumers[playerId] = progress

        // 播放开始音效
        try {
            player.playSound(player.location, Sound.ENTITY_GENERIC_EAT, 0.5f, 0.8f)
        } catch (e: Exception) {
            plugin.logger.fine("Failed to play consume start sound: " + e.message)
        }

        // 启动进度检查任务
        scheduleProgressCheck(player)
    }

    /**
     * 调度进度检查
     */
    private fun scheduleProgressCheck(player: Player) {
        val playerId = player.uniqueId

        SchedulerUtil.entityRun(
            plugin,
            player,
            {
                if (!activeConsumers.containsKey(playerId)) {
                    return@entityRun
                }

                val progress = activeConsumers[playerId] ?: return@entityRun

                // 检查玩家是否还在线
                if (!player.isOnline) {
                    cancelConsuming(player, false)
                    return@entityRun
                }

                // 检查是否超时（玩家停止右键）
                val timeSinceLastInteract = System.currentTimeMillis() - progress.lastInteractTime
                if (timeSinceLastInteract > INTERACT_TIMEOUT_MS) {
                    cancelConsuming(player, true)
                    return@entityRun
                }

                // 检查手中物品是否还是同一个宝石
                val currentItem = player.inventory.itemInMainHand
                if (!gemManager.isRuleGem(currentItem)) {
                    cancelConsuming(player, true)
                    return@entityRun
                }
                val currentGemId = gemManager.getGemUUID(currentItem)
                if (progress.gemId != null && progress.gemId != currentGemId) {
                    cancelConsuming(player, true)
                    return@entityRun
                }

                // 计算进度
                val elapsed = System.currentTimeMillis() - progress.startTime
                val requiredMs = consumeDurationTicks * 50L // ticks to ms
                val progressPercent = min(1.0f, elapsed.toFloat() / requiredMs)

                // 显示进度条
                showProgressBar(player, progressPercent)

                // 播放进度音效
                if (progress.tickCount % 10 == 0) {
                    try {
                        player.playSound(player.location, Sound.ENTITY_GENERIC_EAT, 0.3f, 0.8f + progressPercent * 0.4f)
                    } catch (e: Exception) {
                        plugin.logger.fine("Failed to play consume progress sound: " + e.message)
                    }
                }
                progress.tickCount++

                // 检查是否完成
                if (progressPercent >= 1.0f) {
                    completeConsuming(player)
                    return@entityRun
                }

                // 继续检查
                SchedulerUtil.entityRun(plugin, player, { scheduleProgressCheck(player) }, CHECK_INTERVAL_TICKS.toLong(), -1L)
            },
            CHECK_INTERVAL_TICKS.toLong(),
            -1L,
        )
    }

    /**
     * 显示进度条
     */
    private fun showProgressBar(player: Player, progress: Float) {
        val filled = (progress * PROGRESS_BAR_LENGTH).toInt()
        val empty = PROGRESS_BAR_LENGTH - filled

        val bar = StringBuilder()
        bar.append(ChatColor.GREEN)
        for (i in 0 until filled) {
            bar.append("█")
        }
        bar.append(ChatColor.GRAY)
        for (i in 0 until empty) {
            bar.append("░")
        }

        // 计算百分比
        val percent = (progress * 100).toInt()

        // 使用语言文件中的格式
        var message = languageManager.getMessage("messages.hold_redeem.progress_bar")
            .replace("%bar%", bar.toString())
            .replace("%percent%", percent.toString())
        message = ColorUtils.translateColorCodes(message) ?: ""

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(message))
    }

    /**
     * 完成消耗，触发兑换
     */
    private fun completeConsuming(player: Player) {
        val playerId = player.uniqueId
        activeConsumers.remove(playerId)

        // 清除进度条
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(""))

        // 播放完成音效
        try {
            player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, 1.0f, 1.2f)
        } catch (e: Exception) {
            plugin.logger.fine("Failed to play consume complete sound: " + e.message)
        }

        // 触发兑换
        val success = gemManager.redeemGemInHand(player)

        if (success) {
            // 播放成功特效
            try {
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
            } catch (e: Exception) {
                plugin.logger.fine("Failed to play redeem success sound: " + e.message)
            }
        }
    }

    /**
     * 取消消耗
     */
    private fun cancelConsuming(player: Player, @Suppress("UNUSED_PARAMETER") showMessage: Boolean) {
        val playerId = player.uniqueId
        activeConsumers.remove(playerId)

        if (player.isOnline) {
            // 清除进度条，显示取消消息
            var cancelledMessage = languageManager.getMessage("messages.hold_redeem.cancelled")
            cancelledMessage = ColorUtils.translateColorCodes(cancelledMessage) ?: ""
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(cancelledMessage))

            // 延迟清除消息
            SchedulerUtil.entityRun(
                plugin,
                player,
                {
                    if (player.isOnline) {
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(""))
                    }
                },
                20L,
                -1L,
            )
        }
    }

    /**
     * 切换手持物品时取消
     */
    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        if (activeConsumers.containsKey(player.uniqueId)) {
            cancelConsuming(player, true)
        }
    }

    /**
     * 受到伤害时取消
     */
    @EventHandler
    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (activeConsumers.containsKey(player.uniqueId)) {
            cancelConsuming(player, true)
        }
    }

    /**
     * 玩家退出时清理
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        activeConsumers.remove(event.player.uniqueId)
    }

    /**
     * 消耗进度数据类
     */
    private class ConsumeProgress {
        var gemId: UUID? = null
        var startTime = 0L
        var lastInteractTime = 0L
        @Suppress("unused")
        var itemSnapshot: ItemStack? = null // 保留用于未来的物品比较验证
        var tickCount = 0
    }

    companion object {
        private const val CHECK_INTERVAL_TICKS = 2 // 每2tick检查一次
        private const val PROGRESS_BAR_LENGTH = 20 // 进度条长度
        private const val INTERACT_TIMEOUT_MS = 300L // 右键释放检测超时
    }
}
