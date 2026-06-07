package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.cubexmc.model.ExecuteConfig
import org.cubexmc.model.GemDefinition
import org.cubexmc.utils.EffectUtils
import java.util.UUID
import kotlin.math.max

/**
 * 专注散落流程，避免 GemManager 继续膨胀。
 */
class GemScatterService(
    private val stateManager: GemStateManager,
    private val placementManager: GemPlacementManager,
    private val gemParser: GemDefinitionParser,
    private val gameplayConfig: GameplayConfig,
    private val effectUtils: EffectUtils,
    private val languageManager: LanguageManager,
    private val resetOwnershipStateAction: Runnable?,
    private val saveAction: Runnable,
) {
    fun scatterGems() {
        languageManager.logMessage("scatter_start")
        var scatteredCount = 0

        val placedSnapshot: Map<Location, UUID> = stateManager.snapshotPlacedGems()
        for ((location, gemId) in placedSnapshot) {
            placementManager.unplaceRuleGem(location, gemId)
        }
        stateManager.clearPlacedMappings()

        for (player in Bukkit.getOnlinePlayers()) {
            for (item in player.inventory.contents) {
                if (stateManager.isRuleGem(item)) {
                    player.inventory.remove(item)
                }
            }
        }
        stateManager.clearHolderMappings()
        stateManager.clearGemKeys()
        resetOwnershipStateAction?.run()

        languageManager.logMessage("gems_recollected")

        val definitions: List<GemDefinition>? = gemParser.gemDefinitions
        val sampleGemIds = HashMap<GemDefinition, UUID>()
        if (!definitions.isNullOrEmpty()) {
            for (definition in definitions) {
                val count = max(1, definition.count)
                for (i in 0 until count) {
                    val gemId = UUID.randomUUID()
                    stateManager.setGemKey(gemId, definition.gemKey)
                    placementManager.randomPlaceGem(gemId)
                    sampleGemIds.putIfAbsent(definition, gemId)
                    scatteredCount++
                }
            }
            for ((definition, gemId) in sampleGemIds) {
                if (definition.onScatter == null) {
                    continue
                }
                val location = stateManager.getGemLocation(gemId)
                if (location != null) {
                    placementManager.triggerScatterEffects(gemId, location, null, false)
                }
            }
        } else {
            val toPlace = max(0, gemParser.requiredCount)
            scatteredCount = toPlace
            for (i in 0 until toPlace) {
                placementManager.randomPlaceGem(UUID.randomUUID())
            }
        }

        val placeholders = HashMap<String, String>()
        placeholders["count"] = scatteredCount.toString()
        languageManager.logMessage("gems_scattered", placeholders)

        val gemScatterExecute: ExecuteConfig? = gameplayConfig.gemScatterExecute
        effectUtils.executeCommands(gemScatterExecute, placeholders)
        effectUtils.playGlobalSound(gemScatterExecute, 1.0f, 1.0f)
        for (player in Bukkit.getOnlinePlayers()) {
            languageManager.showTitle(player, "gems_scattered", placeholders)
        }
        saveAction.run()
    }
}
