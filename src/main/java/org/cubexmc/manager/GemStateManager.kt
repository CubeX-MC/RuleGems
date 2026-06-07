package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.RuleGems
import org.cubexmc.gui.ItemBuilder
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.ColorUtils
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class GemStateManager(
    @Suppress("unused") private val plugin: RuleGems,
    private val gemParser: GemDefinitionParser,
    private val languageManager: LanguageManager?,
) {
    val ruleGemKey: NamespacedKey = NamespacedKey(plugin, "rule_gem")
    val uniqueIdKey: NamespacedKey = NamespacedKey(plugin, "unique_id")
    val gemKeyKey: NamespacedKey = NamespacedKey(plugin, "gem_key")

    val locationToGemUuid: MutableMap<Location, UUID> = ConcurrentHashMap()
    val gemUuidToLocation: MutableMap<UUID, Location> = ConcurrentHashMap()
    val gemUuidToHolder: MutableMap<UUID, Player> = ConcurrentHashMap()
    val gemUuidToKey: MutableMap<UUID, String> = ConcurrentHashMap()
    val gemDefinitionCache: MutableMap<String, GemDefinition> = ConcurrentHashMap()
    val playerNameCache: MutableMap<UUID, String> = ConcurrentHashMap()
    private val pendingWorldGems: MutableMap<UUID, PendingPlacedGem> = ConcurrentHashMap()

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
        val previousLocation = gemUuidToLocation.put(gemId, location)
        if (previousLocation != null && previousLocation != location) {
            locationToGemUuid.remove(previousLocation, gemId)
        }
        val previousGemId = locationToGemUuid.put(location, gemId)
        if (previousGemId != null && previousGemId != gemId) {
            gemUuidToLocation.remove(previousGemId, location)
        }
        gemUuidToHolder.remove(gemId)
    }

    fun unbindPlacedGem(location: Location?, gemId: UUID?) {
        if (gemId == null) return
        if (location != null) {
            locationToGemUuid.remove(location, gemId)
        } else {
            val old = gemUuidToLocation[gemId]
            if (old != null) {
                locationToGemUuid.remove(old, gemId)
            }
        }
        if (location == null) {
            gemUuidToLocation.remove(gemId)
        } else {
            gemUuidToLocation.remove(gemId, location)
        }
    }

    fun setGemHolder(gemId: UUID?, player: Player?) {
        if (gemId == null || player == null) return
        gemUuidToHolder[gemId] = player
    }

    fun clearGemHolder(gemId: UUID?) {
        if (gemId == null) return
        gemUuidToHolder.remove(gemId)
    }

    fun setGemKey(gemId: UUID?, gemKey: String?) {
        if (gemId == null) return
        if (gemKey.isNullOrEmpty()) {
            gemUuidToKey.remove(gemId)
        } else {
            gemUuidToKey[gemId] = gemKey
        }
    }

    fun clearPlacedMappings() {
        locationToGemUuid.clear()
        gemUuidToLocation.clear()
        pendingWorldGems.clear()
    }

    fun clearHolderMappings() {
        gemUuidToHolder.clear()
    }

    fun clearGemKeys() {
        gemUuidToKey.clear()
    }

    fun snapshotPlacedGems(): Map<Location, UUID> = HashMap(locationToGemUuid)

    fun clearAll() {
        locationToGemUuid.clear()
        gemUuidToLocation.clear()
        gemUuidToHolder.clear()
        gemUuidToKey.clear()
        gemDefinitionCache.clear()
        playerNameCache.clear()
        pendingWorldGems.clear()
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
                locationToGemUuid[loc] = gemId
                gemUuidToLocation[gemId] = loc
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
                    gemUuidToHolder[gemId] = player
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

    fun populateSaveSnapshot(snapshot: MutableMap<String, Any?>) {
        for ((loc, gemId) in locationToGemUuid) {
            val world = loc.world
            if (world == null) {
                plugin.logger.warning("Skipping gem save for $gemId because location/world is unavailable.")
                continue
            }
            val path = "placed-gems.$gemId"
            snapshot["$path.world"] = world.name
            snapshot["$path.x"] = loc.x
            snapshot["$path.y"] = loc.y
            snapshot["$path.z"] = loc.z
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
        for ((gemId, player) in gemUuidToHolder) {
            val path = "held-gems.$gemId"
            snapshot["$path.player"] = player.name
            snapshot["$path.player_uuid"] = player.uniqueId.toString()
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
            locationToGemUuid[loc] = pending.gemId
            gemUuidToLocation[pending.gemId] = loc
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

    fun getGemLocation(gemId: UUID?): Location? = gemUuidToLocation[gemId]

    fun getGemHolder(gemId: UUID?): Player? = gemUuidToHolder[gemId]

    fun getGemKey(gemId: UUID?): String? = gemUuidToKey[gemId]

    fun getAllGemUuids(): Set<UUID> = HashSet(gemUuidToKey.keys)

    fun getGemUuidByLocation(loc: Location?): UUID? = locationToGemUuid[loc]

    fun findLocationByGemId(gemId: UUID?): Location? = gemUuidToLocation[gemId]

    fun getPlacedCount(): Int = locationToGemUuid.size

    fun getHeldCount(): Int = gemUuidToHolder.size

    val allGemUuidsAndKeys: Set<Map.Entry<UUID, String>>
        get() = gemUuidToKey.entries

    fun getTotalGemCount(): Int = locationToGemUuid.size + gemUuidToHolder.size

    fun getAllGemLocations(): Map<UUID, Location> = HashMap(gemUuidToLocation)

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
        val offHand: ItemStack? = inventory.itemInOffHand
        if (isRuleGem(offHand)) {
            val id = getGemUUID(offHand)
            if (targetId == id) {
                inventory.setItemInOffHand(ItemStack(Material.AIR))
                return
            }
        }
        val contents = inventory.contents ?: return
        for (i in contents.indices) {
            val item = contents[i]
            if (!isRuleGem(item)) continue
            val id = getGemUUID(item)
            if (targetId == id) {
                inventory.setItem(i, ItemStack(Material.AIR))
                break
            }
        }
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
    }
}
