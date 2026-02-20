package org.cubexmc.manager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.utils.EffectUtils;

/**
 * 专注散落流程，避免 GemManager 继续膨胀。
 */
public class GemScatterService {

    private final GemStateManager stateManager;
    private final GemPlacementManager placementManager;
    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;
    private final EffectUtils effectUtils;
    private final LanguageManager languageManager;
    private final Runnable saveAction;

    public GemScatterService(GemStateManager stateManager,
                             GemPlacementManager placementManager,
                             GemDefinitionParser gemParser,
                             GameplayConfig gameplayConfig,
                             EffectUtils effectUtils,
                             LanguageManager languageManager,
                             Runnable saveAction) {
        this.stateManager = stateManager;
        this.placementManager = placementManager;
        this.gemParser = gemParser;
        this.gameplayConfig = gameplayConfig;
        this.effectUtils = effectUtils;
        this.languageManager = languageManager;
        this.saveAction = saveAction;
    }

    public void scatterGems() {
        languageManager.logMessage("scatter_start");
        int scatteredCount = 0;

        Map<Location, UUID> placedSnapshot = stateManager.snapshotPlacedGems();
        for (Map.Entry<Location, UUID> entry : placedSnapshot.entrySet()) {
            placementManager.unplaceRuleGem(entry.getKey(), entry.getValue());
        }
        stateManager.clearPlacedMappings();

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (stateManager.isRuleGem(item)) {
                    player.getInventory().remove(item);
                }
            }
        }
        stateManager.clearHolderMappings();
        stateManager.clearGemKeys();

        languageManager.logMessage("gems_recollected");

        List<GemDefinition> defs = gemParser.getGemDefinitions();
        Map<GemDefinition, UUID> sampleGemIds = new HashMap<>();
        if (defs != null && !defs.isEmpty()) {
            for (GemDefinition def : defs) {
                int cnt = Math.max(1, def.getCount());
                for (int i = 0; i < cnt; i++) {
                    UUID gemId = UUID.randomUUID();
                    stateManager.setGemKey(gemId, def.getGemKey());
                    placementManager.randomPlaceGem(gemId);
                    sampleGemIds.putIfAbsent(def, gemId);
                    scatteredCount++;
                }
            }
            for (Map.Entry<GemDefinition, UUID> sample : sampleGemIds.entrySet()) {
                if (sample.getKey().getOnScatter() == null) {
                    continue;
                }
                Location loc = stateManager.getGemLocation(sample.getValue());
                if (loc != null) {
                    placementManager.triggerScatterEffects(sample.getValue(), loc, null, false);
                }
            }
        } else {
            int toPlace = Math.max(0, gemParser.getRequiredCount());
            scatteredCount = toPlace;
            for (int i = 0; i < toPlace; i++) {
                placementManager.randomPlaceGem(UUID.randomUUID());
            }
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("count", String.valueOf(scatteredCount));
        languageManager.logMessage("gems_scattered", placeholders);

        ExecuteConfig gemScatterExecute = gameplayConfig.getGemScatterExecute();
        effectUtils.executeCommands(gemScatterExecute, placeholders);
        effectUtils.playGlobalSound(gemScatterExecute, 1.0f, 1.0f);
        for (Player player : Bukkit.getOnlinePlayers()) {
            languageManager.showTitle(player, "gems_scattered", placeholders);
        }
        saveAction.run();
    }
}
