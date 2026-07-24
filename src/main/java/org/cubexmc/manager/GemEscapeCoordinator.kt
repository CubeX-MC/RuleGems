package org.cubexmc.manager

import org.bukkit.Location
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.RuleGems
import org.cubexmc.utils.SchedulerUtil
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.LongSupplier
import kotlin.math.ceil

enum class GemEscapeMode {
    LOCAL,
    GLOBAL_FALLBACK,
}

data class GemEscapeRequest(
    val gemId: UUID,
    val expectedLocation: Location,
    val mode: GemEscapeMode,
    val failedRounds: Int,
    val lifecycle: Long,
)

enum class GemEscapeRelocationStatus {
    SUCCESS,
    FAILED,
    STALE,
}

data class GemEscapeRelocationResult(
    val status: GemEscapeRelocationStatus,
    val newLocation: Location? = null,
)

fun interface GemEscapeRelocator {
    fun relocate(request: GemEscapeRequest, completion: Consumer<GemEscapeRelocationResult>)
}

fun interface GemEscapeSuccessListener {
    fun onSuccess(request: GemEscapeRequest, result: GemEscapeRelocationResult)
}

/**
 * Coordinates escape cadence and retry state without touching Bukkit world data.
 * World validation and the actual two-location commit stay in [GemPlacementManager].
 */
