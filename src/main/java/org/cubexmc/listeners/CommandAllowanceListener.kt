package org.cubexmc.listeners

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.server.TabCompleteEvent
import org.cubexmc.manager.CustomCommandExecutor
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemAllowanceManager
import org.cubexmc.manager.LanguageManager
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CommandAllowanceListener(
    private val allowanceManager: GemAllowanceManager,
    private val languageManager: LanguageManager,
    private val customCommandExecutor: CustomCommandExecutor,
    private val gameplayConfig: GameplayConfig?,
) : Listener {
    private val proxyLabels: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bypassPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Update the set of command labels that are backed by dedicated proxy commands.
     */
    fun updateProxyLabels(labels: Set<String>?) {
        proxyLabels.clear()
        if (labels != null) {
            for (label in labels) {
                if (label.isEmpty()) {
                    continue
                }
                proxyLabels.add(label.lowercase(Locale.ROOT))
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommandSend(event: PlayerCommandSendEvent) {
        val player = event.player
        val allowed = allowanceManager.getAvailableCommandLabels(player.uniqueId)
        if (allowed.isEmpty()) {
            return
        }
        val commands = event.commands
        for (label in allowed) {
            if (label == null || label.isEmpty()) {
                continue
            }
            commands.add(label)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        if (event.sender !is Player) {
            return
        }
        val buffer = event.buffer
        if (buffer.isEmpty() || buffer[0] != '/') {
            return
        }
        val player = event.sender as Player
        val allowed = allowanceManager.getAvailableCommandLabels(player.uniqueId)
        if (allowed.isEmpty()) {
            return
        }

        val withoutSlash = buffer.substring(1)
        val trailingSpace = buffer.endsWith(" ")
        val parts = withoutSlash.split(" ".toRegex(), -1).toTypedArray()
        if (parts.isEmpty()) {
            return
        }

        val completions = ArrayList(event.completions)
        if (parts.size == 1 && !trailingSpace) {
            val partial = parts[0].lowercase(Locale.ROOT)
            for (label in allowed) {
                if (partial.isEmpty() || label.startsWith(partial)) {
                    completions.add(label)
                }
            }
            if (completions.isNotEmpty()) {
                event.completions = distinct(completions)
            }
            return
        }

        val base = parts[0].lowercase(Locale.ROOT)
        if (!allowed.contains(base)) {
            return
        }

        if (parts.size == 1 && trailingSpace) {
            // command typed exactly, no argument suggestions available yet
            return
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val message = event.message
        val player = event.player
        if (message.isEmpty()) {
            return
        }
        if (message[0] != '/') {
            return
        }

        val raw = message.substring(1).trim()
        if (raw.isEmpty()) {
            return
        }
        val parts = raw.split(" ".toRegex(), 2).toTypedArray()
        val label = parts[0].lowercase(Locale.ROOT)

        if (proxyLabels.contains(label)) {
            // Proxied command: let the registered command handle execution.
            return
        }

        if (bypassPlayers.remove(player.uniqueId)) {
            return
        }

        val args = if (parts.size > 1) parts[1].split(" ".toRegex()).toTypedArray() else emptyArray()
        val handled = handleAllowedCommand(player, raw, label, args, true)
        if (handled) {
            event.isCancelled = true
        }
    }

    fun handleProxyExecution(player: Player, raw: String, label: String, args: Array<String>) {
        handleAllowedCommand(player, raw, label, args, false)
    }

    fun suggestProxyTab(player: Player?, alias: String, args: Array<String>): List<String> {
        if (player == null) {
            return Collections.emptyList()
        }
        val allowed = allowanceManager.getAvailableCommandLabels(player.uniqueId)
        if (allowed.isEmpty()) {
            return Collections.emptyList()
        }
        if (args.isEmpty()) {
            return if (allowed.contains(alias)) Collections.singletonList(alias) else Collections.emptyList()
        }
        return Collections.emptyList()
    }

    private fun handleAllowedCommand(
        player: Player?,
        raw: String?,
        baseLabel: String,
        args: Array<String>,
        allowDelegate: Boolean,
    ): Boolean {
        if (player == null || raw.isNullOrEmpty()) {
            return false
        }

        val uid = player.uniqueId
        val rawLower = raw.lowercase(Locale.ROOT)
        val labelLower = baseLabel.lowercase(Locale.ROOT)

        val fullMatch = allowanceManager.hasAnyAllowed(uid, rawLower)
        val baseMatch = fullMatch || allowanceManager.hasAnyAllowed(uid, labelLower)

        if (!baseMatch) {
            if (!allowDelegate) {
                languageManager.sendMessage(player, "command.no_permission")
                return true
            }
            val pluginCommand = Bukkit.getPluginCommand(labelLower)
            if (pluginCommand != null && pluginCommand.testPermissionSilent(player)) {
                return false
            }
            return false
        }

        val matchedLabel = if (fullMatch) rawLower else labelLower
        val resolved = allowanceManager.resolveAllowedCommand(uid, matchedLabel)
        if (resolved == null) {
            languageManager.sendMessage(player, "allowance.none_left")
            return true
        }

        val allowedCommand = resolved.command
        val cooldownKey = resolved.cooldownKey
        if (allowedCommand != null && allowedCommand.cooldown > 0) {
            if (!customCommandExecutor.checkCooldown(uid, cooldownKey)) {
                val remainingSeconds = customCommandExecutor.getRemainingCooldown(uid, cooldownKey)
                val cooldownPlaceholders = HashMap<String, String>()
                cooldownPlaceholders["seconds"] = remainingSeconds.toString()
                languageManager.sendMessage(player, "allowance.cooldown", cooldownPlaceholders)
                return true
            }
        }

        val consumed = allowanceManager.tryConsumeAllowed(uid, resolved)
        if (!consumed) {
            languageManager.sendMessage(player, "allowance.none_left")
            return true
        }

        val ok = if (allowedCommand != null && !allowedCommand.isSimpleCommand()) {
            customCommandExecutor.executeExtendedCommand(player, allowedCommand, args)
        } else {
            val useOp = gameplayConfig != null && gameplayConfig.isOpEscalationAllowed
            if (useOp) {
                // OP 提权模式（管理员显式启用）
                val wasOp = player.isOp
                try {
                    if (!wasOp) {
                        player.isOp = true
                    }
                    bypassPlayers.add(uid)
                    player.performCommand(raw)
                } catch (_: Throwable) {
                    false
                } finally {
                    bypassPlayers.remove(uid)
                    if (!wasOp && player.isOp) {
                        player.isOp = false
                    }
                }
            } else {
                // 安全模式：以控制台身份在全局线程执行（Folia 安全）
                customCommandExecutor.dispatchAsConsole(raw)
            }
        }

        if (!ok) {
            allowanceManager.refundAllowed(uid, resolved)
            languageManager.sendMessage(player, "allowance.execute_failed")
            return true
        }

        if (allowedCommand != null && allowedCommand.cooldown > 0) {
            customCommandExecutor.setCooldown(uid, cooldownKey, allowedCommand.cooldown)
        }

        val remain = allowanceManager.getRemainingAllowed(uid, matchedLabel)
        val remainShown = if (remain < 0) "∞" else remain.toString()
        val placeholders = HashMap<String, String>()
        placeholders["command"] = matchedLabel
        placeholders["remain"] = remainShown
        languageManager.sendMessage(player, "allowance.used", placeholders)
        return true
    }

    private fun distinct(values: List<String>): List<String> = ArrayList(LinkedHashSet(values))
}
