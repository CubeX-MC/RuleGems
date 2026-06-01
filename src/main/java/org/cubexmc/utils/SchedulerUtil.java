package org.cubexmc.utils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 调度器工具类，用于兼容Bukkit和Folia调度器
 */
public class SchedulerUtil {

    private static final boolean IS_FOLIA;

    // --- Cached Folia Reflection Methods ---
    private static Method METHOD_GET_GLOBAL_REGION_SCHEDULER;
    private static Method METHOD_GLOBAL_RUN;
    private static Method METHOD_GLOBAL_RUN_DELAYED;
    private static Method METHOD_GLOBAL_RUN_AT_FIXED_RATE;

    private static Method METHOD_GET_ENTITY_SCHEDULER;
    private static Method METHOD_ENTITY_RUN;
    private static Method METHOD_ENTITY_RUN_DELAYED;
    private static Method METHOD_ENTITY_RUN_AT_FIXED_RATE;

    private static Method METHOD_GET_REGION_SCHEDULER;
    private static Method METHOD_REGION_RUN;
    private static Method METHOD_REGION_RUN_DELAYED;
    private static Method METHOD_REGION_RUN_AT_FIXED_RATE;

    private static Method METHOD_GET_ASYNC_SCHEDULER;
    private static Method METHOD_ASYNC_RUN_NOW;
    private static Method METHOD_ASYNC_RUN_DELAYED;

    private static Method METHOD_PLAYER_TELEPORT_ASYNC;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;

            // Pre-cache methods if Folia is detected
            Class<?> serverClass = Bukkit.getServer().getClass();
            Class<?> pluginClass = Plugin.class;
            Class<?> consumerClass = Consumer.class;
            Class<?> runnableClass = Runnable.class;
            Class<?> locationClass = Location.class;

            // Global Region Scheduler
            METHOD_GET_GLOBAL_REGION_SCHEDULER = serverClass.getMethod("getGlobalRegionScheduler");
            Class<?> globalSchedulerClass = METHOD_GET_GLOBAL_REGION_SCHEDULER.getReturnType();
            METHOD_GLOBAL_RUN = globalSchedulerClass.getMethod("run", pluginClass, consumerClass);
            METHOD_GLOBAL_RUN_DELAYED = globalSchedulerClass.getMethod("runDelayed", pluginClass, consumerClass,
                    long.class);
            METHOD_GLOBAL_RUN_AT_FIXED_RATE = globalSchedulerClass.getMethod("runAtFixedRate", pluginClass,
                    consumerClass, long.class, long.class);

            // Entity Scheduler
            METHOD_GET_ENTITY_SCHEDULER = Entity.class.getMethod("getScheduler");
            Class<?> entitySchedulerClass = METHOD_GET_ENTITY_SCHEDULER.getReturnType();
            METHOD_ENTITY_RUN = entitySchedulerClass.getMethod("run", pluginClass, consumerClass, runnableClass);
            METHOD_ENTITY_RUN_DELAYED = entitySchedulerClass.getMethod("runDelayed", pluginClass, consumerClass,
                    runnableClass, long.class);
            METHOD_ENTITY_RUN_AT_FIXED_RATE = entitySchedulerClass.getMethod("runAtFixedRate", pluginClass,
                    consumerClass, runnableClass, long.class, long.class);

            // Region Scheduler
            METHOD_GET_REGION_SCHEDULER = serverClass.getMethod("getRegionScheduler");
            Class<?> regionSchedulerClass = METHOD_GET_REGION_SCHEDULER.getReturnType();
            METHOD_REGION_RUN = regionSchedulerClass.getMethod("run", pluginClass, locationClass, consumerClass);
            METHOD_REGION_RUN_DELAYED = regionSchedulerClass.getMethod("runDelayed", pluginClass, locationClass,
                    consumerClass, long.class);
            METHOD_REGION_RUN_AT_FIXED_RATE = regionSchedulerClass.getMethod("runAtFixedRate", pluginClass,
                    locationClass, consumerClass, long.class, long.class);

