package me.hushu.utils;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

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
        if (execCfg == null) {
            return;
        }
        List<String> commands = execCfg.getCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }
        Map<String, String> ph = (placeholders == null) ? java.util.Collections.emptyMap() : placeholders;
        for (String original : commands) {
            if (original == null || original.trim().isEmpty()) {
                continue;
            }
            String cmd = original;
            // 依次替换占位符（同时兼容 key 与 %key% 两种写法）
            for (Map.Entry<String, String> entry : ph.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) continue;
                // 如果键不是包裹过的，占位符两种形式都尝试
                if (!(key.startsWith("%") && key.endsWith("%"))) {
                    cmd = cmd.replace("%" + key + "%", value);
                }
                cmd = cmd.replace(key, value);
            }
            // 若仍存在未解析的 %...% 占位符，则跳过执行以避免错误
            if (cmd.matches(".*%[A-Za-z0-9_]+%.*")) {
                plugin.getLogger().log(Level.WARNING, "[PowerGem] 跳过执行命令，未解析占位符: {0}", cmd);
                continue;
            }
            // 以控制台身份执行命令
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    public void playGlobalSound(ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg == null) {
            return;
        }
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[PowerGem] 无效的音效名称: {0}", execCfg.getSound());
            }
        }
    }

    public void playLocalSound(Location location, ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg == null) {
            return;
        }
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                location.getWorld().playSound(location, sound, volume, pitch);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[PowerGem] 无效的音效名称: {0}", execCfg.getSound());
            }
        }
    }

    public void playParticle(Location location, ExecuteConfig execCfg) {
        if (execCfg == null) {
            return;
        }
        if (execCfg.getParticle() != null) {
            try {
                Particle particle = Particle.valueOf(execCfg.getParticle());
                location.getWorld().spawnParticle(particle, location, 1);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[PowerGem] 无效的粒子名称: {0}", execCfg.getParticle());
            }
        }
    }
}