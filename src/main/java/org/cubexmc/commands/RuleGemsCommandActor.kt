package org.cubexmc.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Unified Cloud sender wrapper so modern and legacy bootstrap paths can share
 * the same command registration layer.
 */
class RuleGemsCommandActor private constructor(
    private val sender: CommandSender,
    private val nativeSender: Any,
) {
    fun sender(): CommandSender = sender

    fun nativeSender(): Any = nativeSender

    fun isPlayer(): Boolean = sender is Player

    fun player(): Player? = sender as? Player

    companion object {
        @JvmStatic
        fun legacy(sender: CommandSender): RuleGemsCommandActor = RuleGemsCommandActor(sender, sender)

        @JvmStatic
        fun modern(sender: CommandSender, nativeSender: Any): RuleGemsCommandActor = RuleGemsCommandActor(sender, nativeSender)
    }
}
