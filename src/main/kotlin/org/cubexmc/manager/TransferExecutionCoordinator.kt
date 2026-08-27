package org.cubexmc.manager

import org.bukkit.entity.Player
import org.cubexmc.storage.TransferOperationStore
import java.util.UUID
import java.util.function.BooleanSupplier
import java.util.logging.Level
import java.util.logging.Logger

/** Owns the durable allowance/economy boundary on the server thread. */
class TransferExecutionCoordinator(
    private val operations: TransferOperationStore,
    private val allowances: GemAllowanceManager,
    private val executor: CustomCommandExecutor,
    private val persist: BooleanSupplier,
    private val logger: Logger,
) {
    data class Attempt(val message: String, val operationId: UUID? = null) {
        val successful: Boolean get() = message == "allowance.used"
    }

    fun execute(player: Player, resolved: GemAllowanceManager.ResolvedAllowance, args: Array<String>): Attempt {
        val pending = operations.pending(player.uniqueId, resolved.cooldownKey)
        return if (pending != null) {
            Attempt("allowance.transfer_blocked", pending.id)
        } else {
            attemptTransferOperation {
                val commands = AllowedCommandRenderer.render(player, resolved.command, args)
                    .filter { it.executor == "transfer" }.map { it.command }
                operations.begin(player.uniqueId, resolved.cooldownKey, resolved.label, commands)
            }.fold(
                onSuccess = { consume(player, resolved, args, it.id) },
                onFailure = {
                    logger.log(Level.SEVERE, "Cannot journal transfer; no command was executed.", it)
                    Attempt("allowance.transfer_storage_failed")
                },
            )
        }
    }

    private fun consume(
        player: Player, resolved: GemAllowanceManager.ResolvedAllowance, args: Array<String>, operation: UUID,
    ): Attempt = when {
        !allowances.tryConsumeAllowed(player.uniqueId, resolved) -> finish(operation, "allowance.none_left")
        !saveRuntime() -> {
            allowances.refundAllowed(player.uniqueId, resolved)
            hold(operation, "PRE_EXECUTION_SAVE_FAILED")
        }
        else -> executePrepared(player, resolved, args, operation)
    }

    private fun executePrepared(
        player: Player, resolved: GemAllowanceManager.ResolvedAllowance, args: Array<String>, operation: UUID,
    ): Attempt {
        val result = attemptTransferOperation {
            executor.executeExtendedCommandResult(player, resolved.command, args)
        }.getOrElse {
            logger.log(Level.SEVERE, "Transfer operation $operation requires reconciliation.", it)
            CommandExecutionResult.REVIEW_REQUIRED
        }
        if (result == CommandExecutionResult.FAILED) {
            allowances.refundAllowed(player.uniqueId, resolved)
        } else if (resolved.command.cooldown > 0) {
            executor.setCooldown(player.uniqueId, resolved.cooldownKey, resolved.command.cooldown)
        }
        return if (!saveRuntime()) {
            hold(operation, "POST_EXECUTION_SAVE_FAILED")
        } else {
            when (result) {
                CommandExecutionResult.SUCCESS -> finish(operation, "allowance.used")
                CommandExecutionResult.FAILED -> finish(operation, "allowance.execute_failed")
                CommandExecutionResult.PARTIAL, CommandExecutionResult.REVIEW_REQUIRED -> hold(operation, result.name)
            }
        }
    }

    private fun saveRuntime(): Boolean = attemptTransferOperation { persist.asBoolean }.getOrElse {
        logger.log(Level.SEVERE, "Cannot persist transfer allowance state.", it)
        false
    }

    private fun finish(id: UUID, message: String): Attempt = attemptTransferOperation {
        operations.complete(id)
        logger.info("Transfer operation $id finished: $message")
        Attempt(message)
    }.getOrElse {
        logger.log(Level.SEVERE, "Cannot finish transfer operation $id; retry guard retained.", it)
        Attempt("allowance.transfer_review_required", id)
    }

    private fun hold(id: UUID, status: String): Attempt {
        attemptTransferOperation { operations.hold(id, status) }.onFailure {
            // The durable EXECUTING marker still blocks retries when this status update fails.
            logger.log(Level.SEVERE, "Cannot update transfer operation $id; existing guard retained.", it)
        }
        logger.severe("Transfer operation $id requires operator review: $status")
        return Attempt("allowance.transfer_review_required", id)
    }
}

/** Third-party/storage exceptions become explicit outcomes; fatal JVM errors still propagate with the guard intact. */
internal inline fun <T> attemptTransferOperation(action: () -> T): Result<T> =
    runCatching(action).onFailure { if (it !is Exception) throw it }
