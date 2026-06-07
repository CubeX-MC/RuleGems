package org.cubexmc.utils

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cubexmc.scheduler.BukkitImmediateMode
import org.cubexmc.scheduler.CubexScheduler
import org.cubexmc.scheduler.LegacySchedulerAdapter
import java.util.Collections
import java.util.WeakHashMap

/**
 * 调度器工具类，用于兼容 Bukkit 和 Folia 调度器。
 */
object SchedulerUtil {
    private val ADAPTERS: MutableMap<Plugin, LegacySchedulerAdapter> = Collections.synchronizedMap(WeakHashMap())

    @JvmStatic
    fun isFolia(): Boolean = CubexScheduler.detectFolia()

    @JvmStatic
    fun cancelTask(task: Any?) {
        LegacySchedulerAdapter.cancelTaskHandle(task)
    }

    @JvmStatic
    fun globalRun(plugin: Plugin, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).globalRun(task, delay, period)

    @JvmStatic
    fun entityRun(plugin: Plugin, entity: Entity, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).entityRun(entity, task, delay, period)

    @JvmStatic
    fun regionRun(plugin: Plugin, location: Location, task: Runnable, delay: Long, period: Long): Any? =
        adapter(plugin).regionRun(location, task, delay, period)

    @JvmStatic
    fun asyncRun(plugin: Plugin, task: Runnable, delay: Long) {
        adapter(plugin).asyncRun(task, delay)
    }

    @JvmStatic
    fun safeTeleport(plugin: Plugin, player: Player, dest: Location) {
        adapter(plugin).safeTeleport(player, dest)
    }

    private fun adapter(plugin: Plugin): LegacySchedulerAdapter =
        ADAPTERS.computeIfAbsent(plugin) { createAdapter(it) }

    private fun createAdapter(plugin: Plugin): LegacySchedulerAdapter =
        LegacySchedulerAdapter.builder(plugin)
            .immediateMode(BukkitImmediateMode.INLINE_WHEN_PRIMARY_THREAD)
            .trackTasksForCancelAll(false)
            .tickAccessEnabled(false)
            .build()
}
