package org.cubexmc.commands;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.cubexmc.RuleGems;
import org.cubexmc.listeners.CommandAllowanceListener;

/**
 * Proxy command registered for each RuleGems allowed-command label so players
 * can enjoy Brigadier auto-completion and visual feedback while still going
 * through the allowance pipeline.
 */
public class AllowedCommandProxy extends Command implements PluginIdentifiableCommand {
    private final RuleGems plugin;
    private final CommandAllowanceListener allowanceListener;

    public AllowedCommandProxy(String label,
                               RuleGems plugin,
                               CommandAllowanceListener allowanceListener) {
        super(label);
        this.plugin = plugin;
        this.allowanceListener = allowanceListener;
        setUsage("/" + label);
        setDescription("RuleGems proxy for /" + label);
        setPermission(null); // handled manually by allowance listener
        setAliases(Collections.emptyList());
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        String lowerLabel = commandLabel.toLowerCase(Locale.ROOT);
        String raw = buildRaw(lowerLabel, args);
        allowanceListener.handleProxyExecution(player, raw, lowerLabel, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }
        Player player = (Player) sender;
        return allowanceListener.suggestProxyTab(player, alias.toLowerCase(Locale.ROOT), args);
    }

    @Override
    public Plugin getPlugin() {
        return plugin;
    }

    private String buildRaw(String label, String[] args) {
        if (args == null || args.length == 0) {
            return label;
        }
        return label + " " + String.join(" ", Arrays.asList(args));
    }
}
