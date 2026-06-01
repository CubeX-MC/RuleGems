package org.cubexmc.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.commands.SubCommand;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;

public class RedeemSubCommand implements SubCommand {

    private final GemManager gemManager;
    private final LanguageManager languageManager;

    public RedeemSubCommand(GemManager gemManager, LanguageManager languageManager) {
        this.gemManager = gemManager;
        this.languageManager = languageManager;
    }

    @Override
    public String getPermission() {
        return "rulegems.redeem";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == org.bukkit.Material.AIR) {
            languageManager.sendMessage(player, "command.redeem.no_item_in_hand");
            return true;
        }
        if (!gemManager.isRuleGem(inHand)) {
            languageManager.sendMessage(player, "command.redeem.not_a_gem");
            return true;
        }
        boolean ok = gemManager.redeemGemInHand(player);
        if (!ok) {
            languageManager.sendMessage(player, "command.redeem.failed");
            return true;
        }
        languageManager.sendMessage(player, "command.redeem.success");
        return true;
    }
}