            // Async Scheduler
            METHOD_GET_ASYNC_SCHEDULER = serverClass.getMethod("getAsyncScheduler");
            Class<?> asyncSchedulerClass = METHOD_GET_ASYNC_SCHEDULER.getReturnType();
            METHOD_ASYNC_RUN_NOW = asyncSchedulerClass.getMethod("runNow", pluginClass, consumerClass);
            METHOD_ASYNC_RUN_DELAYED = asyncSchedulerClass.getMethod("runDelayed", pluginClass, consumerClass,
                    long.class, TimeUnit.class);

        } catch (Throwable e) {
            folia = false;
        }
        IS_FOLIA = folia;

        try {
            METHOD_PLAYER_TELEPORT_ASYNC = Player.class.getMethod("teleportAsync", Location.class);
        } catch (Throwable ignored) {
            // Paper/Folia >= 1.20 API is missing, fallback normally
        }
    }

    /**
     * 判断服务器是否运行在Folia上
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * 取消任务
     */
    public static void cancelTask(Object task) {
        if (task == null)
            return;
        try {
            Method cancel = task.getClass().getMethod("cancel");
            cancel.invoke(task);
        } catch (Throwable ignored) {
            try {
                if (task instanceof BukkitTask) {
                    ((BukkitTask) task).cancel();
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    /**
     * 延迟执行任务（全局调度）
     */
    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object globalScheduler = METHOD_GET_GLOBAL_REGION_SCHEDULER.invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();

                if (period <= 0) {
                    if (delay == 0) {
                        return METHOD_GLOBAL_RUN.invoke(globalScheduler, plugin, foliaTask);
                    } else {
                        return METHOD_GLOBAL_RUN_DELAYED.invoke(globalScheduler, plugin, foliaTask, delay);
                    }
                } else {
                    return METHOD_GLOBAL_RUN_AT_FIXED_RATE.invoke(globalScheduler, plugin, foliaTask,
                            Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                // fallback below
            }
        }
        return runBukkitGlobal(plugin, task, delay, period);
    }

    /**
     * 在玩家所在区域执行任务
     */
    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Object entityScheduler = METHOD_GET_ENTITY_SCHEDULER.invoke(entity);
                Consumer<Object> foliaTask = scheduledTask -> task.run();
                Runnable retiredCallback = () -> {
                };

                if (period <= 0) {
                    if (delay == 0) {
                        return METHOD_ENTITY_RUN.invoke(entityScheduler, plugin, foliaTask, retiredCallback);
                    } else {
                        return METHOD_ENTITY_RUN_DELAYED.invoke(entityScheduler, plugin, foliaTask, retiredCallback,
                                delay);
                    }
                } else {
                    return METHOD_ENTITY_RUN_AT_FIXED_RATE.invoke(entityScheduler, plugin, foliaTask, retiredCallback,
                            Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                // fallback below
            }
        }
        return runBukkitEntityFallback(plugin, task, delay, period);
    }

    /**
     * 在指定位置区域延迟执行任务
     */
    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object regionScheduler = METHOD_GET_REGION_SCHEDULER.invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();

                if (period <= 0) {
                    if (delay == 0) {
                        return METHOD_REGION_RUN.invoke(regionScheduler, plugin, location, foliaTask);
                    } else {
                        return METHOD_REGION_RUN_DELAYED.invoke(regionScheduler, plugin, location, foliaTask, delay);
                    }
                } else {
                    return METHOD_REGION_RUN_AT_FIXED_RATE.invoke(regionScheduler, plugin, location, foliaTask,
                            Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                // fallback below
            }
        }
        return runBukkitRegionFallback(plugin, task, delay, period);
    }

    /**
     * 在异步线程延迟执行任务（统一使用 tick 作为时间单位）
     */
    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object asyncScheduler = METHOD_GET_ASYNC_SCHEDULER.invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();

                if (delay <= 0) {
                    METHOD_ASYNC_RUN_NOW.invoke(asyncScheduler, plugin, foliaTask);
                } else {
                    // interpret delay as ticks for consistency with Bukkit API
                    METHOD_ASYNC_RUN_DELAYED.invoke(asyncScheduler, plugin, foliaTask, delay * 50,
                            TimeUnit.MILLISECONDS);
                }
                return;
            } catch (Throwable t) {
                // fallback below
            }
        }
        long ticks = delay <= 0 ? 0L : Math.max(1L, delay);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
    }

    /**
     * 兼容 Bukkit 与 Folia 的安全传送：
     * - 若存在 Player#teleportAsync(Location)，优先通过反射调用（Folia 推荐）
     * - 否则在合适的线程上下文调用同步 teleport
     */
    public static void safeTeleport(Plugin plugin, Player player, Location dest) {
        if (player == null || dest == null)
            return;

        if (METHOD_PLAYER_TELEPORT_ASYNC != null) {
            try {
                METHOD_PLAYER_TELEPORT_ASYNC.invoke(player, dest);
                return;
            } catch (Throwable t) {
                // fall through to sync teleport fallback
            }
        }

        // Fallback: schedule appropriately
        if (isFolia()) {
            entityRun(plugin, player, () -> {
                try {
                    player.teleport(dest);
                } catch (Throwable ignored) {
                }
            }, 0L, -1L);
        } else {
            if (Bukkit.isPrimaryThread()) {
                player.teleport(dest);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> player.teleport(dest));
            }
        }
    }

    private static Object runBukkitGlobal(Plugin plugin, Runnable task, long delay, long period) {
        if (period < 0) {
            if (delay == 0) {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                    return null;
                }
                return Bukkit.getScheduler().runTask(plugin, task);
            }
            return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }

    private static Object runBukkitEntityFallback(Plugin plugin, Runnable task, long delay, long period) {
        if (period <= 0) {
            if (delay == 0) {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                    return null;
                }
                return Bukkit.getScheduler().runTask(plugin, task);
            }
            return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }

    private static Object runBukkitRegionFallback(Plugin plugin, Runnable task, long delay, long period) {
        return runBukkitEntityFallback(plugin, task, delay, period);
    }
}