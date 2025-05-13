package me.hushu.utils;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.hushu.PowerGem;
import me.hushu.model.ExecuteConfig;

public class EffectUtils {

    private final PowerGem plugin;

    public EffectUtils(PowerGem plugin) {
        this.plugin = plugin;
    }

    /**
     * 执行指定的命令，支持变量替换。
     * @param config 执行配置
     * @param placeholders 占位符
     */
    public void executeCommands(ExecuteConfig config, Map<String, String> placeholders) {
        if (config == null || config.getCommands() == null || config.getCommands().isEmpty()) {
            return;
        }

        for (String commandTemplate : config.getCommands()) {
            String command = commandTemplate;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    command = command.replace(entry.getKey(), entry.getValue());
                }
            }

            final String finalCommand = command;
            // 使用全局调度器执行命令
            SchedulerUtils.runGlobalTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            }, 0, 0);
        }
    }

    /**
     * 全局播放声音
     * @param config 执行配置
     * @param volume 音量
     * @param pitch 音调
     */
    public void playGlobalSound(ExecuteConfig config, float volume, float pitch) {
        if (config == null || config.getSound() == null) {
            return;
        }

        Sound sound;
        try {
            sound = Sound.valueOf(config.getSound());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的声音: " + config.getSound());
            return;
        }

        // 对每个在线玩家播放声音
        for (Player player : Bukkit.getOnlinePlayers()) {
            final Player finalPlayer = player;
            final Sound finalSound = sound;
            // 为玩家调度任务
            SchedulerUtils.runTaskForEntity(plugin, player, () -> {
                finalPlayer.playSound(finalPlayer.getLocation(), finalSound, volume, pitch);
            }, 0, 0);
        }
    }

    /**
     * 在指定位置播放粒子效果
     * @param location 位置
     * @param config 执行配置
     */
    public void playParticle(Location location, ExecuteConfig config) {
        if (config == null || config.getParticle() == null || location == null || location.getWorld() == null) {
            return;
        }

        org.bukkit.Particle particle;
        try {
            particle = org.bukkit.Particle.valueOf(config.getParticle());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的粒子效果: " + config.getParticle());
            return;
        }

        final org.bukkit.Particle finalParticle = particle;
        // 在位置调度粒子效果
        SchedulerUtils.runTaskAtLocation(plugin, location, () -> {
            SchedulerUtils.spawnParticle(location.getWorld(), finalParticle, location, 10);
        }, 0, 0);
    }
}