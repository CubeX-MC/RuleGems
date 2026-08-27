package org.cubexmc.manager

import org.cubexmc.RuleGems
import org.cubexmc.config.ReloadChain
import org.cubexmc.config.ReloadFailurePolicy
import org.cubexmc.config.ReloadReport
import java.util.function.BooleanSupplier

/** Serializes reload and prevents loading stale data when any persistence barrier fails. */
class RuleGemsReloadCoordinator(
    private val operations: GlobalOperationCoordinator,
    private val closeSessions: Runnable,
    private val flushFeatures: Runnable,
    private val saveGems: BooleanSupplier,
    private val load: BooleanSupplier,
    private val refreshProxies: Runnable,
) {
    var report: ReloadReport? = null
        private set

    fun reload(): RuleGems.ReloadResult {
        if (!operations.tryBegin(GlobalOperation.RELOAD)) return RuleGems.ReloadResult.BUSY
        return try {
            val result = ReloadChain.create()
                .failurePolicy(ReloadFailurePolicy.ABORT)
                .add("close-sessions") { closeSessions.run() }
                .add("feature-save") { flushFeatures.run() }
                .add("gem-save") { check(saveGems.asBoolean) { "Gem snapshot could not be persisted" } }
                .add("load") { check(load.asBoolean) { "Plugin loading chain failed" } }
                .add("command-proxies") { refreshProxies.run() }
                .run()
            report = result
            if (result.ok()) RuleGems.ReloadResult.SUCCESS else RuleGems.ReloadResult.FAILED
        } finally {
            operations.end(GlobalOperation.RELOAD)
        }
    }
}
