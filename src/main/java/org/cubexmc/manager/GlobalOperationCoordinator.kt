package org.cubexmc.manager

import java.util.concurrent.atomic.AtomicReference

enum class GlobalOperation {
    RELOAD,
    SCATTER,
}

/**
 * Serializes whole-plugin state rebuilds. The operation itself remains responsible
 * for scheduling Bukkit work on a supported server thread.
 */
class GlobalOperationCoordinator {
    private val active = AtomicReference<GlobalOperation?>(null)

    fun tryBegin(operation: GlobalOperation): Boolean = active.compareAndSet(null, operation)

    fun end(operation: GlobalOperation) {
        check(active.compareAndSet(operation, null)) {
            "Attempted to end $operation while ${active.get()} was active"
        }
    }

    fun current(): GlobalOperation? = active.get()
}
