package org.cubexmc.utils

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.model.ExecuteConfig
import java.util.logging.Level

class EffectUtils(private val plugin: RuleGems) {
    fun executeCommands(execCfg: ExecuteConfig?, placeholders: Map<String, String>?) {
        if (execCfg == null) {
            return
        }
        val commands = execCfg.commands
        if (commands.isNullOrEmpty()) {
            return
        }
        val ph = placeholders ?: emptyMap()
        for (original in commands) {
            if (original == null || original.trim().isEmpty()) {
                continue
            }
            var replaced = original
            // 依次替换占位符（同时兼容 key 与 %key% 两种写法）
            for ((key, value) in ph) {
                if (key.isEmpty() || value.isEmpty()) {
                    continue
                }
                // 如果键不是包裹过的，占位符两种形式都尝试
                if (!(key.startsWith("%") && key.endsWith("%"))) {
                    replaced = replaced.replace("%$key%", value)
                }
                replaced = replaced.replace(key, value)
            }
            // 若仍存在未解析的 %...% 占位符，则跳过执行以避免错误
            if (replaced.matches(Regex(".*%[A-Za-z0-9_]+%.*"))) {
                plugin.logger.log(
                    Level.WARNING,
                    "[RuleGems] Skipping command execution, unresolved placeholder: {0}",
                    replaced,
                )
                continue
            }
            // 以控制台身份执行命令：统一通过 SchedulerUtil 调度，内部已根据服务端类型与线程处理
            val cmdToRun = replaced
            SchedulerUtil.globalRun(plugin, { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmdToRun) }, 0L, -1L)
        }
    }

    fun playGlobalSound(execCfg: ExecuteConfig?, volume: Float, pitch: Float) {
        if (execCfg == null) {
            return
        }
        if (execCfg.sound != null) {
            try {
                val sound = Sound.valueOf(execCfg.sound)
                for (player in Bukkit.getOnlinePlayers()) {
                    SchedulerUtil.entityRun(plugin, player, { player.playSound(player.location, sound, volume, pitch) }, 0L, -1L)
                }
            } catch (_: Exception) {
                plugin.logger.log(Level.WARNING, "[RuleGems] Invalid sound name: {0}", execCfg.sound)
            }
        }
    }

    fun playLocalSound(location: Location?, soundName: String?, volume: Float, pitch: Float) {
        if (location == null || location.world == null || soundName == null) {
            return
        }
        try {
            val sound = Sound.valueOf(soundName)
            SchedulerUtil.regionRun(
                plugin,
                location,
                {
                    val world = location.world
                    if (world != null) {
                        world.playSound(location, sound, volume, pitch)
                    }
                },
                0L,
                -1L,
            )
        } catch (_: Exception) {
            plugin.logger.log(Level.WARNING, "[RuleGems] Invalid sound name: {0}", soundName)
        }
    }

    fun playLocalSound(location: Location?, execCfg: ExecuteConfig?, volume: Float, pitch: Float) {
        if (execCfg == null || location == null || location.world == null) {
            return
        }
        playLocalSound(location, execCfg.sound, volume, pitch)
    }

    fun playParticle(location: Location?, execCfg: ExecuteConfig?) {
        if (execCfg == null || location == null || location.world == null) {
            return
        }
        if (execCfg.particle != null) {
            try {
                val particle = Particle.valueOf(execCfg.particle)
                SchedulerUtil.regionRun(
                    plugin,
                    location,
                    {
                        val world = location.world
                        if (world != null) {
                            try {
                                world.spawnParticle(particle, location, 1)
                            } catch (e: IllegalArgumentException) {
                                plugin.logger.log(
                                    Level.WARNING,
                                    "[RuleGems] Cannot spawn particle {0} because it requires missing BlockData. ({1})",
                                    arrayOf(particle, e.message),
                                )
                            }
                        }
                    },
                    0L,
                    -1L,
                )
            } catch (_: Exception) {
                plugin.logger.log(Level.WARNING, "[RuleGems] Invalid particle name: {0}", execCfg.particle)
            }
        }
    }

    fun sendActionBar(player: Player?, text: String?) {
        if (player == null || text.isNullOrEmpty()) {
            return
        }
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(text))
        } catch (_: Throwable) {
            // fallback for older/incompatible versions
            player.sendMessage(text)
        }
    }
}
