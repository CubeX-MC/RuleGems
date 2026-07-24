package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.utils.SchedulerUtil
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the runtime representation of placed gems.
 *
 * BLOCK mode writes the configured material into the world. PROXIMITY_DISPLAY
 * keeps the world block empty and creates non-persistent display entities only
 * while at least one player is within the configured reveal/hide radius.
 */
class GemPresentationManager(
    private val plugin: RuleGems,
    private val gameplayConfig: GameplayConfig,
    private val stateManager: GemStateManager,
) {
    private val records: MutableMap<UUID, DisplayRecord> = ConcurrentHashMap()
    private val entityToGem: MutableMap<UUID, UUID> = ConcurrentHashMap()
    private val displayMarkerKey = NamespacedKey(plugin, "proximity_display")
    private val displayGemIdKey = NamespacedKey(plugin, "proximity_display_gem_id")
    private val modernBackendDisabled = AtomicBoolean(false)

    private val blockDisplayType: EntityType? = try {
        EntityType.valueOf("BLOCK_DISPLAY")
    } catch (_: IllegalArgumentException) {
        null
    }
    private val showEntityMethod: Method? = findPlayerEntityVisibilityMethod("showEntity")
    private val hideEntityMethod: Method? = findPlayerEntityVisibilityMethod("hideEntity")
    private val visibleByDefaultMethod: Method? = try {
        Entity::class.java.getMethod("setVisibleByDefault", Boolean::class.javaPrimitiveType)
    } catch (_: ReflectiveOperationException) {
        null
    }

    private val hasModernBackend: Boolean
        get() = blockDisplayType != null &&
            showEntityMethod != null &&
            hideEntityMethod != null &&
            visibleByDefaultMethod != null &&
            !modernBackendDisabled.get()

    fun synchronizePlacedGems(gems: Map<UUID, Location>?) {
        clearDisplayEntities()
        if (gems.isNullOrEmpty()) return

        val mode = gameplayConfig.gemPresentationMode
        for ((gemId, location) in gems) {
            val target = toBlockLocation(location) ?: continue
            val material = stateManager.getGemMaterial(gemId)
            SchedulerUtil.regionRun(
                plugin,
                target,
                { renderPlacedGem(gemId, target, material) },
                0L,
                -1L,
            )
        }
        if (mode == GemPresentationMode.PROXIMITY_DISPLAY) {
            val backend = if (hasModernBackend) "BlockDisplay" else "ArmorStand compatibility"
            plugin.logger.info("Gem presentation mode: proximity_display ($backend backend).")
        } else {
            plugin.logger.info("Gem presentation mode: block.")
        }
    }

    /** Must be called from the owning region for [location]. */
    fun renderPlacedGem(gemId: UUID, location: Location, material: Material) {
        val target = toBlockLocation(location) ?: return
        if (gameplayConfig.gemPresentationMode != GemPresentationMode.PROXIMITY_DISPLAY) {
            removeRecord(gemId, target)
            target.block.type = material
            return
        }

        target.block.type = Material.AIR
        val replacement = DisplayRecord(gemId, target, material)
        val previous = records.put(gemId, replacement)
        if (previous != null) {
            scheduleEntityRemoval(previous, false)
        }
    }

    /**
     * Detaches the runtime record before a delayed region cleanup is queued.
     * This prevents an old cleanup from deleting a replacement that reused the
     * same UUID and block location during scatter or relocation.
     */
    fun detachPlacedGem(gemId: UUID?, location: Location) {
        if (gemId == null) return
        val target = toBlockLocation(location) ?: return
        removeRecord(gemId, target)
    }

    /** Must be called from the owning region for [location]. */
    fun clearLocationIfUnoccupied(location: Location) {
        val target = toBlockLocation(location) ?: return
        if (!stateManager.locationToGemUuid.containsKey(target)) {
            target.block.type = Material.AIR
        }
    }

    fun refreshAllPlayers() {
        if (gameplayConfig.gemPresentationMode != GemPresentationMode.PROXIMITY_DISPLAY) return
        if (records.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            refreshPlayer(player)
        }
    }

    fun refreshPlayer(player: Player?) {
        if (player == null) return
        SchedulerUtil.entityRun(plugin, player, { refreshPlayerOnEntityThread(player) }, 0L, -1L)
    }

    fun removeViewer(player: Player?) {
        val playerId = player?.uniqueId ?: return
        for (record in records.values) {
            if (record.viewers.remove(playerId) && record.viewers.isEmpty()) {
                scheduleEntityRemoval(record, true)
            }
        }
    }

    fun resolveGemId(entity: Entity?): UUID? {
        if (entity == null) return null
        val mapped = entityToGem[entity.uniqueId]
        if (mapped != null && records.containsKey(mapped)) {
            return mapped
        }
        val pdc = entity.persistentDataContainer
        if (!pdc.has(displayMarkerKey, PersistentDataType.BYTE)) return null
        val rawId = pdc.get(displayGemIdKey, PersistentDataType.STRING) ?: return null
        return try {
            UUID.fromString(rawId).takeIf { records.containsKey(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun shutdown() {
        clearDisplayEntities()
    }

    private fun refreshPlayerOnEntityThread(player: Player) {
        if (!player.isOnline || gameplayConfig.gemPresentationMode != GemPresentationMode.PROXIMITY_DISPLAY) {
            removeViewer(player)
            return
        }

        val playerLocation = player.location
        val playerId = player.uniqueId
        val revealSquared = gameplayConfig.gemDisplayRevealRange * gameplayConfig.gemDisplayRevealRange
        val hideSquared = gameplayConfig.gemDisplayHideRange * gameplayConfig.gemDisplayHideRange
        for (record in records.values) {
            val wasViewing = record.viewers.contains(playerId)
            val threshold = if (wasViewing) hideSquared else revealSquared
            val shouldView = playerLocation.world == record.location.world &&
                playerLocation.distanceSquared(record.location) <= threshold
            if (shouldView) {
                record.viewers.add(playerId)
                requestEntitySpawn(record)
                showRecord(player, record)
            } else if (record.viewers.remove(playerId)) {
                hideRecord(player, record)
                if (record.viewers.isEmpty()) {
                    scheduleEntityRemoval(record, true)
                }
            }
        }
    }

    private fun requestEntitySpawn(record: DisplayRecord) {
        if (!record.spawnScheduled.compareAndSet(false, true)) return
        SchedulerUtil.regionRun(
            plugin,
            record.location,
            {
                try {
                    if (records[record.gemId] !== record || record.viewers.isEmpty()) return@regionRun
                    if (record.entities.isNotEmpty() && record.entities.all { it.isValid }) {
                        showToCurrentViewers(record)
                        return@regionRun
                    }
                    removeEntitiesNow(record)
                    record.entities = spawnEntities(record)
                    for (entity in record.entities) {
                        entityToGem[entity.uniqueId] = record.gemId
                    }
                    showToCurrentViewers(record)
                } finally {
                    record.spawnScheduled.set(false)
                }
            },
            0L,
            -1L,
        )
    }

    private fun spawnEntities(record: DisplayRecord): List<Entity> {
        if (hasModernBackend) {
            try {
                return spawnModernEntities(record)
            } catch (error: Throwable) {
                modernBackendDisabled.set(true)
                plugin.logger.warning(
                    "BlockDisplay backend failed; falling back to ArmorStand compatibility mode: ${error.message}",
                )
            }
        }
        return listOf(spawnArmorStand(record, true))
    }

    private fun spawnModernEntities(record: DisplayRecord): List<Entity> {
        val world = record.location.world ?: return emptyList()
        val type = blockDisplayType ?: return listOf(spawnArmorStand(record, true))
        val visual = world.spawnEntity(record.location, type)
        try {
            val setBlock = visual.javaClass.methods.firstOrNull {
                it.name == "setBlock" && it.parameterTypes.size == 1
            } ?: throw IllegalStateException("BlockDisplay#setBlock is unavailable")
            setBlock.invoke(visual, record.material.createBlockData())
            configureEntity(visual, record.gemId, true, true)
            val hitbox = spawnArmorStand(record, false)
            return listOf(visual, hitbox)
        } catch (error: Throwable) {
            visual.remove()
            throw error
        }
    }

    private fun spawnArmorStand(record: DisplayRecord, showMaterial: Boolean): ArmorStand {
        val world = record.location.world ?: throw IllegalStateException("Display world is unavailable")
        val standLocation = record.location.clone().add(0.5, if (showMaterial) -0.35 else -0.95, 0.5)
        val stand = world.spawnEntity(standLocation, EntityType.ARMOR_STAND) as ArmorStand
        stand.isVisible = false
        stand.isSmall = showMaterial
        stand.setBasePlate(false)
        stand.setArms(false)
        stand.setMarker(false)
        if (showMaterial) {
            stand.setHelmet(ItemStack(record.material))
        }
        configureEntity(stand, record.gemId, hasModernBackend, false)
        return stand
    }

    private fun configureEntity(
        entity: Entity,
        gemId: UUID,
        hiddenByDefault: Boolean,
        invulnerable: Boolean,
    ) {
        entity.setGravity(false)
        entity.isInvulnerable = invulnerable
        entity.isSilent = true
        entity.isPersistent = false
        entity.addScoreboardTag(DISPLAY_SCOREBOARD_TAG)
        entity.persistentDataContainer.set(displayMarkerKey, PersistentDataType.BYTE, 1.toByte())
        entity.persistentDataContainer.set(displayGemIdKey, PersistentDataType.STRING, gemId.toString())
        if (hiddenByDefault) {
            visibleByDefaultMethod?.invoke(entity, false)
        }
    }

    private fun showToCurrentViewers(record: DisplayRecord) {
        for (playerId in record.viewers) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            SchedulerUtil.entityRun(plugin, player, { showRecord(player, record) }, 0L, -1L)
        }
    }

    private fun showRecord(player: Player, record: DisplayRecord) {
        val method = showEntityMethod ?: return
        for (entity in record.entities) {
            try {
                method.invoke(player, plugin, entity)
            } catch (error: ReflectiveOperationException) {
                plugin.logger.fine("Failed to show gem display to ${player.name}: ${error.message}")
            }
        }
    }

    private fun hideRecord(player: Player, record: DisplayRecord) {
        val method = hideEntityMethod ?: return
        for (entity in record.entities) {
            try {
                method.invoke(player, plugin, entity)
            } catch (error: ReflectiveOperationException) {
                plugin.logger.fine("Failed to hide gem display from ${player.name}: ${error.message}")
            }
        }
    }

    private fun removeRecord(gemId: UUID, location: Location) {
        val record = records[gemId] ?: return
        if (!sameBlock(record.location, location)) return
        if (records.remove(gemId, record)) {
            scheduleEntityRemoval(record, false)
        }
    }

    private fun scheduleEntityRemoval(record: DisplayRecord, onlyIfUnused: Boolean) {
        SchedulerUtil.regionRun(
            plugin,
            record.location,
            {
                if (onlyIfUnused && records[record.gemId] === record && record.viewers.isNotEmpty()) {
                    return@regionRun
                }
                removeEntitiesNow(record)
            },
            0L,
            -1L,
        )
    }

    private fun removeEntitiesNow(record: DisplayRecord) {
        val entities = record.entities
        record.entities = emptyList()
        for (entity in entities) {
            entityToGem.remove(entity.uniqueId, record.gemId)
            if (entity.isValid) {
                entity.remove()
            }
        }
    }

    private fun clearDisplayEntities() {
        val snapshot = records.values.toList()
        records.clear()
        entityToGem.clear()
        for (record in snapshot) {
            scheduleEntityRemoval(record, false)
        }
    }

    private fun findPlayerEntityVisibilityMethod(name: String): Method? {
        return try {
            Player::class.java.getMethod(name, org.bukkit.plugin.Plugin::class.java, Entity::class.java)
        } catch (_: ReflectiveOperationException) {
            null
        }
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

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ
    }

    private class DisplayRecord(
        val gemId: UUID,
        val location: Location,
        val material: Material,
    ) {
        val viewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val spawnScheduled = AtomicBoolean(false)

        @Volatile
        var entities: List<Entity> = emptyList()
    }

    companion object {
        private const val DISPLAY_SCOREBOARD_TAG = "rulegems_proximity_display"
    }
}
