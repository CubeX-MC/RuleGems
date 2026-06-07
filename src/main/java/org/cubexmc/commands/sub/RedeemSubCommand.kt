package org.cubexmc.commands.sub

import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.commands.SubCommand
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager

class RedeemSubCommand(
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : SubCommand {
    override fun getPermission(): String = "rulegems.redeem"

    override fun isPlayerOnly(): Boolean = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as Player
        val inHand = player.inventory.itemInMainHand
        if (inHand.type == Material.AIR) {
            languageManager.sendMessage(player, "command.redeem.no_item_in_hand")
            return true
        }
        if (!gemManager.isRuleGem(inHand)) {
            languageManager.sendMessage(player, "command.redeem.not_a_gem")
            return true
        }
        val ok = gemManager.redeemGemInHand(player)
        if (!ok) {
            languageManager.sendMessage(player, "command.redeem.failed")
            return true
        }
        languageManager.sendMessage(player, "command.redeem.success")
        return true
    }
}
