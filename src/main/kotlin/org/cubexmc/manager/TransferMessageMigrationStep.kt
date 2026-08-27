package org.cubexmc.manager

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

/** Update obsolete bundled refund promises only; operator-authored wording remains untouched. */
class TransferMessageMigrationStep(private val defaults: YamlConfiguration) : MigrationStep {
    override fun fromVersion(): Int = 2
    override fun toVersion(): Int = VERSION
    override fun description(): String = "Replace obsolete transfer refund messages with reconciliation guidance."

    override fun migrate(context: MigrationContext) {
        for ((key, previous) in oldDefaults) {
            val path = "messages.allowance.$key"
            if (context.yaml().getString(path) in previous) {
                context.yaml().set(path, requireNotNull(defaults.getString(path)) { "Missing bundled message: $path" })
            }
        }
    }

    companion object {
        const val VERSION = 3
        private val oldDefaults = mapOf(
            "transfer_failed" to setOf(
                "<prefix> <red>转账失败，已为你退回次数。",
                "<prefix> <red>Transfer failed; your attempt has been refunded.",
            ),
            "transfer_disabled" to setOf(
                "<prefix> <red>内置转账功能未启用，已为你退回本次次数。",
                "<prefix> <red>Built-in transfers are disabled; the attempt has been refunded.",
            ),
            "transfer_review_required" to setOf(
                "<red><prefix> 转账结果需要人工核对，请联系管理员检查双方余额和控制台日志；核对前不要重复执行。",
                "<red><prefix> This transfer requires manual review. " +
                    "Ask an administrator to check both balances and the server log before retrying.",
            ),
        )
    }
}
