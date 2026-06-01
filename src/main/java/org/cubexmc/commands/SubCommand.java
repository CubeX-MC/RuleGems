package org.cubexmc.commands;

import org.bukkit.command.CommandSender;

/**
 * A sub-command handler for /rulegems &lt;sub&gt;.
 */
public interface SubCommand {

    /**
     * Execute the sub-command.
     *
     * @param sender the command sender
     * @param args   the arguments (excluding the sub-command name itself)
     * @return true if usage is correct
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * The permission required to execute this sub-command (nullable = no permission check).
     */
    default String getPermission() {
        return null;
    }

    /**
     * Whether this sub-command requires the sender to be a Player.
     */
    default boolean isPlayerOnly() {
        return false;
    }
}
