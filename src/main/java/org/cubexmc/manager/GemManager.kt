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
import org.cubexmc.storage.StorageLoadStatus
import org.cubexmc.storage.StorageException
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.EffectUtils
import org.cubexmc.utils.SchedulerUtil
import org.cubexmc.view.GemStatusView
import java.util.Collections
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val pickupsInProgress: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val custodyItemClaims: MutableMap<UUID, Long> = ConcurrentHashMap()

    val stateManager: GemStateManager = GemStateManager(plugin, gemParser, languageManager)
    val boundsService: GemBoundsService = GemBoundsService(gemParser, gameplayConfig, stateManager, plugin.logger)
    val allowanceManager: GemAllowanceManager = GemAllowanceManager(gemParser, gameplayConfig)
    val permissionManager: GemPermissionManager = GemPermissionManager(plugin, gameplayConfig, stateManager)
    val globalOperationCoordinator = GlobalOperationCoordinator()
    val placementManager: GemPlacementManager =
        GemPlacementManager(plugin, gemParser, gameplayConfig, languageManager, stateManager, boundsService)
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

    val custodyAuditor: GemCustodyAuditor = GemCustodyAuditor(
        plugin,
        stateManager,
        placementManager,
        GemCustodyAuditor.GemRecovery { gemId, location -> recoverStrayGem(gemId, location) },
        Runnable { saveGems() },
    )

    private val saveLock = Any()
    private val saveRevision = AtomicLong()

    @Volatile
    private var lastWrittenSaveRevision = 0L
    @Volatile
    var lastStorageError: Throwable? = null
        private set
    @Volatile
    var lastEmergencySnapshot: File? = null
        private set

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

    fun loadGems(): Boolean {
        val loadResult = configManager.readGemsData()
        if (!loadResult.isUsable) {
            lastStorageError = loadResult.error
            plugin.logger.log(
                java.util.logging.Level.SEVERE,
                "Gem storage load failed. Active state was preserved and configured gems were not generated.",
                loadResult.error,
            )
            return false
        }
        val loadedData = requireNotNull(loadResult.data)
        val validation = GemDataValidator.validate(loadedData, gemParser.gemDefinitions)
        if (!validation.valid) {
            val failure = StorageException(
                "Gem data validation failed: " + validation.errors.joinToString("; "),
            )
            lastStorageError = failure
            plugin.logger.log(
                java.util.logging.Level.SEVERE,
                "Gem storage data failed semantic validation. Active state was preserved.",
                failure,
            )
            return false
        }
        val gemsData = try {
            GemDataSnapshot.capture(loadedData).materialize()
        } catch (failure: Exception) {
            lastStorageError = failure
            plugin.logger.log(
                java.util.logging.Level.SEVERE,
                "Gem data could not be staged. Active state was preserved.",
                failure,
            )
            return false
        }

        placementManager.prepareEscapeReload()
        placementManager.shutdownPresentation()
        stateManager.clearAll()
        permissionManager.clearRuntimeState()
        allowanceManager.clearAll()

        placementManager.loadEscapeState(gemsData)
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
        lastStorageError = null
        if (loadResult.status == StorageLoadStatus.NOT_FOUND) {
            plugin.logger.info("No existing gem data was found; treating this as a new installation.")
        }
        return true
    }

    fun saveGems(): Boolean = saveGemsInternal(true)

    fun saveGemsSync(): Boolean = saveGemsInternal(false)

    private fun saveGemsInternal(asyncWhenEnabled: Boolean): Boolean {
        val revision = saveRevision.incrementAndGet()
        val mutableSnapshot: MutableMap<String, Any?> = HashMap()
        stateManager.populateSaveSnapshot(mutableSnapshot)
        permissionManager.populateSaveSnapshot(mutableSnapshot)
        allowanceManager.populateSaveSnapshot(mutableSnapshot)
        placementManager.populateEscapeSaveSnapshot(mutableSnapshot)
        val snapshot = Collections.unmodifiableMap(HashMap(mutableSnapshot))

        val accepted = AtomicBoolean(true)
        val saveTask = Runnable {
            synchronized(saveLock) {
                if (revision < saveRevision.get() || revision < lastWrittenSaveRevision) return@synchronized
                val gemsData = configManager.getGemsData()
                for (key in SAVE_ROOT_KEYS) {
                    gemsData.set(key, null)
                }
                for ((key, value) in snapshot) {
                    gemsData.set(key, value)
                }
                val result: org.cubexmc.storage.StorageSaveResult? = configManager.saveGemData(gemsData)
                if (result == null || !result.successful) {
                    accepted.set(false)
                    val failure = result?.error
                        ?: StorageException("Storage provider returned no save result")
                    lastStorageError = failure
                    plugin.logger.log(
                        java.util.logging.Level.SEVERE,
                        "Gem data save failed at revision $revision; the revision was not marked as persisted.",
                        failure,
                    )
                    if (!asyncWhenEnabled) {
                        writeEmergencySnapshot(gemsData, revision)
                    }
                    return@synchronized
                }
                lastWrittenSaveRevision = revision
                lastStorageError = null
                lastEmergencySnapshot = null
            }
        }

        if (asyncWhenEnabled && plugin.isEnabled) {
            SchedulerUtil.asyncRun(plugin, saveTask, 0L)
        } else {
            saveTask.run()
        }
        return accepted.get()
    }

    private fun writeEmergencySnapshot(gemsData: org.bukkit.configuration.file.FileConfiguration, revision: Long) {
        try {
            val recoveryFile = configManager.saveEmergencyGemData(gemsData)
            lastEmergencySnapshot = recoveryFile
            plugin.logger.severe(
                "Primary gem save failed; wrote emergency revision $revision to ${recoveryFile.absolutePath}",
            )
        } catch (recoveryFailure: Exception) {
            lastStorageError?.addSuppressed(recoveryFailure)
            plugin.logger.log(
                java.util.logging.Level.SEVERE,
                "Primary gem save and emergency recovery snapshot both failed at revision $revision.",
                recoveryFailure,
            )
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
        // 反转白名单：手持宝石右键任何方块时，一律禁止"方块消费这个物品"。
        //
        // 这条规则取代了"枚举容器材质"的做法，因此对置物架、饰纹陶罐、雕纹书架，
        // 以及未来版本新增的任何收纳方块都天然生效——不需要 material 名单，
        // 也不需要引用高版本 API（本插件对 1.16.5 编译，根本引用不到这些新类）。
        //
        // 只否决 useInteractedBlock，不碰 useItemInHand：放置宝石方块、祭坛兑换、
        // 长按兑换走的都是 useItemInHand，功能不受影响。
        val holdingGem = event.action == Action.RIGHT_CLICK_BLOCK && stateManager.containsGem(event.item)
        val block = event.clickedBlock

        if (block != null && stateManager.locationToGemUuid.containsKey(block.location)) {
            // 手持宝石时不放行方块交互：材质恰好是箱子/木桶的宝石不能变成"能塞进别的宝石的容器"。
            if (!holdingGem && event.useInteractedBlock() == Event.Result.DENY) {
                event.setUseInteractedBlock(Event.Result.ALLOW)
            }
            if (event.action == Action.LEFT_CLICK_BLOCK && event.useItemInHand() == Event.Result.DENY) {
                event.setUseItemInHand(Event.Result.ALLOW)
            }
        }

        // 放在最后落笔，保证不会被上面的领地绕过逻辑翻回 ALLOW。
        if (holdingGem) {
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    /** 该坐标上是否有一颗已放置的宝石。世界侧保护监听器的统一入口。 */
    fun isGemBlock(block: Block?): Boolean {
        if (block == null) return false
        return stateManager.locationToGemUuid.containsKey(block.location)
    }

    /** 没有任何已放置宝石时，世界侧监听器可以直接短路，避免遍历爆炸方块列表。 */
    fun hasPlacedGems(): Boolean = stateManager.locationToGemUuid.isNotEmpty()

    /**
     * 回收一颗脱离托管的宝石（掉落物实体、被容器吐出、商店箱里被扫到等）。
     *
     * 幂等且保守：世界里已经有本体、或持有者手里确实还有本体时，什么都不做——
     * 此时触发回收的那一摞物品只是个副本，调用方直接销毁即可。
     *
     * @return true 表示这颗宝石归本插件管理（无论是否真的搬动了它）
     */
    fun recoverStrayGem(gemId: UUID?, location: Location?): Boolean {
        if (gemId == null || !stateManager.gemUuidToKey.containsKey(gemId)) return false
        if (stateManager.getGemLocation(gemId) != null) return true

        val holder = stateManager.getGemHolder(gemId)
        if (holder != null && holder.isOnline && stateManager.playerHoldsGem(holder, gemId)) return true

        stateManager.clearGemHolder(gemId)
        if (location != null && location.world != null) {
            placementManager.placeRuleGem(location, gemId)
        } else {
            placementManager.randomPlaceGem(gemId)
        }
        saveGems()
        plugin.logger.info("Recovered stray gem $gemId back into the world.")
        return true
    }

    /**
     * 同一个掉落物实体在 Folia/Paper 上可能先后触发 ItemSpawnEvent 与 PlayerDropItemEvent。
     * 以实体 UUID 认领托管操作，保证这两个监听器最多只有一个安排宝石放置。
     */
    fun claimItemCustody(entityId: UUID?): Boolean {
        if (entityId == null) return true
        val now = System.currentTimeMillis()
        val claimed = custodyItemClaims.putIfAbsent(entityId, now) == null
        if (custodyItemClaims.size > MAX_CUSTODY_ITEM_CLAIMS) {
            custodyItemClaims.entries.removeIf { now - it.value > CUSTODY_ITEM_CLAIM_TTL_MS }
        }
        return claimed
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
        placementManager.adoptPlayerPlacedGem(gemId, placedLoc)

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
        when (pickupPlacedGem(player, gemId, block.location)) {
            PickupResult.SUCCESS -> Unit
            PickupResult.INVENTORY_FULL,
            PickupResult.CANCELLED,
            PickupResult.INVALID,
            -> event.isCancelled = true
        }
    }

    fun blockPlacementConflictsWithDisplayedGem(player: Player, location: Location): Boolean {
        if (gameplayConfig.gemPresentationMode != GemPresentationMode.PROXIMITY_DISPLAY) return false
        if (!stateManager.locationToGemUuid.containsKey(location.block.location)) return false
        languageManager.sendMessage(player, "inventory.display_location_occupied")
        return true
    }

    fun handleDisplayedGemHit(player: Player, entity: org.bukkit.entity.Entity?): Boolean {
        val gemId = placementManager.resolveDisplayedGem(entity) ?: return false
        val location = stateManager.getGemLocation(gemId) ?: return false
        return pickupPlacedGem(player, gemId, location) == PickupResult.SUCCESS
    }

    private fun pickupPlacedGem(player: Player, gemId: UUID, location: Location): PickupResult {
        val currentLocation = stateManager.getGemLocation(gemId) ?: return PickupResult.INVALID
        if (!sameBlock(currentLocation, location) || stateManager.getGemHolder(gemId) != null) {
            return PickupResult.INVALID
        }
        if (!pickupsInProgress.add(gemId)) return PickupResult.INVALID
        if (!placementManager.tryBeginPickup(gemId)) {
            pickupsInProgress.remove(gemId)
            return PickupResult.INVALID
        }

        try {
            val inventory = player.inventory
            if (inventory.firstEmpty() == -1) {
                languageManager.logMessage("inventory_full")
                return PickupResult.INVENTORY_FULL
            }

            val pickupKey = stateManager.getGemKey(gemId) ?: return PickupResult.INVALID
            val pickupEvent = GemPickupEvent(player, gemId, pickupKey, currentLocation)
            Bukkit.getPluginManager().callEvent(pickupEvent)
            if (pickupEvent.isCancelled) return PickupResult.CANCELLED

            inventory.addItem(stateManager.createRuleGem(gemId))
            stateManager.setGemHolder(gemId, player)
            placementManager.cancelEscape(gemId)
            placementManager.unplaceRuleGem(currentLocation, gemId)
            permissionManager.handleInventoryOwnershipOnPickup(player, gemId)

            val definition = stateManager.findGemDefinition(stateManager.getGemKey(gemId))
            val onPickup = definition?.onPickup
            if (onPickup != null) {
                effectUtils.executeCommands(onPickup, Collections.singletonMap("%player%", player.name))
                effectUtils.playLocalSound(player.location, onPickup, 1.0f, 1.0f)
                effectUtils.playParticle(player.location, onPickup)
            }
            saveGems()
            return PickupResult.SUCCESS
        } finally {
            placementManager.endPickup(gemId)
            pickupsInProgress.remove(gemId)
        }
    }

    fun handlePlayerQuit(player: Player) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        val gemIds: MutableSet<UUID> = LinkedHashSet()
        val contents = player.inventory.contents
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (!stateManager.containsGem(item)) continue
            val removal = stateManager.stripAllGems(item)
            player.inventory.setItem(slot, removal.item)
            gemIds.addAll(removal.gemIds)
        }
        for (gemId in gemIds) {
            stateManager.clearGemHolder(gemId)
            placementManager.placeRuleGem(player.location, gemId)
        }
    }

    fun handleGemDrop(player: Player, loc: Location, droppedItemEntity: org.bukkit.entity.Item, item: ItemStack?) {
        if (!stateManager.containsGem(item)) return
        droppedItemEntity.remove()
        if (!claimItemCustody(droppedItemEntity.uniqueId)) return

        for (gemId in LinkedHashSet(stateManager.collectGemIds(item))) {
            stateManager.clearGemHolder(gemId)
            placementManager.triggerScatterEffects(gemId, loc, player.name)
            placementManager.placeRuleGem(loc, gemId)
        }
    }

    fun handlePlayerDeathDrops(player: Player, deathLoc: Location, drops: MutableList<ItemStack>) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        val gemIds: MutableSet<UUID> = LinkedHashSet()
        val iterator = drops.listIterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!stateManager.containsGem(item)) continue
            val removal = stateManager.stripAllGems(item)
            if (removal.item == null) {
                iterator.remove()
            } else {
                iterator.set(removal.item)
            }
            gemIds.addAll(removal.gemIds)
        }
        for (gemId in gemIds) {
            stateManager.clearGemHolder(gemId)
            placementManager.triggerScatterEffects(gemId, deathLoc, player.name)
            placementManager.placeRuleGem(deathLoc, gemId)
        }
    }

    fun handlePlayerJoin(player: Player) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "State mutation must occur on primary thread")
        // 删除"不属于本人"的宝石副本：本体已被收回世界后残留在背包里的那些。
        custodyAuditor.sweepPlayerInventory(player)
        permissionManager.restoreRedeemedPermissions(player)
        permissionManager.applyPendingRevokesIfAny(player)
        placementManager.refreshDisplayForPlayer(player)
    }

    fun scatterGems(): Boolean {
        if (!globalOperationCoordinator.tryBegin(GlobalOperation.SCATTER)) {
            return false
        }
        return try {
            scatterService.scatterGems()
            true
        } finally {
            globalOperationCoordinator.end(GlobalOperation.SCATTER)
        }
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
        stateManager.clearGemHolder(matchedGemId)
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
                stateManager.clearGemHolder(gid)
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

        stateManager.clearGemHolder(gemId)
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
            stateManager.clearGemHolder(consumedGemId)
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
            stateManager.clearGemHolder(gemId)
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

    /** 这一摞物品里是否藏着宝石（含收纳袋/潜影盒内部）。存储类判定都该用它。 */
    fun containsGem(item: ItemStack?): Boolean = stateManager.containsGem(item)

    fun collectGemIds(item: ItemStack?): List<UUID> = stateManager.collectGemIds(item)

    fun isContainerItem(item: ItemStack?): Boolean = stateManager.isContainerItem(item)

    fun sweepForeignInventory(inventory: org.bukkit.inventory.Inventory?, contextName: String?): Int =
        custodyAuditor.sweepForeignInventory(inventory, contextName)

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

    fun handleDisplayViewerQuit(player: Player?) {
        placementManager.removeDisplayViewer(player)
    }

    fun isDisplayedGem(entity: org.bukkit.entity.Entity?): Boolean =
        placementManager.resolveDisplayedGem(entity) != null

    fun shutdownPresentation() {
        placementManager.shutdownPresentation()
    }

    fun shutdownEscape() {
        placementManager.shutdownEscape()
    }

    fun setGemAltarLocation(gemKey: String?, location: Location?) {
        placementManager.setGemAltarLocation(gemKey, location)
    }

    fun removeGemAltarLocation(gemKey: String?) {
        placementManager.removeGemAltarLocation(gemKey)
    }

    companion object {
        private const val MAX_CUSTODY_ITEM_CLAIMS = 4096
        private const val CUSTODY_ITEM_CLAIM_TTL_MS = 60_000L
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
            "escape-state",
        )
    }

    private fun sameBlock(first: Location, second: Location): Boolean {
        return first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ
    }

    private enum class PickupResult {
        SUCCESS,
        INVENTORY_FULL,
        CANCELLED,
        INVALID,
    }
}
