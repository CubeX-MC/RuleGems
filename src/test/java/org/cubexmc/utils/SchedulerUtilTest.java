package org.cubexmc.utils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SchedulerUtilTest {

    private MockedStatic<Bukkit> mockedBukkit;

    @BeforeEach
    void setUp() {
        mockedBukkit = mockStatic(Bukkit.class);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void globalRunExecutesInlineWhenPrimaryThreadNoDelay() {
        Plugin plugin = mock(Plugin.class);
        mockedBukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

        final int[] called = new int[]{0};
        Object task = SchedulerUtil.globalRun(plugin, () -> called[0]++, 0L, -1L);

        assertNull(task);
        verify(plugin, never()).getLogger();
        org.junit.jupiter.api.Assertions.assertEquals(1, called[0]);
    }

    @Test
    void globalRunUsesBukkitSchedulerForDelayedTask() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        mockedBukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.eq(5L)))
                .thenReturn(task);

        Object result = SchedulerUtil.globalRun(plugin, () -> {
        }, 5L, -1L);

        assertSame(task, result);
        verify(scheduler).runTaskLater(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.eq(5L));
    }
}
