package org.cubexmc.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cubexmc.RuleGems
import org.cubexmc.listeners.CommandAllowanceListener
import java.util.Collections
import java.util.Locale

/**
 * Proxy command registered for each RuleGems allowed-command label so players
 * can enjoy Brigadier auto-completion and visual feedback while still going
 * through the allowance pipeline.
 */
class AllowedCommandProxy(
    label: String,
    private val plugin: RuleGems,
    private val allowanceListener: CommandAllowanceListener,
) : Command(label), PluginIdentifiableCommand {
    init {
        usage = "/$label"
        description = "RuleGems proxy for /$label"
        permission = null // handled manually by allowance listener
        aliases = Collections.emptyList()
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can use this command.")
            return true
        }
        val lowerLabel = commandLabel.lowercase(Locale.ROOT)
        val raw = buildRaw(lowerLabel, args)
        allowanceListener.handleProxyExecution(sender, raw, lowerLabel, args)
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
        if (sender !is Player) {
            return Collections.emptyList()
        }
        return allowanceListener.suggestProxyTab(sender, alias.lowercase(Locale.ROOT), args)
    }

    override fun getPlugin(): Plugin = plugin

    private fun buildRaw(label: String, args: Array<String>?): String {
        if (args == null || args.isEmpty()) {
            return label
        }
        return label + " " + args.joinToString(" ")
    }
}
