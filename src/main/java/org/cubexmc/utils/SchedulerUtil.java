package org.cubexmc.utils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 调度器工具类，用于兼容Bukkit和Folia调度器
 */
public class SchedulerUtil {

    /** 缓存 Folia 检测结果，避免每次调度都做反射 */
    private static final boolean IS_FOLIA;
    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    /**
     * 判断服务器是否运行在Folia上
     * 
     * @return 是否为Folia服务器
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }
      /**
     * 延迟执行任务（全局调度）
     * 
     * @param plugin 插件实例
     * @param task 任务
     * @param delay 延迟时间，单位为tick
     * @param period 周期时间，单位为tick, 如果为负数则表示只延迟一次
     * @return 任务ID
     */
    public static Object globalRun(Plugin plugin, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                if (period <= 0) {
                    if (delay == 0) {
                        Method run = globalScheduler.getClass().getMethod("run", pluginClass, consumerClass);
                        return run.invoke(globalScheduler, plugin, foliaTask);
                    } else {
                        Method runDelayed = globalScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, long.class);
                        return runDelayed.invoke(globalScheduler, plugin, foliaTask, delay);
                    }
                } else {
                    Method runAtFixedRate = globalScheduler.getClass().getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class);
                    return runAtFixedRate.invoke(globalScheduler, plugin, foliaTask, Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                try {
                    plugin.getLogger().fine("Folia global scheduler unavailable, fallback to Bukkit scheduler: " + t.getMessage());
                } catch (Throwable ignored) {
                    // no-op
                }
            }
        }
        return runBukkitGlobal(plugin, task, delay, period);
    }
    
    /**
     * 取消任务
     * 
     * @param task 任务ID
     */
    public static void cancelTask(Object task) {
        if (task == null) return;
        try {
            // 优先尝试通用的 cancel 方法
            Method cancel = task.getClass().getMethod("cancel");
            cancel.invoke(task);
        } catch (Throwable ignored) {
            try {
                if (task instanceof BukkitTask) {
                    ((BukkitTask) task).cancel();
                }
            } catch (Throwable t) {
                // 忽略异常
            }
        }
    }
    /**
     * 在玩家所在区域执行任务
     * 
     * @param plugin 插件实例
     * @param entity 实体
     * @param task 任务
     * @param delay 延迟时间，单位为tick
     * @param period 周期时间，单位为tick，如果为负数则表示只延迟一次
     * @return 任务ID
     */
    public static Object entityRun(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> foliaTask = scheduledTask -> task.run();
                Runnable retiredCallback = () -> {
                    try { plugin.getLogger().fine("Entity scheduler task cancelled: entity no longer exists"); } catch (Throwable ignored) {}
                };
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                Class<?> runnableClass = Runnable.class;
                if (period <= 0) {
                    if (delay == 0) {
                        Method run = entityScheduler.getClass().getMethod("run", pluginClass, consumerClass, runnableClass);
                        return run.invoke(entityScheduler, plugin, foliaTask, retiredCallback);
                    } else {
                        Method runDelayed = entityScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, runnableClass, long.class);
                        return runDelayed.invoke(entityScheduler, plugin, foliaTask, retiredCallback, delay);
                    }
                } else {
                    Method runAtFixedRate = entityScheduler.getClass().getMethod("runAtFixedRate", pluginClass, consumerClass, runnableClass, long.class, long.class);
                    return runAtFixedRate.invoke(entityScheduler, plugin, foliaTask, retiredCallback, Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                try {
                    plugin.getLogger().fine("Folia entity scheduler unavailable, fallback to Bukkit scheduler: " + t.getMessage());
                } catch (Throwable ignored) {
                    // no-op
                }
            }
        }
        return runBukkitEntityFallback(plugin, task, delay, period);
    }
      /**
     * 在指定位置区域延迟执行任务
     * 
     * @param plugin 插件实例
     * @param location 位置
     * @param task 任务
     * @param delay 延迟时间，单位为tick
     * @return 任务ID
     */
    public static Object regionRun(Plugin plugin, Location location, Runnable task, long delay, long period) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object regionScheduler = server.getClass().getMethod("getRegionScheduler").invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                Class<?> locationClass = Location.class;
                if (period <= 0) {
                    if (delay == 0) {
                        Method run = regionScheduler.getClass().getMethod("run", pluginClass, locationClass, consumerClass);
                        return run.invoke(regionScheduler, plugin, location, foliaTask);
                    } else {
                        Method runDelayed = regionScheduler.getClass().getMethod("runDelayed", pluginClass, locationClass, consumerClass, long.class);
                        return runDelayed.invoke(regionScheduler, plugin, location, foliaTask, delay);
                    }
                } else {
                    Method runAtFixedRate = regionScheduler.getClass().getMethod("runAtFixedRate", pluginClass, locationClass, consumerClass, long.class, long.class);
                    return runAtFixedRate.invoke(regionScheduler, plugin, location, foliaTask, Math.max(1, delay), period);
                }
            } catch (Throwable t) {
                try {
                    plugin.getLogger().fine("Folia region scheduler unavailable, fallback to Bukkit scheduler: " + t.getMessage());
                } catch (Throwable ignored) {
                    // no-op
                }
            }
        }
        return runBukkitRegionFallback(plugin, task, delay, period);
    }
    /**
     * 在异步线程延迟执行任务（统一使用 tick 作为时间单位）
     *
     * @param plugin 插件实例
     * @param task 任务
     * @param delay 延迟时间，单位为 tick
     */
    public static void asyncRun(Plugin plugin, Runnable task, long delay) {
        delay = Math.max(0, delay);
        if (isFolia()) {
            try {
                Server server = Bukkit.getServer();
                Object asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);
                Consumer<Object> foliaTask = scheduledTask -> task.run();
                Class<?> pluginClass = Plugin.class;
                Class<?> consumerClass = Consumer.class;
                if (delay <= 0) {
                    Method runNow = asyncScheduler.getClass().getMethod("runNow", pluginClass, consumerClass);
                    runNow.invoke(asyncScheduler, plugin, foliaTask);
                } else {
                    Method runDelayed = asyncScheduler.getClass().getMethod("runDelayed", pluginClass, consumerClass, long.class, TimeUnit.class);
                    // interpret delay as ticks for consistency with Bukkit API
                    runDelayed.invoke(asyncScheduler, plugin, foliaTask, delay * 50, TimeUnit.MILLISECONDS);
                }
            } catch (Throwable t) {
                long ticks = delay <= 0 ? 0L : Math.max(1L, delay);
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
            }
        } else {
            long ticks = delay <= 0 ? 0L : Math.max(1L, delay);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        }
    }

    /**
     * 兼容 Bukkit 与 Folia 的安全传送：
     * - 若存在 Player#teleportAsync(Location)，优先通过反射调用（Folia 推荐）
     * - 否则在合适的线程上下文调用同步 teleport
     */
    public static void safeTeleport(Plugin plugin, org.bukkit.entity.Player player, org.bukkit.Location dest) {
        if (player == null || dest == null) return;
        // Try teleportAsync via reflection (Paper/Folia >= 1.20+)
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("teleportAsync", org.bukkit.Location.class);
            m.invoke(player, dest);
            return;
        } catch (NoSuchMethodException ignored) {
            // method not present on this API
        } catch (Throwable t) {
            // fall through to sync teleport fallback
        }
        // Fallback: schedule appropriately
        if (isFolia()) {
            // Execute on player's region thread; some Folia builds still allow sync teleport from scheduler
            entityRun(plugin, player, () -> {
                try { player.teleport(dest); } catch (Throwable e) { Bukkit.getLogger().fine("Failed to teleport player in Folia fallback: " + e.getMessage()); }
            }, 0L, -1L);
        } else {
            if (org.bukkit.Bukkit.isPrimaryThread()) {
                player.teleport(dest);
            } else {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> player.teleport(dest));
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