class GemEscapeCoordinator(
    private val plugin: RuleGems,
    private val gameplayConfig: GameplayConfig,
    private val stateManager: GemStateManager,
    private val retryTasks: MutableMap<UUID, Any>,
    private val relocator: GemEscapeRelocator,
    private val successListener: GemEscapeSuccessListener,
    private val saveAction: Runnable,
    private val nowMillis: LongSupplier,
    private val random: Random,
) {
    constructor(
        plugin: RuleGems,
        gameplayConfig: GameplayConfig,
        stateManager: GemStateManager,
        retryTasks: MutableMap<UUID, Any>,
        relocator: GemEscapeRelocator,
        successListener: GemEscapeSuccessListener,
        saveAction: Runnable,
    ) : this(
        plugin,
        gameplayConfig,
        stateManager,
        retryTasks,
        relocator,
        successListener,
        saveAction,
        LongSupplier { System.currentTimeMillis() },
        Random(),
    )

    private val lastMovedAt: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val failedRounds: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val localEscapesWithoutPickup: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val inProgress: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val retryVersions: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val lifecycle = AtomicLong()
    private val globalVersion = AtomicLong()

    @Volatile
    private var initialized = false

    @Volatile
    private var globalTask: Any? = null

    @Volatile
    private var nextCycleAtMillis = 0L

    fun prepareReload() {
        initialized = false
        cancelScheduledTasks()
        lifecycle.incrementAndGet()
        lastMovedAt.clear()
        failedRounds.clear()
        localEscapesWithoutPickup.clear()
        inProgress.clear()
        nextCycleAtMillis = 0L
    }

    fun loadState(data: FileConfiguration?) {
        if (data == null) return
        nextCycleAtMillis = data.getLong("escape-state.next_cycle_at", 0L).coerceAtLeast(0L)
        val gems = data.getConfigurationSection("escape-state.gems") ?: return
        for (rawId in gems.getKeys(false)) {
            val gemId = try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid escape-state UUID '$rawId'.")
                continue
            }
            val base = "escape-state.gems.$rawId"
            val movedAt = data.getLong("$base.last_moved_at", 0L)
            if (movedAt > 0L) {
                lastMovedAt[gemId] = movedAt
            }
            val failures = data.getInt("$base.failed_rounds", 0).coerceAtLeast(0)
            if (failures > 0) {
                failedRounds[gemId] = failures
            }
            val localEscapes = data.getInt("$base.local_escapes_without_pickup", 0).coerceAtLeast(0)
            if (localEscapes > 0) {
                localEscapesWithoutPickup[gemId] = localEscapes
            }
        }
    }

    fun initialize() {
        cancelScheduledTasks()
        lifecycle.incrementAndGet()
        inProgress.clear()
        val knownGemIds = stateManager.gemUuidToKey.keys.toSet()
        lastMovedAt.keys.retainAll(knownGemIds)
        failedRounds.keys.retainAll(knownGemIds)
        localEscapesWithoutPickup.keys.retainAll(knownGemIds)

        val now = nowMillis.asLong
        for (gemId in knownGemIds) {
            lastMovedAt.compute(gemId) { _, stored ->
                if (stored == null || stored <= 0L || stored > now) now else stored
            }
        }
        initialized = true

        if (!gameplayConfig.isGemEscapeEnabled) {
            nextCycleAtMillis = 0L
            return
        }
        scheduleNextGlobalCycle(false)
    }

    fun shutdown() {
        initialized = false
        cancelScheduledTasks()
        lifecycle.incrementAndGet()
        inProgress.clear()
    }

    fun resetForScatter() {
        cancelScheduledTasks()
        lifecycle.incrementAndGet()
        lastMovedAt.clear()
        failedRounds.clear()
        localEscapesWithoutPickup.clear()
        inProgress.clear()
        nextCycleAtMillis = 0L
        if (initialized && gameplayConfig.isGemEscapeEnabled) {
            scheduleNextGlobalCycle(true)
        }
    }

    fun ensureTracked(gemId: UUID?) {
        if (gemId == null) return
        lastMovedAt.putIfAbsent(gemId, nowMillis.asLong)
        if (initialized && gameplayConfig.isGemEscapeEnabled) {
            scheduleNextGlobalCycle(false)
        }
    }

    fun recordMovement(gemId: UUID?) {
        if (gemId == null) return
        cancelRetry(gemId)
        inProgress.remove(gemId)
        failedRounds.remove(gemId)
        localEscapesWithoutPickup.remove(gemId)
        lastMovedAt[gemId] = nowMillis.asLong
        if (initialized && gameplayConfig.isGemEscapeEnabled) {
            scheduleNextGlobalCycle(false)
        }
    }

    fun recordPickup(gemId: UUID?) {
        if (gemId == null) return
        cancelRetry(gemId)
        inProgress.remove(gemId)
        failedRounds.remove(gemId)
        localEscapesWithoutPickup.remove(gemId)
    }

    fun isRelocating(gemId: UUID?): Boolean = gemId != null && inProgress.contains(gemId)

    fun currentLifecycle(): Long = lifecycle.get()

    fun populateSaveSnapshot(snapshot: MutableMap<String, Any?>) {
        if (nextCycleAtMillis > 0L) {
            snapshot["escape-state.next_cycle_at"] = nextCycleAtMillis
        }
        val knownGemIds = stateManager.gemUuidToKey.keys
        for (gemId in knownGemIds) {
            val base = "escape-state.gems.$gemId"
            val movedAt = lastMovedAt[gemId]
            if (movedAt != null) {
                snapshot["$base.last_moved_at"] = movedAt
            }
            val failures = failedRounds[gemId]
            if (failures != null && failures > 0) {
                snapshot["$base.failed_rounds"] = failures
            }
            val localEscapes = localEscapesWithoutPickup[gemId]
            if (localEscapes != null && localEscapes > 0) {
                snapshot["$base.local_escapes_without_pickup"] = localEscapes
            }
        }
    }

    private fun cancelScheduledTasks() {
        SchedulerUtil.cancelTask(globalTask)
        globalTask = null
        globalVersion.incrementAndGet()
        for (task in retryTasks.values) {
            SchedulerUtil.cancelTask(task)
        }
        retryTasks.clear()
        retryVersions.clear()
    }

    @Synchronized
    private fun scheduleNextGlobalCycle(forceNewDeadline: Boolean) {
        if (!initialized || !gameplayConfig.isGemEscapeEnabled || globalTask != null) return

        val now = nowMillis.asLong
        val maxDelayMillis = ticksToMillis(gameplayConfig.gemEscapeMaxIntervalTicks)
        if (
            forceNewDeadline ||
            nextCycleAtMillis <= now ||
            nextCycleAtMillis > safeAdd(now, maxDelayMillis)
        ) {
            nextCycleAtMillis = safeAdd(now, ticksToMillis(drawGlobalDelayTicks()))
        }
        val delayTicks = millisToTicksCeil(nextCycleAtMillis - now)
        val version = globalVersion.incrementAndGet()
        val scheduled = SchedulerUtil.globalRun(
            plugin,
            {
                if (globalVersion.get() == version) {
                    globalTask = null
                    runGlobalCycle()
                }
            },
            delayTicks,
            -1L,
        )
        if (scheduled == null) {
            plugin.logger.warning("Unable to schedule the next gem escape cycle.")
            nextCycleAtMillis = 0L
            return
        }
        globalTask = scheduled
    }

    private fun runGlobalCycle() {
        if (!initialized || !gameplayConfig.isGemEscapeEnabled) return
        nextCycleAtMillis = 0L
        scheduleNextGlobalCycle(true)
        val gemId = selectEligibleGem() ?: run {
            saveAction.run()
            return
        }
        attemptEscape(gemId, false)
        saveAction.run()
    }

    private fun selectEligibleGem(): UUID? {
        val now = nowMillis.asLong
        val minimumAgeMillis = ticksToMillis(gameplayConfig.gemEscapeMinimumUnmovedTicks)
        val locations = stateManager.gemUuidToLocation.entries
            .associate { it.key to it.value.clone() }
        var selected: UUID? = null
        var selectedScore = Double.NEGATIVE_INFINITY
        for ((gemId, location) in locations) {
            if (stateManager.getGemHolder(gemId) != null) continue
            if (inProgress.contains(gemId) || retryTasks.containsKey(gemId)) continue
            val movedAt = lastMovedAt[gemId] ?: continue
            val ageMillis = now - movedAt
            if (ageMillis < minimumAgeMillis) continue

            val nearby = locations.count { (otherId, otherLocation) ->
                otherId != gemId &&
                    otherLocation.world == location.world &&
                    otherLocation.distanceSquared(location) <=
                    gameplayConfig.gemEscapeClusterRadius * gameplayConfig.gemEscapeClusterRadius
            }
            val clusterMultiplier = 1.0 + nearby * (gameplayConfig.gemEscapeClusterWeight - 1.0)
            val score = ageMillis.toDouble() * clusterMultiplier
            if (
                selected == null ||
                score > selectedScore ||
                (score == selectedScore && gemId.toString() < selected.toString())
            ) {
                selected = gemId
                selectedScore = score
            }
        }
        return selected
    }

    private fun attemptEscape(gemId: UUID, bypassMinimumAge: Boolean) {
        if (!initialized || !gameplayConfig.isGemEscapeEnabled) return
        val location = stateManager.getGemLocation(gemId)?.clone() ?: return
        if (stateManager.getGemHolder(gemId) != null) return
        if (!bypassMinimumAge) {
            val movedAt = lastMovedAt[gemId] ?: nowMillis.asLong
            if (nowMillis.asLong - movedAt < ticksToMillis(gameplayConfig.gemEscapeMinimumUnmovedTicks)) return
        }
        if (!inProgress.add(gemId)) return

        val failures = failedRounds[gemId] ?: 0
        val localEscapes = localEscapesWithoutPickup[gemId] ?: 0
        val mode = if (
            failures >= gameplayConfig.gemEscapeMaxFailedRounds ||
            localEscapes >= gameplayConfig.gemEscapeMaxLocalEscapesWithoutPickup
        ) {
            GemEscapeMode.GLOBAL_FALLBACK
        } else {
            GemEscapeMode.LOCAL
        }
        val request = GemEscapeRequest(gemId, location, mode, failures, lifecycle.get())
        try {
            relocator.relocate(request, Consumer { result -> handleRelocationResult(request, result) })
        } catch (error: Throwable) {
            plugin.logger.warning("Gem escape relocation failed for $gemId: ${error.message}")
            handleRelocationResult(request, GemEscapeRelocationResult(GemEscapeRelocationStatus.FAILED))
        }
    }

    private fun handleRelocationResult(request: GemEscapeRequest, result: GemEscapeRelocationResult) {
        if (request.lifecycle != lifecycle.get() || !initialized) return
        if (!inProgress.remove(request.gemId)) return

        when (result.status) {
            GemEscapeRelocationStatus.SUCCESS -> {
                cancelRetry(request.gemId)
                lastMovedAt[request.gemId] = nowMillis.asLong
                failedRounds.remove(request.gemId)
                if (request.mode == GemEscapeMode.LOCAL) {
                    localEscapesWithoutPickup.merge(request.gemId, 1) { first, second -> first + second }
                } else {
                    localEscapesWithoutPickup.remove(request.gemId)
                }
                rescheduleGlobalCycleAfterMovement()
                try {
                    successListener.onSuccess(request, result)
                } catch (error: Throwable) {
                    plugin.logger.warning("Gem escape success callback failed for ${request.gemId}: ${error.message}")
                } finally {
                    saveAction.run()
                }
            }

            // A pickup or a newer placement owns the state transition. Those paths
            // update their own counters; a transient contention must not erase history.
            GemEscapeRelocationStatus.STALE -> Unit

            GemEscapeRelocationStatus.FAILED -> handleFailure(request)
        }
    }

    private fun handleFailure(request: GemEscapeRequest) {
        if (request.mode == GemEscapeMode.LOCAL) {
            val failures = request.failedRounds + 1
            failedRounds[request.gemId] = failures
            saveAction.run()
            if (failures >= gameplayConfig.gemEscapeMaxFailedRounds) {
                attemptEscape(request.gemId, true)
                return
            }
        } else {
            failedRounds[request.gemId] = gameplayConfig.gemEscapeMaxFailedRounds
            saveAction.run()
        }
        scheduleRetry(request.gemId)
    }

    private fun scheduleRetry(gemId: UUID) {
        if (!initialized || !gameplayConfig.isGemEscapeEnabled) return
        cancelRetry(gemId)
        val version = (retryVersions[gemId] ?: 0L) + 1L
        retryVersions[gemId] = version
        val lifecycleSnapshot = lifecycle.get()
        val task = SchedulerUtil.globalRun(
            plugin,
            {
                if (
                    initialized &&
                    lifecycle.get() == lifecycleSnapshot &&
                    retryVersions[gemId] == version
                ) {
                    retryTasks.remove(gemId)
                    attemptEscape(gemId, true)
                }
            },
            gameplayConfig.gemEscapeRetryDelayTicks.coerceAtLeast(1L),
            -1L,
        )
        if (task != null) {
            retryTasks[gemId] = task
        } else {
            plugin.logger.warning("Unable to schedule gem escape retry for $gemId.")
        }
    }

    private fun cancelRetry(gemId: UUID) {
        retryVersions.compute(gemId) { _, value -> (value ?: 0L) + 1L }
        SchedulerUtil.cancelTask(retryTasks.remove(gemId))
    }

    @Synchronized
    private fun rescheduleGlobalCycleAfterMovement() {
        SchedulerUtil.cancelTask(globalTask)
        globalTask = null
        globalVersion.incrementAndGet()
        nextCycleAtMillis = 0L
        if (initialized && gameplayConfig.isGemEscapeEnabled) {
            scheduleNextGlobalCycle(true)
        }
    }

    private fun drawGlobalDelayTicks(): Long {
        val minTicks = gameplayConfig.gemEscapeMinIntervalTicks.coerceAtLeast(1L)
        val maxTicks = gameplayConfig.gemEscapeMaxIntervalTicks.coerceAtLeast(minTicks)
        val width = maxTicks - minTicks
        return if (width <= 0L) minTicks else minTicks + (random.nextDouble() * width).toLong()
    }

    private fun ticksToMillis(ticks: Long): Long {
        val safeTicks = ticks.coerceAtLeast(0L)
        return if (safeTicks > Long.MAX_VALUE / MILLIS_PER_TICK) {
            Long.MAX_VALUE
        } else {
            safeTicks * MILLIS_PER_TICK
        }
    }

    private fun safeAdd(first: Long, second: Long): Long {
        if (second <= 0L) return first
        return if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
    }

    private fun millisToTicksCeil(millis: Long): Long =
        ceil(millis.coerceAtLeast(1L).toDouble() / MILLIS_PER_TICK).toLong().coerceAtLeast(1L)

    companion object {
        private const val MILLIS_PER_TICK = 50L
    }
}
