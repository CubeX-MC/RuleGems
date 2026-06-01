package org.cubexmc.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Unified Cloud sender wrapper so modern and legacy bootstrap paths can share
 * the same command registration layer.
 */
public final class RuleGemsCommandActor {

    private final CommandSender sender;
    private final Object nativeSender;

    private RuleGemsCommandActor(CommandSender sender, Object nativeSender) {
        this.sender = sender;
        this.nativeSender = nativeSender;
    }

    public static RuleGemsCommandActor legacy(CommandSender sender) {
        return new RuleGemsCommandActor(sender, sender);
    }

    public static RuleGemsCommandActor modern(CommandSender sender, Object nativeSender) {
        return new RuleGemsCommandActor(sender, nativeSender);
    }

    public CommandSender sender() {
        return this.sender;
    }

    public Object nativeSender() {
        return this.nativeSender;
    }

    public boolean isPlayer() {
        return this.sender instanceof Player;
    }

    public Player player() {
        return this.isPlayer() ? (Player) this.sender : null;
    }
}
