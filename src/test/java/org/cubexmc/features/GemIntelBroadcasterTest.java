package org.cubexmc.features;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Pair;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemIntelBroadcasterTest {

    @Mock private RuleGems plugin;
    @Mock private GemManager gemManager;
    @Mock private Player player;

    @Test
    void playerStateIsReadOnlyInsideEntityTaskAndOfflineRecipientIsSkipped() throws Exception {
        UUID playerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        GemIntelBroadcaster broadcaster = new GemIntelBroadcaster(plugin, gemManager);

        when(player.getUniqueId()).thenReturn(playerId);
        when(gemManager.getCurrentRulers()).thenReturn(Collections.emptyMap());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.singleton(player));
            scheduler.when(() -> SchedulerUtil.entityRun(
                    eq(plugin), eq(player), any(Runnable.class), eq(0L), eq(-1L)))
                    .thenAnswer(invocation -> {
                        scheduled.set(invocation.getArgument(2));
                        return new Object();
                    });

            invokeNoArgs(broadcaster, "broadcastIntel");

            assertNotNull(scheduled.get());
            verify(player, never()).isOnline();
            verify(player, never()).getLocation();

            when(player.isOnline()).thenReturn(false);
            scheduled.get().run();

            verify(player, never()).getLocation();
            verify(player, never()).sendMessage(any(String.class));
        }
    }

    @Test
    void everyGeneratedAxisRangeContainsTheGemCoordinate() throws Exception {
        GemIntelBroadcaster broadcaster = new GemIntelBroadcaster(plugin, gemManager);
        Method method = GemIntelBroadcaster.class.getDeclaredMethod("rangeContaining", int.class);
        method.setAccessible(true);

        int[] coordinates = {-30_000_000, -1_025, -1, 0, 1, 1_025, 30_000_000};
        for (int coordinate : coordinates) {
            for (int sample = 0; sample < 200; sample++) {
                @SuppressWarnings("unchecked")
                Pair<Integer, Integer> range = (Pair<Integer, Integer>) method.invoke(broadcaster, coordinate);
                assertTrue(range.getFirst() <= coordinate, "range starts after coordinate");
                assertTrue(range.getSecond() >= coordinate, "range ends before coordinate");
                assertTrue(range.getFirst() % 50 == 0, "range start is not aligned");
                assertTrue(range.getSecond() % 50 == 0, "range end is not aligned");
            }
        }
    }

    private void invokeNoArgs(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
}
