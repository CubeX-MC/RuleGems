package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemBoundsServiceTest {

    private static final UUID GEM_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    /** 原版默认边界直径，用来模拟"装了 ChunkyBorder 但原版边界没被设置"的线上情况。 */
    private static final double VANILLA_DEFAULT_SIZE = 5.9999968E7;

    @Mock private World world;
    @Mock private WorldBorder border;
    @Mock private GemDefinitionParser gemParser;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private GemStateManager stateManager;

    private GemBoundsService boundsService;

    @BeforeEach
    void setUp() {
        boundsService = new GemBoundsService(
                gemParser, gameplayConfig, stateManager, Logger.getLogger("GemBoundsServiceTest"));
        lenient().when(world.getMinHeight()).thenReturn(-64);
        lenient().when(world.getName()).thenReturn("world");
        lenient().when(world.getWorldBorder()).thenReturn(border);
        lenient().when(border.getCenter()).thenReturn(new Location(world, 0, 0, 0));
        lenient().when(stateManager.getGemKey(GEM_ID)).thenReturn(null);
        lenient().when(gameplayConfig.getRandomPlaceCorner1()).thenReturn(new Location(world, -1000, 64, -1000));
        lenient().when(gameplayConfig.getRandomPlaceCorner2()).thenReturn(new Location(world, 1000, 64, 1000));
    }

    @Test
    void configuredRangeConstrainsGemsWhenTheVanillaBorderIsWideOpen() {
        // ChunkyBorder 这类插件不同步原版边界，只看 WorldBorder 等于没做校验，
        // 因此 random_place_range 必须是硬约束。
        when(border.getSize()).thenReturn(VANILLA_DEFAULT_SIZE);

        GemBoundsService.Bounds bounds = boundsService.boundsFor(GEM_ID);

        assertNotNull(bounds);
        assertEquals(-1000, bounds.getMinX());
        assertEquals(1000, bounds.getMaxX());
        assertEquals(-1000, bounds.getMinZ());
        assertEquals(1000, bounds.getMaxZ());
    }

    @Test
    void vanillaBorderShrinksTheLegalAreaAndKeepsASafetyMargin() {
        when(border.getSize()).thenReturn(200.0);

        GemBoundsService.Bounds bounds = boundsService.boundsFor(GEM_ID);

        assertNotNull(bounds);
        // 半径 100 减去 4 格安全边距，避免宝石落在会持续掉血的边界带上。
        assertEquals(-96, bounds.getMinX());
        assertEquals(96, bounds.getMaxX());
    }

    @Test
    void locationsOutsideTheConfiguredRangeAreRejected() {
        when(border.getSize()).thenReturn(VANILLA_DEFAULT_SIZE);

        assertTrue(boundsService.isInside(GEM_ID, new Location(world, 999, 70, -999)));
        assertFalse(boundsService.isInside(GEM_ID, new Location(world, 1001, 70, 0)));
    }

    @Test
    void escapeChecksLookAtTheWorldBorderOnlyNotAtTheScatterRange() {
        // 局部逃逸允许把宝石带出 random_place_range，只要还在世界边界内。
        when(border.getSize()).thenReturn(20_000.0);

        Location outsideRangeInsideBorder = new Location(world, 5000, 70, -8000);
        assertFalse(boundsService.isInside(GEM_ID, outsideRangeInsideBorder), "散落范围应当排除它");
        assertTrue(boundsService.isInsideBorder(outsideRangeInsideBorder), "逃逸判定应当接受它");
    }

    @Test
    void clampToBorderPullsALocationBackInsideTheBorderKeepingY() {
        when(border.getSize()).thenReturn(200.0);

        Location clamped = boundsService.clampToBorder(new Location(world, 5000, 70, -8000));

        assertEquals(96, clamped.getBlockX());
        assertEquals(-96, clamped.getBlockZ());
        assertEquals(70.0, clamped.getY());
    }

    @Test
    void clampToBorderIsANoOpWhenNoBorderIsConfigured() {
        when(border.getSize()).thenReturn(VANILLA_DEFAULT_SIZE);

        Location untouched = boundsService.clampToBorder(new Location(world, 5_000_000, 70, -8_000_000));

        assertEquals(5_000_000, untouched.getBlockX());
        assertEquals(-8_000_000, untouched.getBlockZ());
    }

    @Test
    void theVanillaDefaultBorderCountsAsNoBorderAtAll() {
        // 逃逸的唯一上界就是原版边界；默认值等于没有上界，必须能识别出来并告警。
        when(border.getSize()).thenReturn(VANILLA_DEFAULT_SIZE);
        assertFalse(boundsService.hasEffectiveBorder(world));

        when(border.getSize()).thenReturn(20_000.0);
        assertTrue(boundsService.hasEffectiveBorder(world));
    }

    @Test
    void randomColumnAlwaysLandsInsideTheLegalArea() {
        when(border.getSize()).thenReturn(200.0);
        Random random = new Random(1234L);

        for (int i = 0; i < 200; i++) {
            Location column = boundsService.randomColumn(GEM_ID, random);
            assertNotNull(column);
            assertTrue(boundsService.isInside(GEM_ID, column), "sampled column escaped the bounds: " + column);
        }
    }

    @Test
    void centerColumnIsTheTerminalFallbackAndIsAlwaysLegal() {
        when(border.getSize()).thenReturn(200.0);

        Location center = boundsService.centerColumn(GEM_ID);

        assertNotNull(center);
        assertEquals(0, center.getBlockX());
        assertEquals(0, center.getBlockZ());
        assertTrue(boundsService.isInside(GEM_ID, center));
    }

    @Test
    void missingConfigurationYieldsNoBounds() {
        when(gameplayConfig.getRandomPlaceCorner1()).thenReturn(null);
        when(gameplayConfig.getRandomPlaceCorner2()).thenReturn(null);

        assertEquals(null, boundsService.boundsFor(GEM_ID));
    }

    @Test
    void disjointConfiguredRangeFailsClosedInsteadOfEscapingTheWorldBorder() {
        when(border.getSize()).thenReturn(200.0);
        when(gameplayConfig.getRandomPlaceCorner1()).thenReturn(new Location(world, 500, 64, 500));
        when(gameplayConfig.getRandomPlaceCorner2()).thenReturn(new Location(world, 600, 64, 600));

        assertNull(boundsService.boundsFor(GEM_ID));
        assertNull(boundsService.randomColumn(GEM_ID, new Random(1L)));
        assertNull(boundsService.centerColumn(GEM_ID));
        assertFalse(
                boundsService.isInside(GEM_ID, new Location(world, 0, 70, 0)),
                "无交集的显式配置不能退化成只检查世界边界");
    }
}
