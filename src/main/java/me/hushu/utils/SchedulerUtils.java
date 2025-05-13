package me.hushu.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度器工具类，用于兼容Bukkit和Folia
 */
public class SchedulerUtils {

    private static final boolean IS_FOLIA;
    private static Method FOLIA_REGION_SCHEDULER_METHOD = null;
    private static Method FOLIA_GLOBAL_REGION_SCHEDULER_METHOD = null;
    private static Method FOLIA_ENTITY_SCHEDULER_METHOD = null;

    static {
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
            
            // 获取Folia的各种调度器方法
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            FOLIA_REGION_SCHEDULER_METHOD = bukkitClass.getMethod("getRegionScheduler");
            FOLIA_GLOBAL_REGION_SCHEDULER_METHOD = bukkitClass.getMethod("getGlobalRegionScheduler");
            
            Class<?> entityClass = Class.forName("org.bukkit.entity.Entity");
            FOLIA_ENTITY_SCHEDULER_METHOD = entityClass.getMethod("getScheduler");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // 不是Folia
        }
        IS_FOLIA = isFolia;
    }

    /**
     * 判断是否在Folia环境下运行
     * @return 是否为Folia
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * 执行全局任务，适用于不依赖特定位置或实体的任务
     * @param plugin 插件实例
     * @param task 要执行的任务
     * @param delay 延迟（刻）
     * @param period 周期（刻），0代表执行一次
     */
    public static void runGlobalTask(Plugin plugin, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            try {
                Object scheduler = FOLIA_GLOBAL_REGION_SCHEDULER_METHOD.invoke(null);
                if (period > 0) {
                    // 反射调用GlobalRegionScheduler的scheduleAtFixedRate方法
                    Method scheduleMethod = scheduler.getClass().getMethod("scheduleAtFixedRate", 
                            Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                    scheduleMethod.invoke(scheduler, plugin, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, period * 50, TimeUnit.MILLISECONDS);
                } else {
                    // 反射调用GlobalRegionScheduler的runDelayed方法
                    Method runMethod = scheduler.getClass().getMethod("runDelayed", 
                            Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    runMethod.invoke(scheduler, plugin, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("无法调度全局任务: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // 使用Bukkit调度器
            if (period > 0) {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        }
    }

    /**
     * 在特定位置执行任务
     * @param plugin 插件实例
     * @param location 位置
     * @param task 要执行的任务
     * @param delay 延迟（刻）
     * @param period 周期（刻），0代表执行一次
     */
    public static void runTaskAtLocation(Plugin plugin, Location location, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            try {
                Object scheduler = FOLIA_REGION_SCHEDULER_METHOD.invoke(null);
                if (period > 0) {
                    // 反射调用RegionScheduler的scheduleAtFixedRate方法
                    Method scheduleMethod = scheduler.getClass().getMethod("scheduleAtFixedRate", 
                            Plugin.class, Location.class, Consumer.class, long.class, long.class, TimeUnit.class);
                    scheduleMethod.invoke(scheduler, plugin, location, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, period * 50, TimeUnit.MILLISECONDS);
                } else {
                    // 反射调用RegionScheduler的runDelayed方法
                    Method runMethod = scheduler.getClass().getMethod("runDelayed", 
                            Plugin.class, Location.class, Consumer.class, long.class, TimeUnit.class);
                    runMethod.invoke(scheduler, plugin, location, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("无法在位置调度任务: " + e.getMessage());
                e.printStackTrace();
                
                // 退回到全局任务
                runGlobalTask(plugin, task, delay, period);
            }
        } else {
            // 使用Bukkit调度器
            if (period > 0) {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        }
    }

    /**
     * 对实体执行任务
     * @param plugin 插件实例
     * @param entity 实体
     * @param task 要执行的任务
     * @param delay 延迟（刻）
     * @param period 周期（刻），0代表执行一次
     */
    public static void runTaskForEntity(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            try {
                Object scheduler = FOLIA_ENTITY_SCHEDULER_METHOD.invoke(entity);
                if (period > 0) {
                    // 反射调用EntityScheduler的scheduleAtFixedRate方法
                    Method scheduleMethod = scheduler.getClass().getMethod("scheduleAtFixedRate", 
                            Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                    scheduleMethod.invoke(scheduler, plugin, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, period * 50, TimeUnit.MILLISECONDS);
                } else {
                    // 反射调用EntityScheduler的runDelayed方法
                    Method runMethod = scheduler.getClass().getMethod("runDelayed", 
                            Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    runMethod.invoke(scheduler, plugin, (Consumer<Object>)(context) -> task.run(), 
                            delay * 50, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("无法为实体调度任务: " + e.getMessage());
                e.printStackTrace();
                
                // 退回到位置任务
                runTaskAtLocation(plugin, entity.getLocation(), task, delay, period);
            }
        } else {
            // 使用Bukkit调度器
            if (period > 0) {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        }
    }

    /**
     * 在特定世界生成粒子效果
     * @param world 世界
     * @param particle 粒子类型
     * @param location 位置
     * @param count 粒子数量
     */
    public static void spawnParticle(World world, Particle particle, Location location, int count) {
        if (IS_FOLIA) {
            // 在Folia中，通过位置调度器来生成粒子
            try {
                Object scheduler = FOLIA_REGION_SCHEDULER_METHOD.invoke(null);
                Method runMethod = scheduler.getClass().getMethod("run", 
                        Plugin.class, Location.class, Consumer.class);
                runMethod.invoke(scheduler, Bukkit.getPluginManager().getPlugins()[0], location, 
                        (Consumer<Object>)(context) -> world.spawnParticle(particle, location, count));
            } catch (Exception e) {
                // 如果失败，直接尝试生成粒子
                world.spawnParticle(particle, location, count);
            }
        } else {
            // 在Bukkit中直接生成粒子
            world.spawnParticle(particle, location, count);
        }
    }
} 