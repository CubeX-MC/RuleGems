package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.economy.EconomyProvider
import org.cubexmc.model.AllowedCommand
import org.cubexmc.utils.SchedulerUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max

/**
 * 自定义命令执行器
 * 处理命令解析、参数替换、执行者切换等逻辑
 */
class CustomCommandExecutor(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager?,
    private val gameplayConfig: GameplayConfig?,
    private val economyProvider: EconomyProvider? = null,
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

    /**
     * 处理 transfer 指令：格式为 "<付款账户> <收款账户> <金额>"（占位符已替换完毕）。
     * 经 Vault 做离线安全原子转账，并向玩家反馈结果。返回是否成功。
     */
    private fun executeTransfer(player: Player, spec: String): Boolean {
        val eco = economyProvider
        if (eco == null) {
            languageManager?.sendMessage(player, "allowance.transfer_no_economy")
            plugin.logger.warning("transfer: directive used but Vault economy is unavailable: $spec")
            return false
        }

        val parts = spec.trim().split("\\s+".toRegex())
        if (parts.size != 3) {
            languageManager?.sendMessage(player, "allowance.transfer_failed")
            plugin.logger.warning("transfer: directive must be 'transfer:<from> <to> <amount>', got: $spec")
            return false
        }
        val fromName = parts[0]
        val toName = parts[1]
        val amount = parts[2].toDoubleOrNull()
        if (amount == null) {
            languageManager?.sendMessage(player, "allowance.transfer_failed")
            plugin.logger.warning("transfer: invalid amount in: $spec")
            return false
        }

        val placeholders = HashMap<String, String>()
        placeholders["from"] = fromName
        placeholders["to"] = toName
        placeholders["amount"] = String.format("%.2f", amount)

        return when (eco.transfer(fromName, toName, amount)) {
            EconomyProvider.Result.SUCCESS -> {
                languageManager?.sendMessage(player, "allowance.transfer_success", placeholders)
                true
            }
            EconomyProvider.Result.INSUFFICIENT -> {
                languageManager?.sendMessage(player, "allowance.transfer_insufficient", placeholders)
                false
            }
            EconomyProvider.Result.NO_ECONOMY -> {
                languageManager?.sendMessage(player, "allowance.transfer_no_economy", placeholders)
                false
            }
            else -> {
                languageManager?.sendMessage(player, "allowance.transfer_failed", placeholders)
                false
            }
        }
    }

    /**
     * 替换占位符，支持默认值语法：%arg1|defaultValue%
     */
    private fun replacePlaceholders(text: String, placeholders: Map<String, String>, args: Array<String>): String {
        var result = text

        // 先处理简单占位符
        for ((key, value) in placeholders) {
            result = result.replace(key, value)
        }

        // 处理带默认值的占位符: %arg1|default%
        val pattern = Pattern.compile("%arg(\\d+)\\|([^%]+)%")
        val matcher = pattern.matcher(result)
        val builder = StringBuffer()

        while (matcher.find()) {
            val argIndex = matcher.group(1).toInt() - 1 // arg1 = index 0
            val defaultValue = matcher.group(2)
            val replacement = if (argIndex >= 0 && argIndex < args.size) args[argIndex] else defaultValue
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(builder)

        return builder.toString()
    }

    /**
     * 执行扩展命令（多命令链或带执行者前缀）
     */
    fun executeExtendedCommand(player: Player?, allowedCmd: AllowedCommand?, args: Array<String>): Boolean {
        if (player == null || allowedCmd == null) {
            return false
        }

        // 准备占位符映射
        val placeholders = HashMap<String, String>()
        placeholders["%player%"] = player.name
        for (i in args.indices) {
            placeholders["%arg${i + 1}%"] = args[i]
        }

        // 调试日志
        plugin.logger.fine("[Debug] Executing extended command, player: " + player.name)
        plugin.logger.fine("[Debug] Placeholders: $placeholders")
        plugin.logger.fine("[Debug] Args: " + args.contentToString())
        plugin.logger.fine("[Debug] Command list: " + allowedCmd.getCommands())

        // 执行所有命令
        var allSuccess = true
        for (commandLine in allowedCmd.getCommands()) {
            if (commandLine == null || commandLine.trim().isEmpty()) {
                continue
            }

            plugin.logger.fine("[Debug] Raw command line: $commandLine")

            // 解析执行者和命令
            val parsed = AllowedCommand.parseExecutor(commandLine)
            val executor = parsed[0] // "console"、"player" 或 "player-op"
            val actualCommand = parsed[1]

            plugin.logger.fine("[Debug] Executor: $executor, actual command: $actualCommand")

            // 替换占位符（包括默认值支持）
            var finalCommand = replacePlaceholders(actualCommand, placeholders, args)

            plugin.logger.fine("[Debug] Command after substitution: $finalCommand")

            // 移除开头的斜杠（如果有）
            if (finalCommand.startsWith("/")) {
                finalCommand = finalCommand.substring(1)
            }

            plugin.logger.fine("[Debug] Final command: $finalCommand")

            // transfer: 由 Vault 做离线安全原子转账。失败（余额不足/无经济/参数错误）时
            // 中止整条命令链并返回失败，监听器据此退回次数且不进入冷却。
            if ("transfer" == executor) {
                if (!executeTransfer(player, finalCommand)) {
                    return false
                }
                continue
            }

            // 根据执行者类型执行命令
            val success = if ("console" == executor) {
                executeAsConsole(finalCommand, player)
            } else if ("player" == executor) {
                executeAsPlayer(finalCommand, player)
            } else {
                // "player-op" 或默认
                executeAsPlayerOp(finalCommand, player)
            }

            if (!success) {
                allSuccess = false
                if (languageManager != null) {
                    val messagePlaceholders = HashMap<String, String>()
                    messagePlaceholders["command"] = finalCommand
                    languageManager.sendMessage(player, "allowance.command_failed_detail", messagePlaceholders)
                } else {
                    player.sendMessage("§cCommand execution failed: $finalCommand")
                }
            }
        }

        return allSuccess
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
