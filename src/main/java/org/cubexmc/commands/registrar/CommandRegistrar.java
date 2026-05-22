package org.cubexmc.commands.registrar;

import org.incendo.cloud.CommandManager;
import org.cubexmc.commands.RuleGemsCommandActor;

public interface CommandRegistrar {
    void register(CommandManager<RuleGemsCommandActor> manager);
}
