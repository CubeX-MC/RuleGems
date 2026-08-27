package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.economy.VaultTransfers
import org.cubexmc.model.AllowedCommand
import org.cubexmc.utils.SchedulerUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import kotlin.math.max

/**
 * 自定义命令执行器
 * 处理命令解析、参数替换、执行者切换等逻辑
 */
class CustomCommandExecutor(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager?,
    private val gameplayConfig: GameplayConfig?,
    var economyProvider: VaultTransfers? = null,
) {
    // 冷却时间管理: 玩家UUID -> (命令名 -> 过期时间戳)
    private val playerCooldowns: MutableMap<UUID, MutableMap<String, Long>> = ConcurrentHashMap()

    /**
     * 以控制台身份调度命令（Folia 安全，fire-and-forget）。
     * 供外部调用（如 CommandAllowanceListener）在全局线程上执行命令。
     *
     * @param command 不含前导 / 的命令字符串
     * @return 调度是否成功（不代表命令本身成功）
     */
    fun dispatchAsConsole(command: String): Boolean = executeAsConsole(command, null)

    /**
     * 以后台身份执行命令
     * 在 Folia 中，后台命令必须在全局线程执行
     */
    private fun executeAsConsole(command: String, @Suppress("UNUSED_PARAMETER") player: Player?): Boolean {
        return try {
            // 后台命令必须在全局线程执行
            SchedulerUtil.globalRun(plugin, { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }, 0L, -1L)
            plugin.logger.fine("[Debug] Console command submitted: $command")
            true
        } catch (e: Exception) {
            plugin.logger.warning("Failed to execute console command: $command")
            plugin.logger.log(Level.SEVERE, "Console command execution failed", e)
            false
        }
    }

    /**
     * 以玩家身份执行命令（安全方式）
     * 如果配置允许 OP 提权则临时授予 OP，否则回退为控制台执行。
     */
    private fun executeAsPlayerOp(command: String, player: Player): Boolean {
        val useOp = gameplayConfig != null && gameplayConfig.isOpEscalationAllowed

        if (!useOp) {
            // 安全回退：以控制台身份执行，保留 %player% 替换
            plugin.logger.fine("[Safe mode] player-op command falling back to console execution: $command")
            return executeAsConsole(command, player)
        }

        // OP 提权模式（管理员显式启用）
        val wasOp = player.isOp
        try {
            if (!wasOp) {
                player.isOp = true
            }
            return player.performCommand(command)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to execute player command: $command")
            plugin.logger.log(Level.SEVERE, "Player command execution failed", e)
            return false
        } finally {
            if (!wasOp && player.isOp) {
                player.isOp = false
            }
        }
    }

    /**
     * 以玩家本人身份执行命令，不提权。适合需要玩家上下文且玩家已由 power 授权的交互命令。
     */
    private fun executeAsPlayer(command: String, player: Player?): Boolean {
        if (player == null) {
            return false
        }
        return try {
            player.performCommand(command)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to execute player command: $command")
            plugin.logger.log(Level.SEVERE, "Player command execution failed", e)
            false
        }
    }

    private val transfers by lazy {
        TransferDirectiveExecutor(
            plugin.logger, languageManager,
            BooleanSupplier { gameplayConfig?.isTransferDirectivesEnabled == true },
            Supplier { economyProvider },
        )
    }
    private val chain by lazy { AllowedCommandChain(transfers, ::executeOrdinaryCommand) }

    fun executeExtendedCommand(player: Player?, allowedCmd: AllowedCommand?, args: Array<String>): Boolean =
        executeExtendedCommandResult(player, allowedCmd, args) == CommandExecutionResult.SUCCESS

    fun executeExtendedCommandResult(
        player: Player?, allowedCmd: AllowedCommand?, args: Array<String>,
    ): CommandExecutionResult {
        if (player == null || allowedCmd == null) return CommandExecutionResult.FAILED
        val error = allowedCmd.argumentConstraints.validate(args)
        return if (error != null) {
            languageManager?.sendMessage(player, error.messageKey, error.placeholders + ("usage" to allowedCmd.usage))
            CommandExecutionResult.FAILED
        } else {
            chain.execute(player, AllowedCommandRenderer.render(player, allowedCmd, args))
        }
    }

    private fun executeOrdinaryCommand(player: Player, entry: AllowedCommandRenderer.Entry): Boolean {
        val success = when (entry.executor) {
            "console" -> executeAsConsole(entry.command, player)
            "player" -> executeAsPlayer(entry.command, player)
            else -> executeAsPlayerOp(entry.command, player)
        }
        if (!success) {
            if (languageManager != null) {
                languageManager.sendMessage(
                    player, "allowance.command_failed_detail", mapOf("command" to entry.command),
                )
            } else {
                player.sendMessage("§cCommand execution failed: ${entry.command}")
            }
        }
        return success
    }

    /**
     * 检查玩家是否可以执行命令（冷却时间）
     */
    fun checkCooldown(playerUuid: UUID, commandName: String): Boolean {
        val cooldowns = playerCooldowns[playerUuid] ?: return true
        val expireTime = cooldowns[commandName] ?: return true
        return System.currentTimeMillis() >= expireTime
    }

    /**
     * 获取剩余冷却时间（秒）
     */
    fun getRemainingCooldown(playerUuid: UUID, commandName: String): Long {
        val cooldowns = playerCooldowns[playerUuid] ?: return 0
        val expireTime = cooldowns[commandName] ?: return 0
        val remaining = (expireTime - System.currentTimeMillis()) / 1000
        return max(0, remaining)
    }

    /**
     * 设置冷却时间
     */
    fun setCooldown(playerUuid: UUID, commandName: String, seconds: Int) {
        val cooldowns = playerCooldowns.computeIfAbsent(playerUuid) { HashMap() }
        val expireTime = System.currentTimeMillis() + seconds * 1000L
        cooldowns[commandName] = expireTime
    }
}
