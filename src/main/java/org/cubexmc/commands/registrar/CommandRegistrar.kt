package org.cubexmc.commands.registrar

import org.cubexmc.commands.RuleGemsCommandActor
import org.incendo.cloud.CommandManager

interface CommandRegistrar {
    fun register(manager: CommandManager<RuleGemsCommandActor>)
}
