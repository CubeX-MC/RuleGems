package org.cubexmc.commands

import org.bukkit.command.CommandSender

/**
 * A sub-command handler for /rulegems &lt;sub&gt;.
 */
interface SubCommand {
    /**
     * Execute the sub-command.
     *
     * @param sender the command sender
     * @param args   the arguments (excluding the sub-command name itself)
     * @return true if usage is correct
     */
    fun execute(sender: CommandSender, args: Array<String>): Boolean

    /**
     * The permission required to execute this sub-command (nullable = no permission check).
     */
    fun getPermission(): String? = null

    /**
     * Whether this sub-command requires the sender to be a Player.
     */
    fun isPlayerOnly(): Boolean = false
}
