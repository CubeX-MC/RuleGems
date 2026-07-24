package org.cubexmc.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemNavigatorTest {

    @Mock private RuleGems plugin;
    @Mock private GemManager gemManager;
    @Mock private LanguageManager languageManager;
    @Mock private Player player;
    @Mock private World world;

    private GemNavigator navigator;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLanguageManager()).thenReturn(languageManager);
        lenient().when(languageManager.formatMessage(anyString(), any())).thenReturn("");
        navigator = new GemNavigator(plugin, gemManager);
    }

    @Test
    void compassTargetIsAPlayerRelativeWaypoint() throws Exception {
        Location playerLocation = new Location(world, 100.25, 70.0, -40.75);
        Location gemLocation = new Location(world, 850.0, 12.0, 620.0);

        Location projected = project(playerLocation, gemLocation);

        assertNotNull(projected);
        double projectedX = projected.getX() - playerLocation.getX();
        double projectedZ = projected.getZ() - playerLocation.getZ();
        double gemX = gemLocation.getX() - playerLocation.getX();
        double gemZ = gemLocation.getZ() - playerLocation.getZ();
        assertEquals(32.0, Math.hypot(projectedX, projectedZ), 1.0e-9);
        assertEquals(playerLocation.getY(), projected.getY(), 1.0e-9);
        assertEquals(0.0, projectedX * gemZ - projectedZ * gemX, 1.0e-6);
        assertTrue(projectedX * gemX + projectedZ * gemZ > 0.0);
        assertTrue(projected.distanceSquared(gemLocation) > 1.0);
    }

    @Test
    void failedSearchCancelsAndRestoresExistingSession() throws Exception {
        UUID playerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID gemId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        Location playerLocation = new Location(world, 10.0, 64.0, 10.0);
        Location gemLocation = new Location(world, 400.0, 30.0, -250.0);
        Location originalTarget = new Location(world, 0.0, 64.0, 0.0);
        Object task = new Object();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getCompassTarget()).thenReturn(originalTarget);
        when(player.isOnline()).thenReturn(true);
        when(gemManager.getAllGemLocations())
                .thenReturn(Collections.singletonMap(gemId, gemLocation), Collections.emptyMap());
        setActiveSeconds(-1);

        try (MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.entityRun(
                    eq(plugin), eq(player), any(Runnable.class), eq(10L), eq(10L)))
                    .thenReturn(task);

            navigate(player);

            ArgumentCaptor<Location> firstTarget = ArgumentCaptor.forClass(Location.class);
            verify(player).setCompassTarget(firstTarget.capture());
            assertEquals(32.0, horizontalDistance(playerLocation, firstTarget.getValue()), 1.0e-9);

            navigate(player);

            scheduler.verify(() -> SchedulerUtil.cancelTask(task));
            verify(player, times(2)).setCompassTarget(any(Location.class));
            verify(player).setCompassTarget(originalTarget);
        }
    }

    @Test
    void disappearingTargetEndsTheRepeatingSession() throws Exception {
        UUID playerId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        UUID gemId = UUID.fromString("60000000-0000-0000-0000-000000000006");
        Location playerLocation = new Location(world, 10.0, 64.0, 10.0);
        Location gemLocation = new Location(world, 400.0, 30.0, -250.0);
        Location originalTarget = new Location(world, 0.0, 64.0, 0.0);
        AtomicReference<Runnable> refresh = new AtomicReference<>();
        Object task = new Object();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getCompassTarget()).thenReturn(originalTarget);
        when(player.isOnline()).thenReturn(true);
        when(gemManager.getAllGemLocations()).thenReturn(Collections.singletonMap(gemId, gemLocation));
        when(gemManager.getGemLocation(gemId)).thenReturn(null);

        try (MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.entityRun(
                    eq(plugin), eq(player), any(Runnable.class), eq(10L), eq(10L)))
                    .thenAnswer(invocation -> {
                        refresh.set(invocation.getArgument(2));
                        return task;
                    });

            navigate(player);
            assertNotNull(refresh.get());
            refresh.get().run();

            scheduler.verify(() -> SchedulerUtil.cancelTask(task));
            verify(player).setCompassTarget(originalTarget);
        }
    }

    @Test
    void stalePlacedLocationForHeldGemIsIgnored() throws Exception {
        UUID gemId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID playerId = UUID.fromString("40000000-0000-0000-0000-000000000004");
        Location playerLocation = new Location(world, 10.0, 64.0, 10.0);
        Location staleLocation = new Location(world, 20.0, 64.0, 20.0);

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        when(gemManager.getAllGemLocations()).thenReturn(Collections.singletonMap(gemId, staleLocation));
        when(gemManager.getGemHolder(gemId)).thenReturn(mock(Player.class));

        navigate(player);

        verify(player, never()).setCompassTarget(any(Location.class));
    }

    private void navigate(Player target) throws Exception {
        Method method = GemNavigator.class.getDeclaredMethod("navigateToNearestGem", Player.class);
        method.setAccessible(true);
        method.invoke(navigator, target);
    }

    private Location project(Location from, Location to) throws Exception {
        Class<?> owner = Class.forName("org.cubexmc.features.GemNavigatorKt");
        Method method = owner.getDeclaredMethod("relativeCompassTarget", Location.class, Location.class);
        return (Location) method.invoke(null, from, to);
    }

    private void setActiveSeconds(int seconds) throws Exception {
        java.lang.reflect.Field field = GemNavigator.class.getDeclaredField("activeSeconds");
        field.setAccessible(true);
        field.setInt(navigator, seconds);
    }

    private double horizontalDistance(Location first, Location second) {
        return Math.hypot(second.getX() - first.getX(), second.getZ() - first.getZ());
    }
}
