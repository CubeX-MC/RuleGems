package org.cubexmc.manager

import org.bukkit.entity.Player

internal class AllowedCommandChain(
    private val transfers: TransferDirectiveExecutor,
    private val ordinary: (Player, AllowedCommandRenderer.Entry) -> Boolean,
) {
    fun execute(player: Player, commands: List<AllowedCommandRenderer.Entry>): CommandExecutionResult {
        var failed = false
        var sideEffects = false
        var uncertain = false
        for (entry in commands) {
            if (entry.executor == "transfer") {
                val result = transfers.execute(player, entry.command)
                if (result != CommandExecutionResult.SUCCESS) {
                    uncertain = result == CommandExecutionResult.REVIEW_REQUIRED
                    failed = true
                    break
                }
                sideEffects = true
            } else {
                val success = ordinary(player, entry)
                failed = failed || !success
                sideEffects = sideEffects || success
            }
        }
        return when {
            uncertain -> CommandExecutionResult.REVIEW_REQUIRED
            !failed -> CommandExecutionResult.SUCCESS
            sideEffects -> CommandExecutionResult.PARTIAL
            else -> CommandExecutionResult.FAILED
        }
    }
}
