package org.cubexmc.utils;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cubexmc.scheduler.BukkitImmediateMode;
import org.cubexmc.scheduler.CubexScheduler;
import org.cubexmc.scheduler.LegacySchedulerAdapter;

/**
 * 调度器工具类，用于兼容 Bukkit 和 Folia 调度器。
 *
 * <p>迁移期保留 RuleGems 既有 public surface;实际调度委托给 cubex-scheduler。
 */
public class SchedulerUtil {

    private static final Map<Plugin, LegacySchedulerAdapter> ADAPTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SchedulerUtil() {
    }

    public static boolean isFolia() {
        return CubexScheduler.detectFolia();
    }

    public static void cancelTask(Object task) {
        LegacySchedulerAdapter.cancelTaskHandle(task);
    }

    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        return adapter(plugin).globalRun(task, delay, period);
    }

    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        return adapter(plugin).entityRun(entity, task, delay, period);
    }

    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        return adapter(plugin).regionRun(location, task, delay, period);
    }

    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        adapter(plugin).asyncRun(task, delay);
    }

    public static void safeTeleport(Plugin plugin, Player player, Location dest) {
        adapter(plugin).safeTeleport(player, dest);
    }

    private static LegacySchedulerAdapter adapter(Plugin plugin) {
        return ADAPTERS.computeIfAbsent(plugin, SchedulerUtil::createAdapter);
    }

    private static LegacySchedulerAdapter createAdapter(Plugin plugin) {
        return LegacySchedulerAdapter.builder(plugin)
                .immediateMode(BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD)
                .trackTasksForCancelAll(false)
                .tickAccessEnabled(false)
                .build();
    }
}
