package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.gui.ItemBuilder
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Consumer
import kotlin.concurrent.read
import kotlin.concurrent.write

class GemStateManager(
    @Suppress("unused") private val plugin: RuleGems,
    private val gemParser: GemDefinitionParser,
    private val languageManager: LanguageManager?,
) {
    val ruleGemKey: NamespacedKey = NamespacedKey(plugin, "rule_gem")
    val uniqueIdKey: NamespacedKey = NamespacedKey(plugin, "unique_id")
    val gemKeyKey: NamespacedKey = NamespacedKey(plugin, "gem_key")

    private val positionToGemUuid: MutableMap<BlockPosition, UUID> = ConcurrentHashMap()
    private val gemUuidToPosition: MutableMap<UUID, BlockPosition> = ConcurrentHashMap()
    private val locationViews: MutableMap<BlockPosition, WeakReference<Location>> = ConcurrentHashMap()
    val locationToGemUuid: Map<Location, UUID>
        get() = snapshotPlacedGems()
    val gemUuidToLocation: Map<UUID, Location>
        get() = getAllGemLocations()
    val gemUuidToHolder: MutableMap<UUID, UUID> = ConcurrentHashMap()
    val gemUuidToKey: MutableMap<UUID, String> = ConcurrentHashMap()
    val gemDefinitionCache: MutableMap<String, GemDefinition> = ConcurrentHashMap()
    val playerNameCache: MutableMap<UUID, String> = ConcurrentHashMap()
    private val pendingWorldGems: MutableMap<UUID, PendingPlacedGem> = ConcurrentHashMap()
    private val stateLock = ReentrantReadWriteLock()

    private class PendingPlacedGem(
        val gemId: UUID,
        val worldName: String?,
        val x: Double,
        val y: Double,
        val z: Double,
        val gemKey: String?,
    )

    fun bindPlacedGem(location: Location?, gemId: UUID?) {
        if (location == null || gemId == null) return
        val position = BlockPosition.from(location) ?: return
        locationViews[position] = WeakReference(location)
        stateLock.write {
            val previousPosition = gemUuidToPosition.put(gemId, position)
            if (previousPosition != null && previousPosition != position) {
                positionToGemUuid.remove(previousPosition, gemId)
            }
            val previousGemId = positionToGemUuid.put(position, gemId)
            if (previousGemId != null && previousGemId != gemId) {
                gemUuidToPosition.remove(previousGemId, position)
                gemUuidToHolder.remove(previousGemId)
            }
            pendingWorldGems.remove(gemId)
            gemUuidToHolder.remove(gemId)
        }
    }

    fun unbindPlacedGem(location: Location?, gemId: UUID?) {
        if (gemId == null) return
        val position = BlockPosition.from(location)
        stateLock.write {
            if (position != null) {
                positionToGemUuid.remove(position, gemId)
            } else {
                val old = gemUuidToPosition[gemId]
                if (old != null) {
                    positionToGemUuid.remove(old, gemId)
                }
            }
            if (position == null) {
                gemUuidToPosition.remove(gemId)
            } else {
                gemUuidToPosition.remove(gemId, position)
            }
            pendingWorldGems.remove(gemId)
        }
    }

    fun setGemHolder(gemId: UUID?, player: Player?) {
        if (gemId == null || player == null) return
        val playerId = player.uniqueId
        val playerName = player.name
        stateLock.write {
            val previousPosition = gemUuidToPosition.remove(gemId)
            if (previousPosition != null) {
                positionToGemUuid.remove(previousPosition, gemId)
            }
            pendingWorldGems.remove(gemId)
            gemUuidToHolder[gemId] = playerId
            playerNameCache[playerId] = playerName
        }
    }

    fun clearGemHolder(gemId: UUID?) {
        if (gemId == null) return
        stateLock.write { gemUuidToHolder.remove(gemId) }
    }

    fun setGemKey(gemId: UUID?, gemKey: String?) {
        if (gemId == null) return
        stateLock.write {
            if (gemKey.isNullOrEmpty()) {
                gemUuidToKey.remove(gemId)
            } else {
                gemUuidToKey[gemId] = gemKey
            }
        }
    }

    fun clearPlacedMappings() {
        stateLock.write {
            positionToGemUuid.clear()
            gemUuidToPosition.clear()
            locationViews.clear()
            pendingWorldGems.clear()
        }
    }

    fun clearHolderMappings() {
        stateLock.write { gemUuidToHolder.clear() }
    }

    fun clearGemKeys() {
        stateLock.write { gemUuidToKey.clear() }
    }

    fun snapshotPlacedGems(): Map<Location, UUID> = stateLock.read {
        positionToGemUuid.mapNotNull { (position, gemId) ->
            resolveLocation(position)?.let { location -> location to gemId }
        }.toMap()
    }

    fun snapshotGemKeys(): Map<UUID, String> = stateLock.read { HashMap(gemUuidToKey) }

    fun clearAll() {
        stateLock.write {
            positionToGemUuid.clear()
            gemUuidToPosition.clear()
            locationViews.clear()
            gemUuidToHolder.clear()
            gemUuidToKey.clear()
            gemDefinitionCache.clear()
            playerNameCache.clear()
            pendingWorldGems.clear()
        }
    }

    private fun configuredGemKeys(): Set<String> {
        val keys: MutableSet<String> = HashSet()
        val defs = gemParser.gemDefinitions ?: return keys
        for (def in defs) {
            if (!def.gemKey.isNullOrBlank()) {
                keys.add(def.gemKey.lowercase(ROOT_LOCALE))
            }
        }
        return keys
    }

    private fun shouldLoadPersistedGem(gemKey: String?, configuredKeys: Set<String>?): Boolean {
        if (configuredKeys.isNullOrEmpty()) return true
        if (gemKey.isNullOrBlank()) return false
        return configuredKeys.contains(gemKey.lowercase(ROOT_LOCALE))
    }

    private fun warnSkippedUnknownGem(section: String, gemId: UUID, gemKey: String?) {
        plugin.logger.warning(
            "Skipping saved $section gem $gemId with unknown configured gem_key '$gemKey'. " +
                "Re-add that key in gems/*.yml to restore the instance.",
        )
    }

    fun loadData(gemsData: FileConfiguration, randomPlaceGemFn: Consumer<UUID>) {
        val configuredKeys = configuredGemKeys()
        var placedGemsSection: ConfigurationSection? = gemsData.getConfigurationSection("placed-gems")
        if (placedGemsSection == null) {
            placedGemsSection = gemsData.getConfigurationSection("placed-gams")
        }
        if (placedGemsSection != null) {
            for (uuidStr in placedGemsSection.getKeys(false)) {
                val worldName = placedGemsSection.getString("$uuidStr.world")
                val x = placedGemsSection.getDouble("$uuidStr.x")
                val y = placedGemsSection.getDouble("$uuidStr.y")
                val z = placedGemsSection.getDouble("$uuidStr.z")
                val gemKey = placedGemsSection.getString("$uuidStr.gem_key", "default")
                val gemId = try {
                    UUID.fromString(uuidStr)
                } catch (_: Exception) {
                    continue
                }
                if (!shouldLoadPersistedGem(gemKey, configuredKeys)) {
                    warnSkippedUnknownGem("placed", gemId, gemKey)
                    continue
                }
                if (gemKey != null) {
                    gemUuidToKey[gemId] = gemKey
                }
                val world = if (worldName != null) Bukkit.getWorld(worldName) else null
                if (world == null) {
                    pendingWorldGems[gemId] = PendingPlacedGem(gemId, worldName, x, y, z, gemKey)
                    plugin.logger.info(
                        "Deferring gem $gemId in not-yet-loaded world '$worldName'; will bind on world load.",
                    )
                    continue
                }
                val loc = Location(world, x, y, z)
                val position = BlockPosition.from(loc) ?: continue
                locationViews[position] = WeakReference(loc)
                positionToGemUuid[position] = gemId
                gemUuidToPosition[gemId] = position
            }
        }

        val heldGemsSection = gemsData.getConfigurationSection("held-gems")
        if (heldGemsSection != null) {
            for (uuidStr in heldGemsSection.getKeys(false)) {
                val playerUUIDStr = heldGemsSection.getString("$uuidStr.player_uuid") ?: continue
                val gemKey = heldGemsSection.getString("$uuidStr.gem_key", "default")
                val playerUUID: UUID
                val gemId: UUID
                try {
                    playerUUID = UUID.fromString(playerUUIDStr)
                    gemId = UUID.fromString(uuidStr)
                } catch (_: Exception) {
                    continue
                }
                if (!shouldLoadPersistedGem(gemKey, configuredKeys)) {
                    warnSkippedUnknownGem("held", gemId, gemKey)
                    continue
                }
                if (gemKey != null) {
                    gemUuidToKey[gemId] = gemKey
                }
                val player = Bukkit.getPlayer(playerUUID)
                if (player != null && player.isOnline) {
                    gemUuidToHolder[gemId] = playerUUID
                    val persistedName = heldGemsSection.getString("$uuidStr.player")
                    if (!persistedName.isNullOrBlank()) {
                        playerNameCache[playerUUID] = persistedName
                    }
                } else {
                    randomPlaceGemFn.accept(gemId)
                }
            }
        }

        val namesSection = gemsData.getConfigurationSection("player_names")
        if (namesSection != null) {
            for (uuidStr in namesSection.getKeys(false)) {
                try {
                    val uid = UUID.fromString(uuidStr)
                    val name = namesSection.getString(uuidStr)
                    if (!name.isNullOrEmpty()) {
                        playerNameCache[uid] = name
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to load player name cache entry for UUID $uuidStr: " + e.message)
                }
            }
        }
    }

    fun populateSaveSnapshot(snapshot: MutableMap<String, Any?>) = stateLock.read {
        populateSaveSnapshotLocked(snapshot)
    }

    private fun populateSaveSnapshotLocked(snapshot: MutableMap<String, Any?>) {
        for ((position, gemId) in positionToGemUuid) {
            val path = "placed-gems.$gemId"
            snapshot["$path.world"] = position.worldName
            snapshot["$path.x"] = position.x
            snapshot["$path.y"] = position.y
            snapshot["$path.z"] = position.z
            snapshot["$path.gem_key"] = gemUuidToKey[gemId]
        }
        for (pending in pendingWorldGems.values) {
            val path = "placed-gems.${pending.gemId}"
            val worldName = pending.worldName
            val gemKey = pending.gemKey
            snapshot["$path.world"] = worldName
            snapshot["$path.x"] = pending.x
            snapshot["$path.y"] = pending.y
            snapshot["$path.z"] = pending.z
            snapshot["$path.gem_key"] = gemKey
        }
        for ((gemId, playerId) in gemUuidToHolder) {
            val path = "held-gems.$gemId"
            snapshot["$path.player"] = playerNameCache[playerId] ?: playerId.toString()
            snapshot["$path.player_uuid"] = playerId.toString()
            snapshot["$path.gem_key"] = gemUuidToKey[gemId]
        }
        for ((uuid, name) in playerNameCache) {
            snapshot["player_names.$uuid"] = name
        }
    }

    fun bindPendingWorldGems(world: World?): Map<UUID, Location> {
        val rebound: MutableMap<UUID, Location> = HashMap()
        if (world == null || pendingWorldGems.isEmpty()) {
            return rebound
        }
        val worldName = world.name
        val iterator = pendingWorldGems.entries.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next().value
            if (worldName != pending.worldName) {
                continue
            }
            val loc = Location(world, pending.x, pending.y, pending.z)
            val position = BlockPosition.from(loc) ?: continue
            locationViews[position] = WeakReference(loc)
            positionToGemUuid[position] = pending.gemId
            gemUuidToPosition[pending.gemId] = position
            rebound[pending.gemId] = loc
            iterator.remove()
        }
        return rebound
    }

    fun ensureConfiguredGemsPresent(randomPlaceGemFn: Consumer<UUID>) {
        val defs = gemParser.gemDefinitions ?: return
        if (defs.isEmpty()) return
        val counts: MutableMap<String, Int> = HashMap()
        for ((_, key) in gemUuidToKey) {
            val lk = key.lowercase(ROOT_LOCALE)
            counts[lk] = counts.getOrDefault(lk, 0) + 1
        }
        for (definition in defs) {
            val key = definition.gemKey ?: continue
            val lk = key.lowercase(ROOT_LOCALE)
            val have = counts.getOrDefault(lk, 0)
            val need = maxOf(1, definition.count)
            for (i in have until need) {
                val newId = UUID.randomUUID()
                gemUuidToKey[newId] = key
                randomPlaceGemFn.accept(newId)
            }
        }
    }

    fun getGemLocation(gemId: UUID?): Location? = gemUuidToPosition[gemId]?.let(::resolveLocation)

    fun getGemHolder(gemId: UUID?): Player? {
        val playerId = gemUuidToHolder[gemId] ?: return null
        return Bukkit.getPlayer(playerId)
    }

    fun getGemKey(gemId: UUID?): String? = gemUuidToKey[gemId]

    fun getAllGemUuids(): Set<UUID> = stateLock.read { HashSet(gemUuidToKey.keys) }

    fun getGemUuidByLocation(loc: Location?): UUID? = positionToGemUuid[BlockPosition.from(loc)]

    fun findLocationByGemId(gemId: UUID?): Location? = getGemLocation(gemId)

    fun getPlacedCount(): Int = positionToGemUuid.size

    fun getHeldCount(): Int = stateLock.read { gemUuidToHolder.size }

    val allGemUuidsAndKeys: Set<Map.Entry<UUID, String>>
        get() = gemUuidToKey.entries

    fun getTotalGemCount(): Int = stateLock.read { positionToGemUuid.size + gemUuidToHolder.size }

    fun getAllGemLocations(): Map<UUID, Location> = stateLock.read {
        HashMap(
            gemUuidToPosition.mapNotNull { (gemId, position) ->
            resolveLocation(position)?.let { location -> gemId to location }
            }.toMap(),
        )
    }

    private fun resolveLocation(position: BlockPosition): Location? {
        val cached = locationViews[position]?.get()
        if (cached != null) return cached
        return position.toLocation()?.also { locationViews[position] = WeakReference(it) }
    }

    fun getGemDisplayName(gemId: UUID?): String? {
        val gemKey = gemUuidToKey[gemId] ?: return null
        val definition = findGemDefinition(gemKey)
        if (definition != null && definition.displayName != null) {
            return ColorUtils.translateColorCodes(definition.displayName)
        }
        return gemKey
    }

    fun cachePlayerName(player: Player?) {
        if (player != null) {
            playerNameCache[player.uniqueId] = player.name
        }
    }

    fun getCachedPlayerName(uuid: UUID?): String {
        if (uuid == null) return "Unknown"
        val online = Bukkit.getPlayer(uuid)
        if (online != null) {
            playerNameCache[uuid] = online.name
            return online.name
        }
        val cached = playerNameCache[uuid]
        if (!cached.isNullOrEmpty()) return cached
        try {
            val offline = Bukkit.getOfflinePlayer(uuid)
            val name = offline.name
            if (!name.isNullOrEmpty()) {
                playerNameCache[uuid] = name
                return name
            }
        } catch (e: Exception) {
            plugin.logger.fine("Failed to resolve offline player name for UUID $uuid: " + e.message)
        }
        return uuid.toString().substring(0, 8)
    }

    fun removeGemItemFromInventory(player: Player?, targetId: UUID?) {
        if (player == null || targetId == null) return
        val inventory = player.inventory ?: return
        val offHandResult = stripGem(inventory.itemInOffHand, targetId)
        if (offHandResult.removedCount > 0) {
            inventory.setItemInOffHand(offHandResult.item ?: ItemStack(Material.AIR))
        }
        val contents = inventory.contents ?: return
        for (i in contents.indices) {
            val item = contents[i]
            if (!containsGem(item)) continue
            val result = stripGem(item, targetId)
            if (result.removedCount > 0) {
                inventory.setItem(i, result.item)
            }
        }
    }

    fun hasConsistentPlacementState(): Boolean = stateLock.read {
        if (gemUuidToHolder.keys.any { gemUuidToPosition.containsKey(it) || pendingWorldGems.containsKey(it) }) {
            return@read false
        }
        if (positionToGemUuid.size != gemUuidToPosition.size) {
            return@read false
        }
        positionToGemUuid.all { (position, gemId) -> gemUuidToPosition[gemId] == position } &&
            gemUuidToPosition.all { (gemId, position) -> positionToGemUuid[position] == gemId }
    }


    fun onGemDamage(event: BlockDamageEvent) {
        val block = event.block ?: return
        if (locationToGemUuid.containsKey(block.location)) {
            event.setInstaBreak(true)
        }
    }

    fun isRuleGem(item: ItemStack?): Boolean {
        if (item == null || !item.hasItemMeta()) {
            return false
        }
        val meta = item.itemMeta ?: return false
        val pdc: PersistentDataContainer = meta.persistentDataContainer
        return pdc.has(ruleGemKey, PersistentDataType.BYTE)
    }

    /**
     * 这一摞物品里是否藏着宝石 —— 一切"能否进入存储/离开玩家"的判定都应该用它。
     *
     * 与 [isRuleGem] 的分工：isRuleGem 只看这一摞本身（身份判定，用于拾取/兑换）；
     * containsGem 还会下潜进收纳袋、潜影盒这类"物品形态的容器"。
     * 没有这一层的话，把宝石塞进收纳袋再把收纳袋丢进箱子就能绕过全部容器保护。
     */
    fun containsGem(item: ItemStack?): Boolean = containsGem(item, 0)

    /** 收集这一摞物品（含嵌套容器）中所有宝石的 UUID，用于走失回收。 */
    fun collectGemIds(item: ItemStack?): List<UUID> {
        val result = ArrayList<UUID>()
        collectGemIds(item, 0, result)
        return result
    }

    /**
     * 删除物品本身或其嵌套容器里的全部宝石，同时尽量保留潜影盒/收纳袋及其中普通物品。
     *
     * 如果运行端提供了可读却不可写的未知容器 API，则安全优先：移除整个容器物品，
     * 避免留下无法清除的宝石副本。
     */
    fun stripAllGems(item: ItemStack?): GemRemoval =
        stripMatchingGems(item) { true }

    /** 从顶层或嵌套容器中删除指定 UUID 的宝石副本。 */
    fun stripGem(item: ItemStack?, targetId: UUID): GemRemoval =
        stripMatchingGems(item) { gemId -> gemId == targetId }

    /** 删除所有未登记为该玩家持有的宝石副本（包括嵌套副本）。 */
    fun stripUnownedGems(item: ItemStack?, playerId: UUID): GemRemoval =
        stripMatchingGems(item) { gemId ->
            if (gemId == null) {
                true
            } else {
                val holder = getGemHolder(gemId)
                holder == null || holder.uniqueId != playerId
            }
        }

    data class GemRemoval(
        val item: ItemStack?,
        val gemIds: List<UUID>,
        val removedCount: Int,
    )

    /** 这一摞物品本身是不是"能装下别的物品的物品"（收纳袋 / 潜影盒 ...）。 */
    fun isContainerItem(item: ItemStack?): Boolean = item != null && mayHoldItems(item)

    /**
     * 玩家身上是否真的还有这颗宝石。深检查（含收纳袋/潜影盒内部），
     * 否则被塞进收纳袋的宝石会被托管审计误判为走失，导致世界里多出一颗副本。
     */
    fun playerHoldsGem(player: Player?, gemId: UUID?): Boolean {
        if (player == null || gemId == null) return false
        val inventory = player.inventory ?: return false
        if (collectGemIds(inventory.itemInOffHand).contains(gemId)) return true
        for (item in inventory.contents ?: return false) {
            if (collectGemIds(item).contains(gemId)) return true
        }
        return false
    }

    private fun containsGem(item: ItemStack?, depth: Int): Boolean {
        if (isRuleGem(item)) return true
        if (item == null || depth >= MAX_CONTAINER_NESTING) return false
        for (nested in nestedItems(item)) {
            if (containsGem(nested, depth + 1)) return true
        }
        return false
    }

    private fun collectGemIds(item: ItemStack?, depth: Int, out: MutableList<UUID>) {
        if (item == null) return
        val id = getGemUUID(item)
        if (id != null && isRuleGem(item)) {
            out.add(id)
            return
        }
        if (depth >= MAX_CONTAINER_NESTING) return
        for (nested in nestedItems(item)) {
            collectGemIds(nested, depth + 1, out)
        }
    }

    private fun stripMatchingGems(item: ItemStack?, shouldRemove: (UUID?) -> Boolean): GemRemoval {
        val accumulator = RemovalAccumulator()
        val result = stripMatchingGems(item, 0, shouldRemove, accumulator)
        return GemRemoval(result.item, accumulator.gemIds, accumulator.removedCount)
    }

    private fun stripMatchingGems(
        item: ItemStack?,
        depth: Int,
        shouldRemove: (UUID?) -> Boolean,
        accumulator: RemovalAccumulator,
    ): StripResult {
        if (item == null) return StripResult(null, false)
        if (isRuleGem(item)) {
            val gemId = getGemUUID(item)
            if (!shouldRemove(gemId)) return StripResult(item, false)
            accumulator.removedCount++
            if (gemId != null) accumulator.gemIds.add(gemId)
            return StripResult(null, true)
        }
        if (depth >= MAX_CONTAINER_NESTING || !mayHoldItems(item) || !item.hasItemMeta()) {
            return StripResult(item, false)
        }

        val meta = item.itemMeta ?: return StripResult(item, false)
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                var changed = false
                val contents = state.inventory.contents
                for (slot in contents.indices) {
                    val nested = stripMatchingGems(contents[slot], depth + 1, shouldRemove, accumulator)
                    if (nested.changed) {
                        state.inventory.setItem(slot, nested.item)
                        changed = true
                    }
                }
                if (changed) {
                    meta.blockState = state
                    item.itemMeta = meta
                }
                return StripResult(item, changed)
            }
        }

        // BundleMeta 从 1.17 才存在；getItems/setItems 都通过反射调用以保留 1.16.5 二进制兼容。
        val bundleClass = bundleMetaClass
        val getItems = bundleGetItemsMethod
        if (bundleClass != null && getItems != null && bundleClass.isInstance(meta)) {
            val items = try {
                @Suppress("UNCHECKED_CAST")
                getItems.invoke(meta) as List<ItemStack?>
            } catch (error: ReflectiveOperationException) {
                plugin.logger.fine("Failed to read bundle contents while removing gems: " + error.message)
                return StripResult(item, false)
            }

            var changed = false
            val cleaned = ArrayList<ItemStack>()
            for (nestedItem in items) {
                val nested = stripMatchingGems(nestedItem, depth + 1, shouldRemove, accumulator)
                if (nested.changed) changed = true
                if (nested.item != null) cleaned.add(nested.item)
            }
            if (!changed) return StripResult(item, false)

            val setItems = bundleSetItemsMethod
            if (setItems == null) {
                plugin.logger.warning(
                    "Unable to rewrite bundle contents on this server version; removing the carrier to prevent a gem duplicate.",
                )
                return StripResult(null, true)
            }
            return try {
                setItems.invoke(meta, cleaned)
                item.itemMeta = meta
                StripResult(item, true)
            } catch (error: ReflectiveOperationException) {
                plugin.logger.warning(
                    "Unable to rewrite bundle contents; removing the carrier to prevent a gem duplicate: " + error.message,
                )
                StripResult(null, true)
            }
        }
        return StripResult(item, false)
    }

    private data class StripResult(val item: ItemStack?, val changed: Boolean)

    private class RemovalAccumulator {
        val gemIds: MutableList<UUID> = ArrayList()
        var removedCount: Int = 0
    }

    /** 只有可能装东西的材质才去读 meta，避免在点击热路径上对每件普通物品都克隆一次 BlockState。 */
    private fun mayHoldItems(item: ItemStack): Boolean {
        val name = item.type.name
        return name.endsWith("SHULKER_BOX") || name == "BUNDLE" || name.endsWith("_BUNDLE")
    }

    private fun nestedItems(item: ItemStack): List<ItemStack?> {
        if (!mayHoldItems(item) || !item.hasItemMeta()) return emptyList()
        val meta = item.itemMeta ?: return emptyList()
        val result = ArrayList<ItemStack?>()
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                result.addAll(state.inventory.contents.asList())
            }
        }
        // 收纳袋是 1.17 才有的 API，本插件对 1.16.5 编译，只能反射读取。
        val bundleClass = bundleMetaClass
        val getItems = bundleGetItemsMethod
        if (bundleClass != null && getItems != null && bundleClass.isInstance(meta)) {
            try {
                @Suppress("UNCHECKED_CAST")
                result.addAll(getItems.invoke(meta) as List<ItemStack?>)
            } catch (e: ReflectiveOperationException) {
                plugin.logger.fine("Failed to read bundle contents: " + e.message)
            }
        }
        return result
    }

    fun isRuleGem(block: Block?): Boolean {
        if (block == null) return false
        return locationToGemUuid.containsKey(block.location)
    }

    fun getGemUUID(item: ItemStack?): UUID? {
        if (item == null || !item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        val uuidStr = pdc.get(uniqueIdKey, PersistentDataType.STRING) ?: return null
        return try {
            UUID.fromString(uuidStr)
        } catch (_: Exception) {
            null
        }
    }

    fun getGemUUID(block: Block?): UUID? {
        if (block == null) return null
        return locationToGemUuid[block.location]
    }

    fun findGemDefinition(key: String?): GemDefinition? {
        if (key == null) return null
        val cacheKey = key.lowercase(ROOT_LOCALE)
        val cached = gemDefinitionCache[cacheKey]
        if (cached != null) return cached
        for (definition in gemParser.gemDefinitions) {
            if (definition.gemKey.equals(key, ignoreCase = true)) {
                gemDefinitionCache[cacheKey] = definition
                return definition
            }
        }
        return null
    }

    fun rebuildGemDefinitionCache() {
        gemDefinitionCache.clear()
        val defs = gemParser.gemDefinitions
        for (definition in defs) {
            val key = definition.gemKey
            if (key != null) {
                gemDefinitionCache[key.lowercase(ROOT_LOCALE)] = definition
            }
        }
    }

    fun createRuleGem(gemId: UUID): ItemStack {
        val gemKey = gemUuidToKey.getOrDefault(gemId, null)
        var ruleGem = ItemStack(Material.RED_STAINED_GLASS, 1)
        var enchantedGlint = false
        if (gemKey != null) {
            val definition = findGemDefinition(gemKey)
            if (definition != null) {
                ruleGem = ItemStack(definition.material, 1)
                enchantedGlint = definition.isEnchanted
            }
        }
        val meta: ItemMeta = ruleGem.itemMeta ?: return ruleGem

        var defaultDisplayName: String? = null
        if (languageManager != null) {
            defaultDisplayName = languageManager.getMessage("messages.gem.default_display_name")
        }
        if (defaultDisplayName == null || defaultDisplayName.startsWith("Missing message")) {
            defaultDisplayName = "&cRule Gem"
        }
        var displayName = ColorUtils.translateColorCodes(defaultDisplayName) ?: defaultDisplayName

        val lore: MutableList<String> = ArrayList()
        if (gemKey != null) {
            val definition = findGemDefinition(gemKey)
            if (definition != null && definition.displayName != null) {
                displayName = ColorUtils.translateColorCodes(definition.displayName) ?: definition.displayName
            }
            val definitionLore = definition?.lore
            if (!definitionLore.isNullOrEmpty()) {
                for (line in definitionLore) {
                    lore.add(ColorUtils.translateColorCodes(line) ?: "")
                }
            }
        }
        meta.lore = lore
        meta.setDisplayName(displayName)

        if (enchantedGlint) {
            try {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
                ItemBuilder.applyGlowEffect(meta)
            } catch (e: Throwable) {
                plugin.logger.fine("Failed to apply enchanted glint effect to gem item: " + e.message)
            }
        }

        val pdc = meta.persistentDataContainer
        pdc.set(ruleGemKey, PersistentDataType.BYTE, 1.toByte())
        pdc.set(uniqueIdKey, PersistentDataType.STRING, gemId.toString())
        if (gemKey != null) {
            pdc.set(gemKeyKey, PersistentDataType.STRING, gemKey)
        }

        ruleGem.itemMeta = meta
        return ruleGem
    }

    fun getGemMaterial(gemId: UUID?): Material {
        val key = gemUuidToKey[gemId]
        if (key != null) {
            val definition = findGemDefinition(key)
            if (definition != null && definition.material != null) {
                return definition.material
            }
        }
        return Material.RED_STAINED_GLASS
    }

    fun isSupportRequired(mat: Material?): Boolean {
        if (mat == null) return false
        val name = mat.name
        if (name.endsWith("_TORCH") || name.endsWith("_CARPET") || name.endsWith("_CANDLE")) return true
        if (name.startsWith("POTTED_")) return true
        if ("SCULK_CATALYST" == name) return true
        try {
            if (!mat.isSolid) return true
        } catch (e: Throwable) {
            plugin.logger.fine("Failed to check if material $name is solid: " + e.message)
        }
        return false
    }

    fun hasBlockSupport(loc: Location?): Boolean {
        if (loc == null || loc.world == null) return false
        val below = loc.clone().add(0.0, -1.0, 0.0)
        val block = below.block ?: return false
        val material = block.type
        return try {
            material.isSolid
        } catch (_: Throwable) {
            true
        }
    }

    fun resolveGemIdentifier(input: String?): UUID? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        try {
            val id = UUID.fromString(trimmed)
            if (gemUuidToKey.containsKey(id)) return id
        } catch (e: Exception) {
            plugin.logger.fine("Input '$trimmed' is not a valid UUID, trying other formats: " + e.message)
        }

        if (trimmed.length >= 8 && !trimmed.contains(" ")) {
            for (id in gemUuidToKey.keys) {
                if (id.toString().lowercase(Locale.getDefault()).startsWith(trimmed.lowercase(Locale.getDefault()))) {
                    return id
                }
            }
        }

        val key = resolveGemKeyByNameOrKey(trimmed) ?: return null

        var firstHeld: UUID? = null
        for ((gemId, value) in gemUuidToKey) {
            if (value.equals(key, ignoreCase = true)) {
                if (gemUuidToLocation.containsKey(gemId)) {
                    return gemId
                }
                if (firstHeld == null && gemUuidToHolder.containsKey(gemId)) {
                    firstHeld = gemId
                }
            }
        }
        return firstHeld
    }

    fun resolveGemKeyByNameOrKey(input: String?): String? {
        if (input.isNullOrEmpty()) return null
        val lowerInput = input.lowercase(ROOT_LOCALE)
        for (definition in gemParser.gemDefinitions) {
            if (definition.gemKey.equals(input, ignoreCase = true)) return definition.gemKey
            val name = definition.displayName
            val stripped = if (name != null) {
                ChatColor.stripColor(name)?.replace("§", "&")?.replace("&", "")?.lowercase(ROOT_LOCALE)
            } else {
                null
            }
            if (stripped != null && stripped.contains(lowerInput)) {
                return definition.gemKey
            }
        }
        return null
    }

    fun ensureGemKeyAssigned(gemId: UUID?) {
        if (gemId == null || gemUuidToKey.containsKey(gemId)) return
        val defs = gemParser.gemDefinitions
        if (defs.isEmpty()) return
        val key = defs[Random().nextInt(defs.size)].gemKey
        gemUuidToKey[gemId] = key
    }

    companion object {
        private val ROOT_LOCALE: Locale = Locale.ROOT

        /** 潜影盒装收纳袋这类嵌套的深度上限，防止构造出的畸形 NBT 打爆递归。 */
        private const val MAX_CONTAINER_NESTING = 4

        private val bundleMetaClass: Class<*>? = try {
            Class.forName("org.bukkit.inventory.meta.BundleMeta")
        } catch (_: ClassNotFoundException) {
            null
        }

        private val bundleGetItemsMethod: Method? = try {
            bundleMetaClass?.getMethod("getItems")
        } catch (_: ReflectiveOperationException) {
            null
        }

        private val bundleSetItemsMethod: Method? = try {
            bundleMetaClass?.getMethod("setItems", List::class.java)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
