package org.cubexmc.commands.sub;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlaceSubCommand implements SubCommand {

    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public PlaceSubCommand(GemManager gemManager, LanguageManager languageManager) {
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        // subArgs: <gemId> <x|~> <y|~> <z|~>
        if (args.length < 4) {
            languageManager.sendMessage(player, "command.place.usage");
            return true;
        }
        World world = player.getWorld();
        String gemIdentifier = args[0];

        String sx = args[1].equals("~") ? String.valueOf(player.getLocation().getX()) : args[1];
        String sy = args[2].equals("~") ? String.valueOf(player.getLocation().getY()) : args[2];
        String sz = args[3].equals("~") ? String.valueOf(player.getLocation().getZ()) : args[3];

        double x, y, z;
        try {
            x = Double.parseDouble(sx);
            y = Double.parseDouble(sy);
            z = Double.parseDouble(sz);
        } catch (NumberFormatException e) {
            languageManager.sendMessage(player, "command.place.invalid_coordinates");
            return true;
        }

        UUID gemId = gemManager.resolveGemIdentifier(gemIdentifier);
        if (gemId == null) {
            languageManager.sendMessage(player, "command.place.invalid_gem");
            return true;
        }

        Location loc = new Location(world, x, y, z);
        if (!loc.getChunk().isLoaded())
            loc.getChunk().load();

        org.bukkit.Material m = gemManager.getGemMaterial(gemId);
        if (gemManager.isSupportRequired(m) && !gemManager.hasBlockSupport(loc)) {
            languageManager.sendMessage(player, "command.place.failed_unsupported");
            return true;
        }
        gemManager.forcePlaceGem(gemId, loc);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("x", String.valueOf(x));
        placeholders.put("y", String.valueOf(y));
        placeholders.put("z", String.valueOf(z));
        languageManager.sendMessage(player, "command.place.success", placeholders);
        return true;
    }
}
