package org.cubexmc.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemCustodyAuditorTest {

    private static final UUID GEM_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Mock private RuleGems plugin;
    @Mock private GemStateManager stateManager;
    @Mock private GemPlacementManager placementManager;
    @Mock private World world;
    @Mock private Runnable saveAction;

    private GemCustodyAuditor auditor;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemCustodyAuditorTest"));
        when(stateManager.getAllGemUuids()).thenReturn(Collections.singleton(GEM_ID));
        auditor = new GemCustodyAuditor(
                plugin, stateManager, placementManager, (gemId, location) -> true, saveAction);
    }

    @Test
    void aGemThatIsNeitherPlacedNorHeldIsRecoveredOnlyAfterASecondObservation() {
        when(stateManager.getGemHolder(GEM_ID)).thenReturn(null);
        when(stateManager.getGemLocation(GEM_ID)).thenReturn(null);

        // 拾取、逃逸都存在极短的中间态，一次观测就纠正会误伤正常流程。
        auditor.runAudit();
        verify(placementManager, never()).randomPlaceGem(any(UUID.class));

        auditor.runAudit();
        verify(placementManager, times(1)).randomPlaceGem(GEM_ID);
        verify(saveAction).run();
    }

    @Test
    void aPlacedGemIsOnlyReRenderedNeverRelocated() {
        Location location = new Location(world, 10, 64, 10);
        when(stateManager.getGemHolder(GEM_ID)).thenReturn(null);
        when(stateManager.getGemLocation(GEM_ID)).thenReturn(location);

        auditor.runAudit();
        auditor.runAudit();

        // 被 TNT 抹掉的宝石方块靠这一步画回来（记录仍在，世界里却是空气）。
        verify(placementManager, times(2)).restoreRenderingIfMissing(GEM_ID, location);
        verify(placementManager, never()).randomPlaceGem(any(UUID.class));
    }

    @Test
    void aGemPlacedFarOutsideTheScatterRangeIsLeftWhereThePlayerPutIt() {
        // 回归防护：审计曾经按 random_place_range 判定"越界"并把宝石搬回出生点附近，
        // 导致玩家放好的宝石几分钟后静默消失。玩家可以把宝石放在世界任何地方。
        Location farAway = new Location(world, 90000, 64, -120000);
        when(stateManager.getGemHolder(GEM_ID)).thenReturn(null);
        when(stateManager.getGemLocation(GEM_ID)).thenReturn(farAway);

        auditor.runAudit();
        auditor.runAudit();
        auditor.runAudit();

        verify(placementManager, never()).randomPlaceGem(any(UUID.class));
        verify(saveAction, never()).run();
    }

    @Test
    void anIntermittentAnomalyNeverTriggersACorrection() {
        when(stateManager.getGemHolder(GEM_ID)).thenReturn(null);
        Location location = new Location(world, 10, 64, 10);
        when(stateManager.getGemLocation(GEM_ID)).thenReturn(null, location, null, location);

        auditor.runAudit();
        auditor.runAudit();
        auditor.runAudit();
        auditor.runAudit();

        verify(placementManager, never()).randomPlaceGem(any(UUID.class));
    }
}
