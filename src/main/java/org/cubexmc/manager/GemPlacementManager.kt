package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.model.ExecuteConfig
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.EffectUtils
import org.cubexmc.utils.SchedulerUtil
import java.io.File
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 宝石放置管理器 - 负责宝石的放置、散落、逃逸。
 */
class GemPlacementManager(
    private val plugin: RuleGems,
    private val gemParser: GemDefinitionParser,
    private val gameplayConfig: GameplayConfig,
    private val languageManager: LanguageManager,
    private val stateManager: GemStateManager,
) {
    private var effectUtils: EffectUtils? = null
    private var saveCallback: Runnable? = null
    val presentationManager = GemPresentationManager(plugin, gameplayConfig, stateManager)

    val gemEscapeTasks: MutableMap<UUID, Any> = ConcurrentHashMap()
    private val gemTransitions: MutableMap<UUID, GemTransition> = ConcurrentHashMap()
    private val transitionLock = Any()
    private val escapeOperationGeneration = AtomicLong()
    private val escapeCoordinator = GemEscapeCoordinator(
        plugin,
        gameplayConfig,
        stateManager,
        gemEscapeTasks,
        GemEscapeRelocator { request, completion -> relocateEscapingGem(request, completion) },
        GemEscapeSuccessListener { request, result -> handleEscapeSuccess(request, result) },
        Runnable { savePlacementState() },
    )

    fun setEffectUtils(effectUtils: EffectUtils?) {
        this.effectUtils = effectUtils
    }

    fun setSaveCallback(saveCallback: Runnable?) {
        this.saveCallback = saveCallback
    }

    fun placeRuleGem(loc: Location?, gemId: UUID?) {
        placeRuleGemInternal(loc, gemId, false)
    }

    private fun placeRuleGemForced(loc: Location?, gemId: UUID?) {
        placeRuleGemInternal(loc, gemId, true)
    }

    private fun placeRuleGemInternal(loc: Location?, gemId: UUID?, ignoreLimit: Boolean) {
        if (loc == null || gemId == null) return
        val base = loc.clone()
        SchedulerUtil.regionRun(
            plugin,
            base,
            {
                val world = base.world ?: return@regionRun
                val replacingExistingGem = stateManager.findLocationByGemId(gemId) != null ||
                    stateManager.getGemHolder(gemId) != null
                if (!ignoreLimit && !replacingExistingGem && stateManager.getTotalGemCount() >= gemParser.requiredCount) {
                    plugin.logger.info("Gem limit reached, skipping placement")
                    return@regionRun
                }
                val border = world.worldBorder
                val target = base.block.location
                var tries = 0
                while (
                    tries < MAX_VERTICAL_SEARCH &&
                    (target.block.type.isSolid || occupiedByAnotherGem(target, gemId))
                ) {
                    target.add(0.0, 1.0, 0.0)
                    tries++
                }
                if (!border.isInside(target) || target.blockY < world.minHeight || target.blockY > world.maxHeight) {
                    randomPlaceGem(gemId)
                    return@regionRun
                }
                val oldLocation = stateManager.findLocationByGemId(gemId)
                if (oldLocation != null && !isSameBlock(oldLocation, target)) {
                    unplaceRuleGem(oldLocation, gemId)
                }
                val mat = stateManager.getGemMaterial(gemId)
                presentationManager.renderPlacedGem(gemId, target, mat)
                stateManager.bindPlacedGem(target, gemId)
                recordGemMovement(gemId)
                savePlacementState()
            },
            0L,
            -1L,
        )
    }

    fun unplaceRuleGem(loc: Location?, gemId: UUID?) {
        unplaceRuleGemThen(loc, gemId, null)
    }

    private fun unplaceRuleGemThen(loc: Location?, gemId: UUID?, afterUnplace: Runnable?) {
        if (loc == null) return
        val fLoc = toBlockLocation(loc) ?: return
        presentationManager.detachPlacedGem(gemId, fLoc)
        stateManager.unbindPlacedGem(fLoc, gemId)
        SchedulerUtil.regionRun(
            plugin,
            fLoc,
            {
                presentationManager.clearLocationIfUnoccupied(fLoc)
                afterUnplace?.run()
            },
            0L,
            -1L,
        )
    }

    fun forcePlaceGem(gemId: UUID?, target: Location?, holder: Player?) {
        if (gemId == null || target == null) return

        val oldLoc = stateManager.findLocationByGemId(gemId)
        val base = target.clone()

        SchedulerUtil.regionRun(
            plugin,
            base,
            {
                val world = base.world ?: return@regionRun
                val border = world.worldBorder
                val t = base.block.location
                if (!border.isInside(t) || t.blockY < world.minHeight || t.blockY > world.maxHeight) {
                    return@regionRun
                }

                val mat = stateManager.getGemMaterial(gemId)
                if (stateManager.isSupportRequired(mat) && !stateManager.hasBlockSupport(t)) {
                    return@regionRun
                }

                try {
                    if (!t.chunk.isLoaded) t.chunk.load()
                } catch (e: Throwable) {
                    plugin.logger.fine("Failed to load chunk for gem placement: " + e.message)
                }

                if (oldLoc != null && !isSameBlock(oldLoc, t)) {
                    unplaceRuleGem(oldLoc, gemId)
                }

                presentationManager.renderPlacedGem(gemId, t, mat)
                stateManager.bindPlacedGem(t, gemId)
                recordGemMovement(gemId)
                savePlacementState()
            },
            0L,
            -1L,
        )
    }

    fun randomPlaceGem(gemId: UUID?, corner1: Location?, corner2: Location?) {
        stateManager.ensureGemKeyAssigned(gemId)
        scheduleRandomAttempt(gemId, corner1, corner2, MAX_RANDOM_ATTEMPTS)
    }

    fun randomPlaceGem(gemId: UUID?) {
        if (gemId == null) return
        val range = getGemPlaceRange(gemId)
        if (range != null) {
            randomPlaceGem(gemId, range[0], range[1])
        } else {
            plugin.logger.warning(
                "Cannot place gem $gemId: no spawn range configured, falling back to overworld spawn",
            )
            val defaultWorld = if (Bukkit.getWorlds().isEmpty()) null else Bukkit.getWorlds()[0]
            if (defaultWorld != null) {
                placeRuleGemForced(defaultWorld.spawnLocation, gemId)
            } else {
                plugin.logger.severe("Cannot place gem $gemId: no available world! Gem will be in unknown state")
            }
        }
    }

    fun adoptPlayerPlacedGem(gemId: UUID?, location: Location?) {
        if (gemId == null || location == null) return
        val target = location.block.location
        SchedulerUtil.regionRun(
            plugin,
            target,
            {
                presentationManager.renderPlacedGem(gemId, target, stateManager.getGemMaterial(gemId))
                recordGemMovement(gemId)
                savePlacementState()
            },
            0L,
            -1L,
        )
    }

    private fun scheduleRandomAttempt(gemId: UUID?, corner1: Location?, corner2: Location?, attemptsLeft: Int) {
        if (corner1 == null || corner2 == null) return
        if (corner1.world != corner2.world) return

        if (attemptsLeft <= 0) {
            plugin.logger.warning("Random placement failed for gem $gemId, falling back to range center")
            val centerX = (corner1.blockX + corner2.blockX) / 2
            val centerZ = (corner1.blockZ + corner2.blockZ) / 2
            val world = corner1.world ?: return
            val y = world.getHighestBlockYAt(centerX, centerZ) + 1
            placeRuleGemForced(Location(world, centerX.toDouble(), y.toDouble(), centerZ.toDouble()), gemId)
            return
        }

        val world = corner1.world ?: return
        val rand = Random()
        val minX = minOf(corner1.blockX, corner2.blockX)
        val minZ = minOf(corner1.blockZ, corner2.blockZ)
        val maxX = maxOf(corner1.blockX, corner2.blockX)
        val maxZ = maxOf(corner1.blockZ, corner2.blockZ)
        val x = rand.nextInt(maxX - minX + 1) + minX
        val z = rand.nextInt(maxZ - minZ + 1) + minZ
        val candidate = Location(world, x.toDouble(), (world.minHeight + 1).toDouble(), z.toDouble())
        SchedulerUtil.regionRun(
            plugin,
            candidate,
            {
                try {
                    val y = world.getHighestBlockYAt(x, z) + 1
                    val place = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    val border = world.worldBorder
                    if (!border.isInside(place)) {
                        scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1)
                        return@regionRun
                    }
                    placeRuleGemForced(place, gemId)
                } catch (_: Throwable) {
                    scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1)
                }
            },
            0L,
            -1L,
        )
    }

    private fun getGemPlaceRange(gemId: UUID?): Array<Location>? {
        val gemKey = stateManager.getGemKey(gemId)
        if (gemKey != null) {
            for (definition in gemParser.gemDefinitions) {
                if (definition.gemKey == gemKey) {
                    val c1 = definition.randomPlaceCorner1
                    val c2 = definition.randomPlaceCorner2
                    if (c1 != null && c2 != null) {
                        return arrayOf(c1, c2)
                    }
                    break
                }
            }
        }
        val defaultC1 = gameplayConfig.randomPlaceCorner1
        val defaultC2 = gameplayConfig.randomPlaceCorner2
        if (defaultC1 != null && defaultC2 != null) {
            return arrayOf(defaultC1, defaultC2)
        }
        return null
    }

    fun scheduleEscape(gemId: UUID?) {
        escapeCoordinator.ensureTracked(gemId)
    }

    private fun recordGemMovement(gemId: UUID) {
        synchronized(transitionLock) {
            gemTransitions.remove(gemId, GemTransition.ESCAPE)
        }
        escapeCoordinator.recordMovement(gemId)
    }

    fun cancelEscape(gemId: UUID?) {
        escapeCoordinator.recordPickup(gemId)
    }

    fun cancelAllEscapeTasks() {
        shutdownEscape()
    }

    fun initializeEscapeTasks() {
        escapeCoordinator.initialize()
    }

    fun prepareEscapeReload() {
        synchronized(transitionLock) {
            escapeOperationGeneration.incrementAndGet()
            gemTransitions.clear()
            escapeCoordinator.prepareReload()
        }
    }

    fun loadEscapeState(data: org.bukkit.configuration.file.FileConfiguration?) {
        escapeCoordinator.loadState(data)
    }

    fun populateEscapeSaveSnapshot(snapshot: MutableMap<String, Any?>) {
        escapeCoordinator.populateSaveSnapshot(snapshot)
    }

    fun resetEscapeStateForScatter() {
        synchronized(transitionLock) {
            escapeOperationGeneration.incrementAndGet()
            gemTransitions.clear()
            escapeCoordinator.resetForScatter()
        }
    }

    fun shutdownEscape() {
        synchronized(transitionLock) {
            escapeOperationGeneration.incrementAndGet()
            gemTransitions.clear()
            escapeCoordinator.shutdown()
        }
    }

    fun tryBeginPickup(gemId: UUID?): Boolean {
        if (gemId == null) return false
        synchronized(transitionLock) {
            return gemTransitions.putIfAbsent(gemId, GemTransition.PICKUP) == null
        }
    }

    fun endPickup(gemId: UUID?) {
        if (gemId == null) return
        synchronized(transitionLock) {
            gemTransitions.remove(gemId, GemTransition.PICKUP)
        }
    }

    private fun relocateEscapingGem(
        request: GemEscapeRequest,
        completion: Consumer<GemEscapeRelocationResult>,
    ) {
        val operationGeneration: Long
        synchronized(transitionLock) {
            if (
                request.lifecycle != escapeCoordinator.currentLifecycle() ||
                gemTransitions.putIfAbsent(request.gemId, GemTransition.ESCAPE) != null
            ) {
                completion.accept(GemEscapeRelocationResult(GemEscapeRelocationStatus.STALE))
                return
            }
            operationGeneration = escapeOperationGeneration.get()
        }

        val completed = AtomicBoolean(false)
        fun finish(result: GemEscapeRelocationResult) {
            if (!completed.compareAndSet(false, true)) return
            try {
                completion.accept(result)
            } finally {
                synchronized(transitionLock) {
                    gemTransitions.remove(request.gemId, GemTransition.ESCAPE)
                }
            }
        }

        val attempts = gameplayConfig.gemEscapeAttemptsPerRound.coerceAtLeast(1)

        fun attemptCandidate(attempt: Int) {
            if (!isEscapeRequestCurrent(request, operationGeneration)) {
                finish(GemEscapeRelocationResult(GemEscapeRelocationStatus.STALE))
                return
            }
            if (attempt >= attempts) {
                finish(GemEscapeRelocationResult(GemEscapeRelocationStatus.FAILED))
                return
            }

            val candidate = createEscapeCandidate(request)
            if (candidate == null) {
                finish(GemEscapeRelocationResult(GemEscapeRelocationStatus.FAILED))
                return
            }
            val task = SchedulerUtil.regionRun(
                plugin,
                candidate,
                {
                    if (!isEscapeRequestCurrent(request, operationGeneration)) {
                        finish(GemEscapeRelocationResult(GemEscapeRelocationStatus.STALE))
                        return@regionRun
                    }
                    val world = candidate.world
                    if (world == null) {
                        attemptCandidate(attempt + 1)
                        return@regionRun
                    }
                    val target = try {
                        val y = world.getHighestBlockYAt(candidate.blockX, candidate.blockZ) + 1
                        Location(world, candidate.blockX.toDouble(), y.toDouble(), candidate.blockZ.toDouble())
                    } catch (_: Throwable) {
                        attemptCandidate(attempt + 1)
                        return@regionRun
                    }

                    if (!isValidEscapeTarget(request, target)) {
                        attemptCandidate(attempt + 1)
                        return@regionRun
                    }

                    var committed = false
                    var commitError: Throwable? = null
                    synchronized(transitionLock) {
                        if (isEscapeRequestCurrent(request, operationGeneration) && isValidEscapeTarget(request, target)) {
                            try {
                                presentationManager.renderPlacedGem(
                                    request.gemId,
                                    target,
                                    stateManager.getGemMaterial(request.gemId),
                                )
                                stateManager.bindPlacedGem(target, request.gemId)
                                committed = true
                            } catch (error: Throwable) {
                                commitError = error
                            }
                        }
                    }
                    if (!committed) {
                        if (commitError != null) {
                            plugin.logger.warning(
                                "Failed to commit escape relocation for ${request.gemId}: ${commitError?.message}",
                            )
                            if (!isSameBlock(stateManager.getGemLocation(request.gemId), target)) {
                                presentationManager.clearLocationIfUnoccupied(target)
                            }
                        }
                        attemptCandidate(attempt + 1)
                        return@regionRun
                    }

                    // The UUID points at the destination before the old representation is cleared.
                    // Navigator sessions therefore never observe an unbound gem.
                    try {
                        unplaceRuleGem(request.expectedLocation, request.gemId)
                    } catch (error: Throwable) {
                        plugin.logger.warning(
                            "Gem ${request.gemId} moved, but its old presentation cleanup failed: ${error.message}",
                        )
                    }
                    finish(
                        GemEscapeRelocationResult(
                            GemEscapeRelocationStatus.SUCCESS,
                            target.clone(),
                        ),
                    )
                },
                0L,
                -1L,
            )
            if (task == null) {
                attemptCandidate(attempt + 1)
            }
        }

        attemptCandidate(0)
    }

    private fun isEscapeRequestCurrent(request: GemEscapeRequest, operationGeneration: Long): Boolean {
        if (operationGeneration != escapeOperationGeneration.get()) return false
        if (request.lifecycle != escapeCoordinator.currentLifecycle()) return false
        if (gemTransitions[request.gemId] != GemTransition.ESCAPE) return false
        if (stateManager.getGemHolder(request.gemId) != null) return false
        return isSameBlock(stateManager.getGemLocation(request.gemId), request.expectedLocation)
    }

    private fun isValidEscapeTarget(request: GemEscapeRequest, target: Location): Boolean {
        val world = target.world ?: return false
        if (isSameBlock(request.expectedLocation, target)) return false
        if (!world.worldBorder.isInside(target)) return false
        if (target.blockY < world.minHeight || target.blockY >= world.maxHeight) return false
        val block = target.block
        if (block.type.isSolid || block.isLiquid || occupiedByAnotherGem(target, request.gemId)) return false
        val material = stateManager.getGemMaterial(request.gemId)
        return !stateManager.isSupportRequired(material) || stateManager.hasBlockSupport(target)
    }

    private fun createEscapeCandidate(request: GemEscapeRequest): Location? {
        return if (request.mode == GemEscapeMode.GLOBAL_FALLBACK) {
            createGlobalEscapeCandidate(request.gemId)
        } else {
            createLocalEscapeCandidate(request.expectedLocation, request.failedRounds)
        }
    }

    private fun createLocalEscapeCandidate(origin: Location, failedRound: Int): Location? {
        val world = origin.world ?: return null
        val baseMin = gameplayConfig.gemEscapeLocalMinDistance.coerceAtLeast(1.0)
        val baseMax = gameplayConfig.gemEscapeLocalMaxDistance.coerceAtLeast(baseMin)
        val growth = gameplayConfig.gemEscapeDistanceGrowth.coerceAtLeast(0.0)
        val minDistance = if (failedRound <= 0) baseMin else baseMax + growth * (failedRound - 1)
        val maxDistance = (baseMax + growth * failedRound).coerceAtLeast(minDistance)
        val random = ThreadLocalRandom.current()
        val squaredDistance = minDistance * minDistance +
            random.nextDouble() * (maxDistance * maxDistance - minDistance * minDistance)
        val distance = sqrt(squaredDistance)
        val angle = random.nextDouble() * PI * 2.0
        val x = floor(origin.x + cos(angle) * distance).toInt()
        val z = floor(origin.z + sin(angle) * distance).toInt()
        return Location(world, x.toDouble(), (world.minHeight + 1).toDouble(), z.toDouble())
    }

    private fun createGlobalEscapeCandidate(gemId: UUID): Location? {
        val range = getGemPlaceRange(gemId) ?: return null
        val first = range[0]
        val second = range[1]
        val world = first.world ?: return null
        if (world != second.world) return null
        val minX = minOf(first.blockX, second.blockX)
        val maxX = maxOf(first.blockX, second.blockX)
        val minZ = minOf(first.blockZ, second.blockZ)
        val maxZ = maxOf(first.blockZ, second.blockZ)
        val random = ThreadLocalRandom.current()
        val x = floor(minX + random.nextDouble() * (maxX.toLong() - minX.toLong() + 1L)).toInt()
        val z = floor(minZ + random.nextDouble() * (maxZ.toLong() - minZ.toLong() + 1L)).toInt()
        return Location(world, x.toDouble(), (world.minHeight + 1).toDouble(), z.toDouble())
    }

    private fun handleEscapeSuccess(request: GemEscapeRequest, result: GemEscapeRelocationResult) {
        if (result.status != GemEscapeRelocationStatus.SUCCESS || result.newLocation == null) return
        playEscapeEffects(request.expectedLocation, request.gemId)
        if (gameplayConfig.isGemEscapeBroadcast) {
            broadcastEscape(request.gemId, request.mode)
        }
    }

    private fun savePlacementState() {
        saveCallback?.run()
    }

    private fun isSameBlock(first: Location?, second: Location?): Boolean {
        if (first == null || second == null || first.world == null || second.world == null) {
            return false
        }
        return first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ
    }

    private fun occupiedByAnotherGem(location: Location, gemId: UUID): Boolean {
        val occupant = stateManager.locationToGemUuid[location.block.location]
        return occupant != null && occupant != gemId
    }

    private fun playEscapeEffects(location: Location?, gemId: UUID?) {
        if (location == null || location.world == null) return

        val loc = location.clone().add(0.5, 0.5, 0.5)
        SchedulerUtil.regionRun(
            plugin,
            loc,
            {
                val world = loc.world ?: return@regionRun

                val particleStr = gameplayConfig.gemEscapeParticle
                if (!particleStr.isNullOrEmpty()) {
                    try {
                        val particle = Particle.valueOf(particleStr.uppercase())
                        try {
                            world.spawnParticle(particle, loc, 50, 0.5, 0.5, 0.5, 0.1)
                        } catch (_: IllegalArgumentException) {
                            plugin.logger.warning(
                                "Could not spawn escape particle $particle: requires data like BlockData.",
                            )
                        }
                    } catch (e: IllegalArgumentException) {
                        plugin.logger.warning("Invalid gem escape particle type '$particleStr': " + e.message)
                    }
                }

                val soundStr = gameplayConfig.gemEscapeSound
                if (!soundStr.isNullOrEmpty()) {
                    try {
                        val sound = Sound.valueOf(soundStr.uppercase())
                        world.playSound(loc, sound, 1.0f, 1.0f)
                    } catch (e: IllegalArgumentException) {
                        plugin.logger.warning("Invalid gem escape sound type '$soundStr': " + e.message)
                    }
                }
            },
            0L,
            -1L,
        )
    }

    private fun broadcastEscape(gemId: UUID?, mode: GemEscapeMode) {
        val gemKey = stateManager.gemUuidToKey.getOrDefault(gemId, "unknown")
        val definition = stateManager.findGemDefinition(gemKey)
        val gemName = definition?.displayName ?: gemKey

        val placeholders = HashMap<String, String>()
        placeholders["gem_name"] = gemName
        placeholders["gem_key"] = gemKey

        val messageKey = if (mode == GemEscapeMode.GLOBAL_FALLBACK) {
            "gem_escape.fallback_broadcast"
        } else {
            "gem_escape.broadcast"
        }
        SchedulerUtil.globalRun(
            plugin,
            {
                for (player in Bukkit.getOnlinePlayers()) {
                    SchedulerUtil.entityRun(
                        plugin,
                        player,
                        { languageManager.sendMessage(player, messageKey, placeholders) },
                        0L,
                        -1L,
                    )
                }
                languageManager.logMessage("gem_escape", placeholders)
            },
            0L,
            -1L,
        )
    }

    fun setGemAltarLocation(gemKey: String?, location: Location?) {
        val definition = stateManager.findGemDefinition(gemKey)
        if (definition != null) {
            definition.altarLocation = location
            saveGemAltarToConfig(gemKey, location)
        }
    }

    fun removeGemAltarLocation(gemKey: String?) {
        val definition = stateManager.findGemDefinition(gemKey)
        if (definition != null) {
            definition.altarLocation = null
            removeGemAltarFromConfig(gemKey)
        }
    }

    private fun saveGemAltarToConfig(gemKey: String?, loc: Location?) {
        if (gemKey == null || loc == null) return
        val gemsFolder = File(plugin.dataFolder, "gems")
        if (!gemsFolder.exists()) return

        val files = gemsFolder.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.endsWith(".yml")) {
                val yaml = YamlConfiguration.loadConfiguration(file)
                if (yaml.contains(gemKey)) {
                    yaml.set("$gemKey.altar.world", loc.world?.name)
                    yaml.set("$gemKey.altar.x", loc.blockX)
                    yaml.set("$gemKey.altar.y", loc.blockY)
                    yaml.set("$gemKey.altar.z", loc.blockZ)
                    try {
                        yaml.save(file)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to save altar config: " + e.message)
                    }
                    return
                }
            }
        }
    }

    private fun removeGemAltarFromConfig(gemKey: String?) {
        if (gemKey == null) return
        val gemsFolder = File(plugin.dataFolder, "gems")
        if (!gemsFolder.exists()) return

        val files = gemsFolder.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.endsWith(".yml")) {
                val yaml = YamlConfiguration.loadConfiguration(file)
                if (yaml.contains(gemKey)) {
                    yaml.set("$gemKey.altar", null)
                    try {
                        yaml.save(file)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to remove altar config: " + e.message)
                    }
                    return
                }
            }
        }
    }

    fun startParticleEffectTask(defaultParticle: Particle?) {
        SchedulerUtil.globalRun(
            plugin,
            {
                for (loc in stateManager.locationToGemUuid.keys) {
                    val target = loc
                    SchedulerUtil.regionRun(
                        plugin,
                        target,
                        {
                            val world = target.world ?: return@regionRun
                            val id = stateManager.locationToGemUuid[target]
                            val definition = if (id != null) {
                                stateManager.findGemDefinition(stateManager.gemUuidToKey[id])
                            } else {
                                null
                            }
                            val particle = definition?.particle ?: defaultParticle
                            if (particle != null) {
                                try {
                                    world.spawnParticle(
                                        particle,
                                        target.x + 0.5,
                                        target.y + 0.5,
                                        target.z + 0.5,
                                        1,
                                    )
                                } catch (_: IllegalArgumentException) {
                                    // Continuous particles may require BlockData on some server versions.
                                }
                            }
                        },
                        0L,
                        -1L,
                    )
                }
            },
            0L,
            20L,
        )
    }

    fun initializePlacedGemBlocks() {
        presentationManager.synchronizePlacedGems(stateManager.gemUuidToLocation)
    }

    fun restoreGemBlocks(gems: Map<UUID, Location>?) {
        if (gems.isNullOrEmpty()) return
        for ((gemId, loc) in gems) {
            if (loc.world == null) continue

            val key = stateManager.gemUuidToKey[gemId]
            var material = Material.RED_STAINED_GLASS
            if (key != null) {
                val definition = stateManager.findGemDefinition(key)
                if (definition != null) {
                    material = definition.material
                }
            }

            val targetMaterial = material
            val targetLocation = loc
            SchedulerUtil.regionRun(
                plugin,
                targetLocation,
                {
                    try {
                        if (!targetLocation.chunk.isLoaded) targetLocation.chunk.load()
                    } catch (e: Throwable) {
                        plugin.logger.fine("Failed to load chunk for gem block restoration: " + e.message)
                    }
                    presentationManager.renderPlacedGem(gemId, targetLocation, targetMaterial)
                },
                0L,
                -1L,
            )
        }
    }

    fun checkPlaceRedeem(placedLoc: Location?, gemKey: String?): String? {
        if (!gameplayConfig.isPlaceRedeemEnabled) return null

        val definition = stateManager.findGemDefinition(gemKey) ?: return null
        val altar = definition.altarLocation ?: return null
        if (altar.world == null || placedLoc?.world == null) return null
        if (altar.world?.name != placedLoc.world?.name) return null

        val radius = gameplayConfig.placeRedeemRadius
        if (kotlin.math.abs(placedLoc.blockX - altar.blockX) <= radius &&
            kotlin.math.abs(placedLoc.blockY - altar.blockY) <= radius &&
            kotlin.math.abs(placedLoc.blockZ - altar.blockZ) <= radius
        ) {
            return gemKey
        }
        return null
    }

    fun triggerScatterEffects(gemId: UUID?, location: Location?, playerName: String?) {
        triggerScatterEffects(gemId, location, playerName, true)
    }

    fun triggerScatterEffects(gemId: UUID?, location: Location?, playerName: String?, allowFallback: Boolean) {
        val effects = effectUtils
        if (location == null || location.world == null || effects == null) return
        val definition = stateManager.findGemDefinition(stateManager.gemUuidToKey[gemId])
        val placeholders = if (playerName == null) {
            Collections.emptyMap()
        } else {
            Collections.singletonMap("%player%", playerName)
        }
        if (definition?.onScatter != null) {
            effects.executeCommands(definition.onScatter, placeholders)
            effects.playLocalSound(location, definition.onScatter, 1.0f, 1.0f)
            effects.playParticle(location, definition.onScatter)
            return
        }
        if (allowFallback) {
            val fallback = gameplayConfig.gemScatterExecute
            effects.playLocalSound(location, fallback, 1.0f, 1.0f)
            effects.playParticle(location, fallback)
        }
    }

    fun playPlaceRedeemEffects(location: Location?) {
        if (location == null || location.world == null) return
        val loc = location.clone().add(0.5, 0.5, 0.5)
        SchedulerUtil.regionRun(
            plugin,
            loc,
            {
                val world = loc.world ?: return@regionRun
                val particleStr = gameplayConfig.placeRedeemParticle
                if (!particleStr.isNullOrEmpty()) {
                    try {
                        val particle = Particle.valueOf(particleStr.uppercase())
                        try {
                            world.spawnParticle(particle, loc, 100, 1.0, 1.0, 1.0, 0.1)
                        } catch (_: IllegalArgumentException) {
                            plugin.logger.warning(
                                "Could not spawn redeem particle $particle: requires data like BlockData.",
                            )
                        }
                    } catch (e: IllegalArgumentException) {
                        plugin.logger.warning("Invalid place-redeem particle type '$particleStr': " + e.message)
                    }
                }
                val soundStr = gameplayConfig.placeRedeemSound
                if (!soundStr.isNullOrEmpty()) {
                    try {
                        val sound = Sound.valueOf(soundStr.uppercase())
                        world.playSound(loc, sound, 1.0f, 1.0f)
                    } catch (e: IllegalArgumentException) {
                        plugin.logger.warning("Invalid place-redeem sound type '$soundStr': " + e.message)
                    }
                }
                if (gameplayConfig.isPlaceRedeemBeaconBeam) {
                    playBeaconBeamEffect(loc, gameplayConfig.placeRedeemBeaconDuration)
                }
            },
            0L,
            -1L,
        )
    }

    fun playBeaconBeamEffect(loc: Location?, durationSeconds: Int) {
        if (loc == null || loc.world == null) return
        val world = loc.world ?: return
        val worldName = world.name
        val height = loc.blockY - world.minHeight
        val durationTicks = durationSeconds * 20L
        val interval = 2L
        val taskHolder = arrayOfNulls<Any>(1)
        taskHolder[0] = SchedulerUtil.globalRun(
            plugin,
            {
                val currentWorld = Bukkit.getWorld(worldName)
                if (currentWorld == null) {
                    val task = taskHolder[0]
                    if (task != null) SchedulerUtil.cancelTask(task)
                    return@globalRun
                }
                var y = 0
                while (y < height) {
                    val particleLoc = loc.clone()
                    particleLoc.y = (currentWorld.minHeight + y).toDouble()
                    currentWorld.spawnParticle(Particle.END_ROD, particleLoc, 2, 0.1, 0.0, 0.1, 0.01)
                    y += 3
                }
                currentWorld.spawnParticle(Particle.TOTEM, loc, 5, 0.5, 0.5, 0.5, 0.1)
            },
            0L,
            interval,
        )
        SchedulerUtil.globalRun(
            plugin,
            {
                val task = taskHolder[0]
                if (task != null) SchedulerUtil.cancelTask(task)
            },
            durationTicks,
            -1L,
        )
    }

    fun checkPlayersNearRuleGems() {
        presentationManager.refreshAllPlayers()
        if (stateManager.locationToGemUuid.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            checkPlayerNearRuleGems(player)
        }
    }

    fun checkPlayerNearRuleGems(player: Player?) {
        if (player == null || stateManager.locationToGemUuid.isEmpty()) return
        SchedulerUtil.entityRun(plugin, player, { doPlayerNearCheck(player) }, 0L, -1L)
    }

    fun refreshDisplayForPlayer(player: Player?) {
        presentationManager.refreshPlayer(player)
    }

    fun removeDisplayViewer(player: Player?) {
        presentationManager.removeViewer(player)
    }

    fun resolveDisplayedGem(entity: org.bukkit.entity.Entity?): UUID? = presentationManager.resolveGemId(entity)

    fun shutdownPresentation() {
        presentationManager.shutdown()
    }

    private fun toBlockLocation(location: Location?): Location? {
        val world = location?.world ?: return null
        return Location(
            world,
            location.blockX.toDouble(),
            location.blockY.toDouble(),
            location.blockZ.toDouble(),
        )
    }

    private fun doPlayerNearCheck(player: Player?) {
        if (player == null) return
        val playerLoc = player.location
        val playerWorld = playerLoc.world ?: return
        for (blockLoc in stateManager.locationToGemUuid.keys) {
            val world = blockLoc.world
            if (world == null || world != playerWorld) continue
            val distance = playerLoc.distance(blockLoc)
            if (distance < PROXIMITY_DETECTION_RANGE) {
                val volume = (1.0 - distance / PROXIMITY_DETECTION_RANGE).toFloat()
                player.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_PLING, volume, 1.0f)
            }
        }
    }

    companion object {
        private const val MAX_VERTICAL_SEARCH = 6
        private const val MAX_RANDOM_ATTEMPTS = 12
        private const val PROXIMITY_DETECTION_RANGE = 16.0
    }

    private enum class GemTransition {
        PICKUP,
        ESCAPE,
    }
}
