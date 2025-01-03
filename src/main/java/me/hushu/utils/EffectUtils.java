package me.hushu.utils;

import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import me.hushu.PowerGem;
import me.hushu.model.ExecuteConfig;

public class EffectUtils {

    private final PowerGem plugin;

    public EffectUtils(PowerGem plugin) {
        this.plugin = plugin;
    }

    public void executeCommands(ExecuteConfig execCfg, Map<String, String> placeholders) {
        List<String> commands = execCfg.getCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String cmd : commands) {
            // 依次替换占位符
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String placeholder = entry.getKey();   // 例如 "%player%"
                String value = entry.getValue();       // 例如 "Steve"
                cmd = cmd.replace(placeholder, value);
            }
            // 以控制台身份执行命令
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    public void playGlobalSound(ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("[PowerGem] 无效的音效名称: " + execCfg.getSound());
            }
        }
    }

    public void playLocalSound(Location location, ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                location.getWorld().playSound(location, sound, volume, pitch);
            } catch (Exception ex) {
                plugin.getLogger().warning("[PowerGem] 无效的音效名称: " + execCfg.getSound());
            }
        }
    }

    public void playParticle(Location location, ExecuteConfig execCfg) {
        if (execCfg.getParticle() != null) {
            try {
                Particle particle = Particle.valueOf(execCfg.getParticle());
                location.getWorld().spawnParticle(particle, location, 1);
            } catch (Exception ex) {
                plugin.getLogger().warning("[PowerGem] 无效的粒子名称: " + execCfg.getParticle());
            }
        }
    }
}