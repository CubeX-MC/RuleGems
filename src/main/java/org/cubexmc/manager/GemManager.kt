package org.cubexmc.manager

import com.google.common.base.Preconditions
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.cubexmc.RuleGems
import org.cubexmc.event.GemPickupEvent
import org.cubexmc.event.GemPlaceEvent
import org.cubexmc.event.GemRedeemEvent
import org.cubexmc.model.ExecuteConfig
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PowerStructure
import org.cubexmc.model.RedeemIngredient
import org.cubexmc.model.RedeemRequirementResult
import org.cubexmc.model.RedeemRecipe
import org.cubexmc.model.RedeemRequirements
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.EffectUtils
import org.cubexmc.utils.SchedulerUtil
import org.cubexmc.view.GemStatusView
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer

/**
 * GemManager - 门面类（Facade）。
 */
class GemManager(
    private val plugin: RuleGems,
    val configManager: ConfigManager,
    private val gemParser: GemDefinitionParser,
    private val gameplayConfig: GameplayConfig,
    private val effectUtils: EffectUtils,
    private val languageManager: LanguageManager,
) {
    private var historyLogger: HistoryLogger? = null

    val stateManager: GemStateManager = GemStateManager(plugin, gemParser, languageManager)
    val allowanceManager: GemAllowanceManager = GemAllowanceManager(gemParser, gameplayConfig)
    val permissionManager: GemPermissionManager = GemPermissionManager(plugin, gameplayConfig, stateManager)
    val placementManager: GemPlacementManager = GemPlacementManager(plugin, gemParser, gameplayConfig, languageManager, stateManager)
    private val scatterService: GemScatterService = GemScatterService(
        stateManager,
        placementManager,
        gemParser,
        gameplayConfig,
        effectUtils,
        languageManager,
        Runnable {
            permissionManager.resetForScatter()
            allowanceManager.clearAll()
        },
        Runnable { saveGems() },
    )

    private val saveLock = Any()

    init {
        allowanceManager.setSaveCallback(Runnable { saveGems() })
        allowanceManager.setIsToggledOffCheck { playerId, gemId -> isGemIdToggledOff(playerId, gemId) }
        allowanceManager.setGemKeyLookup { gemId -> stateManager.getGemKey(gemId) }
        permissionManager.setSaveCallback(Runnable { saveGems() })
        permissionManager.setAllowanceManager(allowanceManager)
        placementManager.setEffectUtils(effectUtils)
        placementManager.setSaveCallback(Runnable { saveGems() })

        SchedulerUtil.globalRun(plugin, { allowanceManager.flushIfDirty() }, 20L * 60, 20L * 60)
    }

    val isInventoryGrantsEnabled: Boolean
        get() = gameplayConfig.isInventoryGrantsEnabled

    fun setHistoryLogger(historyLogger: HistoryLogger?) {
        this.historyLogger = historyLogger
        permissionManager.setHistoryLogger(historyLogger)
    }

    fun isGemIdToggledOff(playerId: UUID?, gemId: UUID?): Boolean {
        if (playerId == null || gemId == null) return false
        val gemKey = stateManager.gemUuidToKey[gemId] ?: return false
        return permissionManager.isGemToggledOff(playerId, gemKey)
    }

    fun loadGems() {
        val gemsData = configManager.readGemsData()
        if (gemsData == null) {
            plugin.logger.warning("Failed to load gemsData config! Please check if the file exists.")
            return
        }

        stateManager.clearAll()
        permissionManager.clearRuntimeState()
        allowanceManager.clearAll()

        stateManager.loadData(gemsData, Consumer { gemId -> placementManager.randomPlaceGem(gemId) })
        permissionManager.loadData(gemsData)
        allowanceManager.loadData(gemsData)

        permissionManager.restoreRedeemedPermissionsForOnlinePlayers()
        for (player in Bukkit.getOnlinePlayers()) {
            permissionManager.applyPendingRevokesIfAny(player)
        }

        val placed = HashMap<String, String>()
        placed["count"] = stateManager.getPlacedCount().toString()
        languageManager.logMessage("gems_loaded", placed)
        val held = HashMap<String, String>()
        held["count"] = stateManager.getHeldCount().toString()
        languageManager.logMessage("gems_held_loaded", held)

        stateManager.rebuildGemDefinitionCache()
        placementManager.initializeEscapeTasks()
    }

    fun saveGems() {
        saveGemsInternal(true)
    }

    fun saveGemsSync() {
        saveGemsInternal(false)
    }

    private fun saveGemsInternal(asyncWhenEnabled: Boolean) {
        val snapshot: MutableMap<String, Any?> = HashMap()
        stateManager.populateSaveSnapshot(snapshot)
        permissionManager.populateSaveSnapshot(snapshot)
        allowanceManager.populateSaveSnapshot(snapshot)

        val saveTask = Runnable {
            synchronized(saveLock) {
                val gemsData = configManager.getGemsData()
                for (key in SAVE_ROOT_KEYS) {
                    gemsData.set(key, null)
                }
                for ((key, value) in snapshot) {
                    gemsData.set(key, value)
                }
                configManager.saveGemData(gemsData)
            }
        }

        if (asyncWhenEnabled && plugin.isEnabled) {
            SchedulerUtil.asyncRun(plugin, saveTask, 0L)
        } else {
            saveTask.run()
        }
    }

    fun ensureConfiguredGemsPresent() {
        stateManager.ensureConfiguredGemsPresent(Consumer { gemId -> placementManager.randomPlaceGem(gemId) })
    }

    fun initializePlacedGemBlocks() {
        placementManager.initializePlacedGemBlocks()
    }

    fun handleWorldLoad(world: World?) {
        if (world == null) return
        val rebound = stateManager.bindPendingWorldGems(world)
        if (rebound.isEmpty()) return
        placementManager.restoreGemBlocks(rebound)
        for (gemId in rebound.keys) {
            placementManager.scheduleEscape(gemId)
        }
        plugin.logger.info("Bound " + rebound.size + " deferred gem(s) in world '" + world.name + "'.")
        saveGems()
    }

    fun handleBlockDamage(event: BlockDamageEvent) {
        stateManager.onGemDamage(event)
    }

    /**
     * 让"已放置的宝石方块"无视领地/保护插件在 PlayerInteract 层的额外保护。
     *
     * 背景：BlockPlace/BlockBreak 的绕过已由 [handleGemBlockPlace] / [handleGemBlockBreak] 处理。
     * 但 Residence、Lands 等保护插件对"音符盒、按钮、拉杆、唱片机、容器"等**有特殊交互逻辑的方块**，
     * 是在 PlayerInteractEvent（而非 BlockPlace/BlockBreak）上做保护的：玩家无权限时它们会取消该事件
     * 并提示"无权使用此方块"。如果某个宝石的材质恰好是这类方块，它在领地内就会被误伤。
     *
     * 关键：**左键破坏**音符盒等可交互方块时，会先触发 LEFT_CLICK_BLOCK 的 PlayerInteractEvent。
     * 一旦保护插件取消它，挖掘起手就被打断，BlockBreakEvent 根本不会触发，玩家因此"拿不走"宝石。
     * 仅放行 `useInteractedBlock` 不够：挖掘是否进行取决于 `event.isCancelled()`，而保护插件的
     * `setCancelled(true)` 同时把 `useItemInHand` 也设成 DENY，所以必须两个结果都放行。
     *
     * - 左键（LEFT_CLICK_BLOCK，挖掘起手）：两个结果都强制 ALLOW，确保挖掘照常进行 → 进入
     *   handleGemBlockBreak 完成拾取。左键不会放置/兑换，放行 useItemInHand 没有副作用。
     * - 右键（RIGHT_CLICK_BLOCK，与宝石方块交互）：只放行 useInteractedBlock。useItemInHand 留给
     *   GemConsumeListener（长按兑换，HIGH 优先级）管理，强行放行会导致兑换长按期间误放置手中宝石。
     */
    fun handleGemBlockInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!stateManager.locationToGemUuid.containsKey(block.location)) return

        if (event.useInteractedBlock() == Event.Result.DENY) {
            event.setUseInteractedBlock(Event.Result.ALLOW)
        }
        if (event.action == Action.LEFT_CLICK_BLOCK && event.useItemInHand() == Event.Result.DENY) {
            event.setUseItemInHand(Event.Result.ALLOW)
        }
    }

    fun handleGemBlockPlace(placer: Player, inHand: ItemStack?, block: Block, event: BlockPlaceEvent) {
        if (!stateManager.isRuleGem(inHand)) return

        var gemId = stateManager.getGemUUID(inHand)
        if (gemId == null) gemId = UUID.randomUUID()

        val placedLoc = block.location
        val currentGemKey = stateManager.getGemKey(gemId)
        val matchedDef = findMatchingAltarGem(placedLoc, currentGemKey)
        if (matchedDef != null) {
            event.isCancelled = true
            handlePlaceRedeem(placer, gemId, placedLoc, block, matchedDef)
            return
        }

        val gemKeyForEvent = stateManager.getGemKey(gemId) ?: return
        val placeEvent = GemPlaceEvent(placer, gemId, gemKeyForEvent, placedLoc)
        Bukkit.getPluginManager().callEvent(placeEvent)
        if (placeEvent.isCancelled) {
            event.isCancelled = true
            return
        }

        event.isCancelled = false
        stateManager.clearGemHolder(gemId)
        stateManager.bindPlacedGem(placedLoc, gemId)

        val logger = historyLogger
        if (logger != null) {
            val gemKey = stateManager.getGemKey(gemId)
            val locationStr = String.format(
                "(%d, %d, %d) %s",
                placedLoc.blockX,
                placedLoc.blockY,
                placedLoc.blockZ,
                placedLoc.world?.name ?: "unknown",
            )
            logger.logGemPlace(placer, gemKey ?: gemId.toString(), locationStr)
        }

        if (placer != null) {
            val finalGemId = gemId
            SchedulerUtil.entityRun(
                plugin,
                placer,
                {
                    stateManager.removeGemItemFromInventory(placer, finalGemId)
                    try {
                        placer.updateInventory()
                    } catch (e: Throwable) {
                        plugin.logger.fine("Failed to update placer inventory: " + e.message)
                    }
                },
                1L,
                -1L,
            )
        }
    }

    fun handleGemBlockBreak(player: Player, block: Block, event: BlockBreakEvent) {
        if (!stateManager.locationToGemUuid.containsKey(block.location)) return

        event.isCancelled = false
        event.isDropItems = false
        try {
            event.expToDrop = 0
        } catch (e: Throwable) {
            plugin.logger.fine("Failed to set exp drop to zero: " + e.message)
        }

        val gemId = stateManager.locationToGemUuid[block.location] ?: return
        val inventory = player.inventory
        if (inventory.firstEmpty() == -1) {
            languageManager.logMessage("inventory_full")
            event.isCancelled = true
            return
        }

        val pickupKey = stateManager.getGemKey(gemId) ?: return
        val pickupEvent = GemPickupEvent(player, gemId, pickupKey, block.location)
        Bukkit.getPluginManager().callEvent(pickupEvent)
        if (pickupEvent.isCancelled) {
            event.isCancelled = true
            return
        }

        val gemItem = stateManager.createRuleGem(gemId)
        inventory.addItem(gemItem)
        stateManager.setGemHolder(gemId, player)
        placementManager.cancelEscape(gemId)
        placementManager.unplaceRuleGem(block.location, gemId)
        permissionManager.handleInventoryOwnershipOnPickup(player, gemId)

        val definition = stateManager.findGemDefinition(stateManager.getGemKey(gemId))
        val onPickup = definition?.onPickup
        if (onPickup != null) {
            effectUtils.executeCommands(onPickup, Collections.singletonMap("%player%", player.name))
            effectUtils.playLocalSound(player.location, onPickup, 1.0f, 1.0f)
            effectUtils.playParticle(player.location, onPickup)
        }
    }

    fun handlePlayerQuit(player: Player) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        for (item in player.inventory.contents) {
            if (stateManager.isRuleGem(item)) {
                val gemId = stateManager.getGemUUID(item)
                player.inventory.remove(item)
                stateManager.clearGemHolder(gemId)
                placementManager.placeRuleGem(player.location, gemId)
            }
        }
    }

    fun handleGemDrop(player: Player, loc: Location, droppedItemEntity: org.bukkit.entity.Item, item: ItemStack?) {
        if (!stateManager.isRuleGem(item)) return
        val gemId = stateManager.getGemUUID(item)
        droppedItemEntity.remove()
        stateManager.clearGemHolder(gemId)
        placementManager.triggerScatterEffects(gemId, loc, player.name)
        placementManager.placeRuleGem(loc, gemId)
    }

    fun handlePlayerDeathDrops(player: Player, deathLoc: Location, drops: MutableList<ItemStack>) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        val iterator = drops.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (stateManager.isRuleGem(item)) {
                val gemId = stateManager.getGemUUID(item)
                iterator.remove()
                stateManager.clearGemHolder(gemId)
                placementManager.triggerScatterEffects(gemId, deathLoc, player.name)
                placementManager.placeRuleGem(deathLoc, gemId)
            }
        }
    }

    fun handlePlayerJoin(player: Player) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        for (item in player.inventory.contents) {
            if (stateManager.isRuleGem(item)) {
                val gemId = stateManager.getGemUUID(item)
                if (!stateManager.gemUuidToHolder.containsKey(gemId)) {
                    player.inventory.remove(item)
                }
            }
        }
        permissionManager.restoreRedeemedPermissions(player)
        permissionManager.applyPendingRevokesIfAny(player)
    }

    fun scatterGems() {
        scatterService.scatterGems()
    }

    fun redeemGemInHand(player: Player?): Boolean {
        if (player == null) return false
        stateManager.cachePlayerName(player)
        if (!gameplayConfig.isRedeemEnabled) {
            languageManager.sendMessage(player, "command.redeem.disabled")
            return true
        }
        val inHand = player.inventory.itemInMainHand
        if (!stateManager.isRuleGem(inHand)) return false
        val matchedGemId = stateManager.getGemUUID(inHand) ?: return false

        var targetKey = stateManager.getGemKey(matchedGemId)
        if (targetKey.isNullOrEmpty()) {
            stateManager.ensureGemKeyAssigned(matchedGemId)
            targetKey = stateManager.getGemKey(matchedGemId)
            if (targetKey.isNullOrEmpty()) return false
        }

        val alreadyRedeemed = permissionManager.playerUuidToRedeemedKeys[player.uniqueId]
        if (alreadyRedeemed != null && permissionManager.conflictsWithSelected(targetKey, alreadyRedeemed)) {
            languageManager.sendMessage(player, "command.redeem.conflict")
            effectUtils.playLocalSound(player.location, "ENTITY_VILLAGER_NO", 1.0f, 1.0f)
            return true
        }

        val definition = stateManager.findGemDefinition(targetKey)
        val requirementResult = evaluateRedeemRequirements(player, definition, matchedGemId, GemRedeemEvent.RedeemContext.HAND)
        if (!requirementResult.isAllowed) {
            sendRedeemRequirementFailure(player, requirementResult)
            return false
        }

        val redeemEvent = GemRedeemEvent(player, matchedGemId, targetKey, GemRedeemEvent.RedeemContext.HAND)
        Bukkit.getPluginManager().callEvent(redeemEvent)
        if (redeemEvent.isCancelled) return true

        val previousOwnerName = processRedeemCore(player, matchedGemId, targetKey, definition)

        stateManager.removeGemItemFromInventory(player, matchedGemId)
        stateManager.gemUuidToHolder.remove(matchedGemId)
        placementManager.randomPlaceGem(matchedGemId)
        consumeRequirementGems(player, requirementResult)

        historyLogger?.logGemRedeem(
            player,
            targetKey,
            definition?.displayName,
            definition?.permissions,
            definition?.vaultGroup,
            previousOwnerName,
        )

        broadcastRedeemTitle(player, definition, targetKey)
        return true
    }

    fun redeemAll(player: Player): Boolean {
        stateManager.cachePlayerName(player)
        if (!gameplayConfig.isFullSetGrantsAllEnabled) {
            languageManager.sendMessage(player, "command.redeemall.disabled")
            return true
        }
        val definitions = gemParser.gemDefinitions
        if (definitions.isEmpty()) return false

        val keyToGemId: MutableMap<String, UUID> = HashMap()
        for (item in player.inventory.contents) {
            if (!stateManager.isRuleGem(item)) continue
            val id = stateManager.getGemUUID(item)
            val key = stateManager.getGemKey(id)
            if (key != null && !keyToGemId.containsKey(key.lowercase(Locale.getDefault()))) {
                if (id != null) keyToGemId[key.lowercase(Locale.getDefault())] = id
            }
        }
        for (definition in definitions) {
            if (!keyToGemId.containsKey(definition.gemKey.lowercase(Locale.getDefault()))) return false
        }
        for (definition in definitions) {
            val gid = keyToGemId[definition.gemKey.lowercase(Locale.ROOT)] ?: return false
            val requirementResult = evaluateRedeemRequirements(player, definition, gid, GemRedeemEvent.RedeemContext.FULL_SET)
            if (!requirementResult.isAllowed) {
                sendRedeemRequirementFailure(player, requirementResult)
                return true
            }
        }

        for (definition in definitions) {
            val normalizedKey = definition.gemKey.lowercase(Locale.ROOT)
            val gid = keyToGemId[normalizedKey] ?: return true
            val redeemEvent = GemRedeemEvent(player, gid, definition.gemKey, GemRedeemEvent.RedeemContext.FULL_SET)
            Bukkit.getPluginManager().callEvent(redeemEvent)
            if (redeemEvent.isCancelled) return true
        }

        val previousFull = permissionManager.fullSetOwner
        permissionManager.fullSetOwner = player.uniqueId
        for (definition in definitions) {
            val normalizedKey = definition.gemKey.lowercase(Locale.ROOT)
            val gid = keyToGemId[normalizedKey]
            permissionManager.markGemRedeemed(player, definition.gemKey)
            if (gid != null) {
                val old = permissionManager.gemIdToRedeemer.put(gid, player.uniqueId)
                if (old != null && old != player.uniqueId) {
                    permissionManager.decrementOwnerKeyCount(old, normalizedKey, definition)
                }
                permissionManager.incrementOwnerKeyCount(player.uniqueId, normalizedKey, definition)
                applyRedeemRewards(player, definition)
                allowanceManager.reassignRedeemInstanceAllowance(gid, player.uniqueId, definition, true)
                stateManager.removeGemItemFromInventory(player, gid)
                stateManager.gemUuidToHolder.remove(gid)
                placementManager.randomPlaceGem(gid)
            }
        }

        revokePreviousFullSetOwner(previousFull, definitions)

        val logger = historyLogger
        if (logger != null) {
            val allPerms = ArrayList<String>()
            for (definition in definitions) {
                allPerms.addAll(definition.permissions)
            }
            logger.logFullSetRedeem(
                player,
                definitions.size,
                allPerms,
                if (previousFull != null && previousFull != player.uniqueId) {
                    stateManager.getCachedPlayerName(previousFull)
                } else {
                    null
                },
            )
        }

        broadcastRedeemAllTitle(player, definitions)
        applyRedeemAllPower(player, definitions)
        return true
    }

    private fun processRedeemCore(player: Player, gemId: UUID, targetKey: String, definition: GemDefinition?): String? {
        permissionManager.markGemRedeemed(player, targetKey)
        applyRedeemRewards(player, definition)

        val normalizedKey = targetKey.lowercase(Locale.ROOT)
        val old = permissionManager.gemIdToRedeemer.put(gemId, player.uniqueId)
        var previousOwnerName: String? = null
        if (old != null && old != player.uniqueId) {
            permissionManager.decrementOwnerKeyCount(old, normalizedKey, definition)
            val oldPlayer = Bukkit.getPlayer(old)
            if (oldPlayer != null && oldPlayer.isOnline) previousOwnerName = oldPlayer.name
        }
        permissionManager.incrementOwnerKeyCount(player.uniqueId, normalizedKey, definition)
        allowanceManager.reassignRedeemInstanceAllowance(gemId, player.uniqueId, definition, true)
        return previousOwnerName
    }

    private fun findMatchingAltarGem(loc: Location?, gemKey: String?): GemDefinition? {
        if (!gameplayConfig.isPlaceRedeemEnabled || loc == null || gemKey == null) return null
        val definition = stateManager.findGemDefinition(gemKey) ?: return null
        val altar = definition.altarLocation ?: return null
        if (altar.world == null || loc.world == null) return null
        if (altar.world != loc.world) return null
        return if (altar.distance(loc) <= gameplayConfig.placeRedeemRadius) definition else null
    }

    private enum class PlaceRedeemResult {
        SUCCESS,
        REJECTED,
        CANCELLED_BY_EVENT,
        INVALID,
    }

    private fun handlePlaceRedeem(
        player: Player?,
        gemId: UUID?,
        placedLoc: Location,
        block: Block,
        definition: GemDefinition?,
    ): PlaceRedeemResult {
        if (player == null || gemId == null || definition == null) return PlaceRedeemResult.INVALID
        val targetKey = definition.gemKey

        val alreadyRedeemed = permissionManager.playerUuidToRedeemedKeys[player.uniqueId]
        if (alreadyRedeemed != null && permissionManager.conflictsWithSelected(targetKey, alreadyRedeemed)) {
            languageManager.sendMessage(player, "command.redeem.conflict")
            effectUtils.playLocalSound(player.location, "ENTITY_VILLAGER_NO", 1.0f, 1.0f)
            return PlaceRedeemResult.REJECTED
        }

        val requirementResult = evaluateRedeemRequirements(player, definition, gemId, GemRedeemEvent.RedeemContext.ALTAR)
        if (!requirementResult.isAllowed) {
            sendRedeemRequirementFailure(player, requirementResult)
            return PlaceRedeemResult.REJECTED
        }

        val redeemEvent = GemRedeemEvent(player, gemId, targetKey, GemRedeemEvent.RedeemContext.ALTAR)
        Bukkit.getPluginManager().callEvent(redeemEvent)
        if (redeemEvent.isCancelled) return PlaceRedeemResult.CANCELLED_BY_EVENT

        placementManager.playPlaceRedeemEffects(placedLoc)
        val previousOwnerName = processRedeemCore(player, gemId, targetKey, definition)

        historyLogger?.logGemRedeem(
            player,
            targetKey,
            definition.displayName,
            definition.permissions,
            definition.vaultGroup,
            previousOwnerName,
        )

        val placeholders = HashMap<String, String>()
        placeholders["gem_name"] = definition.displayName ?: ""
        placeholders["gem_key"] = targetKey
        placeholders["player"] = player.name
        languageManager.sendMessage(player, "place_redeem.success", placeholders)
        for (online in Bukkit.getOnlinePlayers()) {
            if (online != player) languageManager.sendMessage(online, "place_redeem.broadcast", placeholders)
        }

        stateManager.gemUuidToHolder.remove(gemId)
        SchedulerUtil.regionRun(plugin, placedLoc, { block.type = Material.AIR }, 1L, -1L)
        placementManager.randomPlaceGem(gemId)
        consumeRequirementGems(player, requirementResult)

        SchedulerUtil.entityRun(
            plugin,
            player,
            {
                stateManager.removeGemItemFromInventory(player, gemId)
                try {
                    player.updateInventory()
                } catch (e: Throwable) {
                    plugin.logger.fine("Failed to update player inventory: " + e.message)
                }
            },
            1L,
            -1L,
        )
        return PlaceRedeemResult.SUCCESS
    }

    private fun evaluateRedeemRequirements(
        player: Player?,
        definition: GemDefinition?,
        targetGemId: UUID?,
        context: GemRedeemEvent.RedeemContext,
    ): RedeemRequirementResult {
        if (player == null || definition == null) return RedeemRequirementResult.ALLOWED
        val requirements = definition.redeemRequirements
        if (!requirements.hasRequirements()) return RedeemRequirementResult.ALLOWED
        if (context == GemRedeemEvent.RedeemContext.FULL_SET) {
            if (requirements.isAllowRedeemAll) return RedeemRequirementResult.ALLOWED
            return deniedRequirement(requirements, "command.redeem.requirements_redeemall_blocked", "gem", definition.gemKey)
        }

        val heldGemIds = collectHeldGemIds(player, targetGemId)
        val redeemedCounts = normalizedRedeemedCounts(player)
        var lastDenied: RedeemRequirementResult? = null

        for (recipe in requirements.recipes) {
            val result = evaluateRedeemRecipe(requirements, recipe, heldGemIds, redeemedCounts, targetGemId)
            if (result.isAllowed) return result
            lastDenied = result
        }
        return lastDenied ?: deniedRequirement(requirements, "command.redeem.requirements_missing_held", "gem", definition.gemKey)
    }

    private fun evaluateRedeemRecipe(
        requirements: RedeemRequirements,
        recipe: RedeemRecipe,
        heldGemIds: Map<String, List<UUID>>,
        redeemedCounts: Map<String, Int>,
        targetGemId: UUID?,
    ): RedeemRequirementResult {
        val usedGemIds: MutableSet<UUID> = HashSet()
        for (ingredient in recipe.requiresHeld) {
            if (!reserveHeldIngredient(heldGemIds, usedGemIds, ingredient)) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_held", ingredientPlaceholders(ingredient))
            }
        }
        for (ingredient in recipe.requiresRedeemed) {
            val owned = redeemedCounts.getOrDefault(normalizeKey(ingredient.gemKey), 0)
            if (owned < ingredient.amount) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_redeemed", ingredientPlaceholders(ingredient))
            }
        }
        if (recipe.requiresAny.isNotEmpty()) {
            var matched = false
            for (candidate in recipe.requiresAny) {
                val key = normalizeKey(candidate)
                if (heldGemIds.containsKey(key) || redeemedCounts.getOrDefault(key, 0) > 0) {
                    matched = true
                    break
                }
            }
            if (!matched) {
                return deniedRequirement(
                    requirements,
                    "command.redeem.requirements_missing_any",
                    "gem",
                    recipe.requiresAny.joinToString(", "),
                )
            }
        }
        if (recipe.requiresCount > 0 && recipe.requiresCountFrom.isNotEmpty()) {
            var matched = 0
            for (candidate in recipe.requiresCountFrom) {
                val key = normalizeKey(candidate)
                if (heldGemIds.containsKey(key) || redeemedCounts.getOrDefault(key, 0) > 0) matched++
            }
            if (matched < recipe.requiresCount) {
                val placeholders = HashMap<String, String>()
                placeholders["count"] = recipe.requiresCount.toString()
                placeholders["gems"] = recipe.requiresCountFrom.joinToString(", ")
                return deniedRequirement(requirements, "command.redeem.requirements_missing_count", placeholders)
            }
        }

        val consumedGemIds = ArrayList<UUID>()
        for (ingredient in recipe.consumes) {
            val selected = reserveIngredient(heldGemIds, usedGemIds, ingredient)
            if (selected.size < ingredient.amount) {
                return deniedRequirement(requirements, "command.redeem.requirements_missing_consumed", ingredientPlaceholders(ingredient))
            }
            for (consumedGemId in selected) {
                if (consumedGemId != targetGemId) consumedGemIds.add(consumedGemId)
            }
        }
        return RedeemRequirementResult.allowed(consumedGemIds, recipe)
    }

    private fun reserveHeldIngredient(
        heldGemIds: Map<String, List<UUID>>,
        usedGemIds: MutableSet<UUID>,
        ingredient: RedeemIngredient,
    ): Boolean = reserveIngredient(heldGemIds, usedGemIds, ingredient).size == ingredient.amount

    private fun reserveIngredient(
        heldGemIds: Map<String, List<UUID>>,
        usedGemIds: MutableSet<UUID>,
        ingredient: RedeemIngredient,
    ): List<UUID> {
        val ids = heldGemIds.getOrDefault(normalizeKey(ingredient.gemKey), emptyList())
        val selected = ArrayList<UUID>()
        for (id in ids) {
            if (usedGemIds.contains(id)) continue
            selected.add(id)
            usedGemIds.add(id)
            if (selected.size >= ingredient.amount) break
        }
        return selected
    }

    private fun ingredientPlaceholders(ingredient: RedeemIngredient): Map<String, String> {
        val placeholders = HashMap<String, String>()
        placeholders["gem"] = ingredient.gemKey
        placeholders["amount"] = ingredient.amount.toString()
        return placeholders
    }

    private fun deniedRequirement(
        requirements: RedeemRequirements,
        fallbackKey: String,
        placeholderKey: String,
        placeholderValue: String,
    ): RedeemRequirementResult {
        val placeholders = HashMap<String, String>()
        placeholders[placeholderKey] = placeholderValue
        return deniedRequirement(requirements, fallbackKey, placeholders)
    }

    private fun deniedRequirement(
        requirements: RedeemRequirements,
        fallbackKey: String,
        placeholders: Map<String, String>,
    ): RedeemRequirementResult {
        val custom = requirements.failureMessage
        return if (!custom.isNullOrBlank()) {
            RedeemRequirementResult.denied(custom, false, placeholders)
        } else {
            RedeemRequirementResult.denied(fallbackKey, true, placeholders)
        }
    }

    private fun sendRedeemRequirementFailure(player: Player?, result: RedeemRequirementResult?) {
        if (player == null || result == null || result.isAllowed) return
        val message = result.message ?: return
        if (result.isMessageLanguageKey) {
            languageManager.sendMessage(player, message, result.placeholders)
        } else {
            val placeholders = HashMap(result.placeholders)
            placeholders.putIfAbsent("prefix", languageManager.getMessage("prefix"))
            player.sendMessage(ColorUtils.translateColorCodes(languageManager.formatText(message, placeholders)) ?: "")
        }
    }

    private fun consumeRequirementGems(player: Player?, result: RedeemRequirementResult?) {
        if (player == null || result == null || result.consumedGemIds.isEmpty()) return
        val uniqueConsumed: Set<UUID> = LinkedHashSet(result.consumedGemIds)
        for (consumedGemId in uniqueConsumed) {
            stateManager.removeGemItemFromInventory(player, consumedGemId)
            stateManager.gemUuidToHolder.remove(consumedGemId)
            placementManager.randomPlaceGem(consumedGemId)
        }
        recalculateGrants(player)
    }

    private fun collectHeldGemIds(player: Player?, targetGemId: UUID?): Map<String, List<UUID>> {
        val result: MutableMap<String, MutableList<UUID>> = LinkedHashMap()
        val seen: MutableSet<UUID> = HashSet()
        if (player == null) {
            addHeldGemId(result, seen, targetGemId)
            return result
        }
        collectHeldGemIdsFromItems(result, seen, player.inventory.contents)
        collectHeldGemIdsFromItems(result, seen, arrayOf(player.inventory.itemInOffHand))
        addHeldGemId(result, seen, targetGemId)
        return result
    }

    private fun collectHeldGemIdsFromItems(
        result: MutableMap<String, MutableList<UUID>>,
        seen: MutableSet<UUID>,
        items: Array<ItemStack?>?,
    ) {
        if (items == null) return
        for (item in items) {
            if (!stateManager.isRuleGem(item)) continue
            val id = stateManager.getGemUUID(item)
            addHeldGemId(result, seen, id)
        }
    }

    private fun addHeldGemId(result: MutableMap<String, MutableList<UUID>>, seen: MutableSet<UUID>, id: UUID?) {
        if (id == null || !seen.add(id)) return
        val key = stateManager.getGemKey(id)
        if (key.isNullOrEmpty()) return
        result.computeIfAbsent(normalizeKey(key)) { ArrayList() }.add(id)
    }

    private fun normalizedRedeemedCounts(player: Player?): Map<String, Int> {
        val result: MutableMap<String, Int> = HashMap()
        if (player == null) return result
        val ownedCounts = permissionManager.ownerKeyCount[player.uniqueId]
        if (ownedCounts != null) {
            for ((key, value) in ownedCounts) {
                if (value > 0) result[normalizeKey(key)] = value
            }
        }
        val redeemed = permissionManager.playerUuidToRedeemedKeys[player.uniqueId]
        if (redeemed != null) {
            for (key in redeemed) {
                result.putIfAbsent(normalizeKey(key), 1)
            }
        }
        return result
    }

    private fun normalizeKey(key: String?): String = key?.lowercase(Locale.ROOT) ?: ""

    private fun applyRedeemRewards(player: Player?, definition: GemDefinition?) {
        if (player == null || definition == null) return
        val onRedeem = definition.onRedeem
        if (onRedeem != null) {
            val placeholders = mapOf("%player%" to player.name)
            effectUtils.executeCommands(onRedeem, placeholders)
            effectUtils.playLocalSound(player.location, onRedeem, 1.0f, 1.0f)
            effectUtils.playParticle(player.location, onRedeem)
        }
    }

    private fun broadcastRedeemTitle(player: Player, definition: GemDefinition?, targetKey: String) {
        if (!gameplayConfig.isBroadcastRedeemTitle) return
        val placeholders = HashMap<String, String>()
        placeholders["player"] = player.name
        placeholders["gem"] = definition?.displayName ?: targetKey
        val title = definition?.redeemTitle
        for (online in Bukkit.getOnlinePlayers()) {
            if (!title.isNullOrEmpty()) {
                sendTitle(online, title, placeholders)
            } else {
                languageManager.showTitle(online, "gems_scattered", Collections.singletonMap("count", "1"))
            }
        }
    }

    private fun broadcastRedeemAllTitle(player: Player, definitions: List<GemDefinition>) {
        val broadcast = gameplayConfig.redeemAllBroadcastOverride ?: gameplayConfig.isBroadcastRedeemTitle
        if (!broadcast) return
        val title = gameplayConfig.redeemAllTitle
        val placeholders = HashMap<String, String>()
        placeholders["player"] = player.name
        for (online in Bukkit.getOnlinePlayers()) {
            if (!title.isNullOrEmpty()) {
                sendTitle(online, title, placeholders)
            } else {
                languageManager.showTitle(online, "gems_recollected", placeholders)
            }
        }
    }

    private fun sendTitle(player: Player, title: List<String>?, placeholders: Map<String, String>) {
        if (title.isNullOrEmpty()) return
        if (title.size == 1) {
            player.sendTitle(
                ColorUtils.translateColorCodes(languageManager.formatText(title[0], placeholders)),
                null,
                10,
                70,
                20,
            )
        } else {
            val line1 = languageManager.formatText(title[0], placeholders)
            val line2 = languageManager.formatText(title[1], placeholders)
            player.sendTitle(ColorUtils.translateColorCodes(line1), ColorUtils.translateColorCodes(line2), 10, 70, 20)
        }
    }

    private fun revokePreviousFullSetOwner(previousFull: UUID?, definitions: List<GemDefinition>) {
        if (previousFull == null || previousFull == permissionManager.fullSetOwner) return
        val previousPlayer = Bukkit.getPlayer(previousFull)
        val psm = try {
            plugin.powerStructureManager
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
        if (previousPlayer != null && previousPlayer.isOnline) {
            for (definition in definitions) {
                if (psm != null) {
                    psm.removeStructure(previousPlayer, definition.powerStructure, "gem_redeem", definition.gemKey)
                } else {
                    permissionManager.revokeNodesAll(previousPlayer, definition.permissions)
                }
                val vaultGroup = definition.vaultGroup
                if (!vaultGroup.isNullOrEmpty()) {
                    plugin.permissionProvider?.removeGroup(previousPlayer, vaultGroup)
                }
            }
            previousPlayer.recalculatePermissions()
        } else {
            val allPerms: MutableSet<String> = HashSet()
            val allGroups: MutableSet<String> = HashSet()
            for (definition in definitions) {
                allPerms.addAll(definition.permissions)
                val vaultGroup = definition.vaultGroup
                if (!vaultGroup.isNullOrEmpty()) allGroups.add(vaultGroup)
            }
            permissionManager.queueOfflineRevokes(previousFull, allPerms, allGroups)
        }
    }

    private fun applyRedeemAllPower(player: Player, definitions: List<GemDefinition>) {
        val redeemAllPower = gameplayConfig.redeemAllPowerStructure ?: return
        if (!redeemAllPower.hasAnyContent()) return
        val psm = try {
            plugin.powerStructureManager
        } catch (_: UninitializedPropertyAccessException) {
            null
        } catch (_: NullPointerException) {
            null
        }
        if (psm != null) {
            psm.applyStructure(player, redeemAllPower, "gem_redeem_all", "full_set", false)
        } else {
            permissionManager.grantRedeemPermissions(player, redeemAllPower.permissions)
        }
        val extraAllows = redeemAllPower.allowedCommands
        if (extraAllows.isNotEmpty()) {
            val pseudoDef = GemDefinition.Builder("ALL")
                .material(Material.BEDROCK)
                .displayName("ALL")
                .powerStructure(redeemAllPower)
                .build()
            allowanceManager.grantGlobalAllowedCommands(player, pseudoDef)
        }
        try {
            val sound = org.bukkit.Sound.valueOf(gameplayConfig.redeemAllSound)
            effectUtils.playGlobalSound(ExecuteConfig(emptyList(), sound.name, null), 1.0f, 1.0f)
        } catch (e: Exception) {
            plugin.logger.fine("Failed to play redeem-all sound: " + e.message)
        }
    }

    fun forcePlaceGem(gemId: UUID?, target: Location?) {
        if (gemId == null || target == null) return
        val holder = stateManager.getGemHolder(gemId)
        if (holder != null) {
            stateManager.gemUuidToHolder.remove(gemId)
            stateManager.removeGemItemFromInventory(holder, gemId)
            recalculateGrants(holder)
        }
        placementManager.forcePlaceGem(gemId, target, holder)
    }

    fun gemStatus(sender: CommandSender) {
        GemStatusView(stateManager, languageManager)
            .sendStatus(sender, gemParser.requiredCount, stateManager.getPlacedCount(), stateManager.getHeldCount())
    }

    fun isRuleGem(item: ItemStack?): Boolean = stateManager.isRuleGem(item)

    fun getGemUUID(item: ItemStack?): UUID? = stateManager.getGemUUID(item)

    fun getGemLocation(gemId: UUID?): Location? = stateManager.getGemLocation(gemId)

    fun getGemHolder(gemId: UUID?): Player? = stateManager.getGemHolder(gemId)

    fun getGemKey(gemId: UUID?): String? = stateManager.getGemKey(gemId)

    val allGemUuids: Set<UUID>
        get() = stateManager.getAllGemUuids()

    fun resolveGemIdentifier(input: String?): UUID? = stateManager.resolveGemIdentifier(input)

    fun getGemMaterial(gemId: UUID?): Material = stateManager.getGemMaterial(gemId)

    fun isSupportRequired(material: Material?): Boolean = stateManager.isSupportRequired(material)

    fun hasBlockSupport(location: Location?): Boolean = stateManager.hasBlockSupport(location)

    fun getAllGemLocations(): Map<UUID, Location> = stateManager.getAllGemLocations()

    fun findGemDefinitionByKey(gemKey: String?): GemDefinition? = stateManager.findGemDefinition(gemKey)

    fun getCachedPlayerName(uuid: UUID?): String = stateManager.getCachedPlayerName(uuid)

    fun recalculateGrants(player: Player?) {
        permissionManager.recalculateGrants(player)
    }

    fun revokeAllPlayerPermissions(player: Player?): Boolean = permissionManager.revokeAllPlayerPermissions(player)

    val currentRulers: Map<UUID, Set<String>>
        get() = permissionManager.getCurrentRulers()

    fun queueOfflineRevokes(user: UUID?, perms: Collection<String>?, groups: Collection<String>?) {
        permissionManager.queueOfflineRevokes(user, perms, groups)
    }

    fun queueOfflineEffectRevokes(user: UUID?, effects: List<org.cubexmc.model.EffectConfig>?) {
        permissionManager.queueOfflineEffectRevokes(user, effects)
    }

    fun startParticleEffectTask(particle: Particle?) {
        placementManager.startParticleEffectTask(particle)
    }

    fun checkPlayersNearRuleGems() {
        placementManager.checkPlayersNearRuleGems()
    }

    fun setGemAltarLocation(gemKey: String?, location: Location?) {
        placementManager.setGemAltarLocation(gemKey, location)
    }

    fun removeGemAltarLocation(gemKey: String?) {
        placementManager.removeGemAltarLocation(gemKey)
    }

    companion object {
        private val SAVE_ROOT_KEYS = arrayOf(
            "placed-gems",
            "held-gems",
            "redeemed",
            "redeem_owner",
            "redeem_owner_by_id",
            "full_set_owner",
            "pending_revokes",
            "allowed_uses",
            "player_names",
        )
    }
}
