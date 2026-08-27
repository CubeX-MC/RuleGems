package org.cubexmc.manager

import org.bukkit.entity.Player
import org.cubexmc.economy.VaultTransfers
import java.util.Locale
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import java.util.logging.Logger

internal class TransferDirectiveExecutor(
    private val logger: Logger,
    private val language: LanguageManager?,
    private val enabled: BooleanSupplier,
    private val provider: Supplier<VaultTransfers?>,
) {
    fun execute(player: Player, spec: String): CommandExecutionResult {
        val economy = provider.get()
        val parts = spec.trim().split(Regex("\\s+"))
        val amount = parts.getOrNull(2)?.toDoubleOrNull()
        return when {
            !enabled.asBoolean -> fail(player, "transfer_disabled", "Transfer directives are disabled.")
            economy == null -> fail(player, "transfer_no_economy", "Vault economy is unavailable.")
            parts.size != SPEC_FIELDS || amount == null ->
                fail(player, "transfer_failed", "Invalid transfer specification: $spec")
            else -> perform(player, economy, parts[0], parts[1], amount)
        }
    }

    private fun perform(
        player: Player, economy: VaultTransfers, from: String, to: String, amount: Double,
    ): CommandExecutionResult {
        val placeholders = mapOf("from" to from, "to" to to, "amount" to String.format(Locale.ROOT, "%.2f", amount))
        val result = economy.transfer(from, to, amount)
        val message = when (result) {
            VaultTransfers.Result.SUCCESS -> "transfer_success"
            VaultTransfers.Result.INSUFFICIENT -> "transfer_insufficient"
            VaultTransfers.Result.NO_ECONOMY -> "transfer_no_economy"
            VaultTransfers.Result.REVIEW_REQUIRED, VaultTransfers.Result.ROLLBACK_FAILED -> null
            else -> "transfer_failed"
        }
        if (message != null) language?.sendMessage(player, "allowance.$message", placeholders)
        return when (result) {
            VaultTransfers.Result.SUCCESS -> CommandExecutionResult.SUCCESS
            VaultTransfers.Result.REVIEW_REQUIRED, VaultTransfers.Result.ROLLBACK_FAILED ->
                CommandExecutionResult.REVIEW_REQUIRED
            else -> CommandExecutionResult.FAILED
        }
    }

    private fun fail(player: Player, message: String, detail: String): CommandExecutionResult {
        language?.sendMessage(player, "allowance.$message")
        logger.warning(detail)
        return CommandExecutionResult.FAILED
    }

    companion object {
        private const val SPEC_FIELDS = 3
    }
}
