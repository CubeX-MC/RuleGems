package org.cubexmc.manager

/** FAILED is the only outcome that a guarded transfer may refund automatically. */
enum class CommandExecutionResult {
    SUCCESS,
    FAILED,
    PARTIAL,
    REVIEW_REQUIRED,
}
