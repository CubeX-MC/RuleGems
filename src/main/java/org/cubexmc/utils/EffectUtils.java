package org.cubexmc.utils;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.model.ExecuteConfig;

public class EffectUtils {

    private final RuleGems plugin;

    public EffectUtils(RuleGems plugin) {
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
            String replaced = original;
            // 依次替换占位符（同时兼容 key 与 %key% 两种写法）
            for (Map.Entry<String, String> entry : ph.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null)
                    continue;
                // 如果键不是包裹过的，占位符两种形式都尝试
                if (!(key.startsWith("%") && key.endsWith("%"))) {
                    replaced = replaced.replace("%" + key + "%", value);
                }
                replaced = replaced.replace(key, value);
            }
            // 若仍存在未解析的 %...% 占位符，则跳过执行以避免错误
            if (replaced.matches(".*%[A-Za-z0-9_]+%.*")) {
                plugin.getLogger().log(Level.WARNING,
                        "[RuleGems] Skipping command execution, unresolved placeholder: {0}", replaced);
                continue;
            }
            // 以控制台身份执行命令：统一通过 SchedulerUtil 调度，内部已根据服务端类型与线程处理
            final String cmdToRun = replaced;
            SchedulerUtil.globalRun(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmdToRun), 0L, -1L);
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
                    SchedulerUtil.entityRun(plugin, p, () -> p.playSound(p.getLocation(), sound, volume, pitch), 0L,
                            -1L);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[RuleGems] Invalid sound name: {0}", execCfg.getSound());
            }
        }
    }

    public void playLocalSound(Location location, ExecuteConfig execCfg, float volume, float pitch) {
        if (execCfg == null || location == null || location.getWorld() == null) {
            return;
        }
        if (execCfg.getSound() != null) {
            try {
                Sound sound = Sound.valueOf(execCfg.getSound());
                SchedulerUtil.regionRun(plugin, location, () -> {
                    if (location.getWorld() != null) {
                        location.getWorld().playSound(location, sound, volume, pitch);
                    }
                }, 0L, -1L);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[RuleGems] Invalid sound name: {0}", execCfg.getSound());
            }
        }
    }

    public void playParticle(Location location, ExecuteConfig execCfg) {
        if (execCfg == null || location == null || location.getWorld() == null) {
            return;
        }
        if (execCfg.getParticle() != null) {
            try {
                Particle particle = Particle.valueOf(execCfg.getParticle());
                SchedulerUtil.regionRun(plugin, location, () -> {
                    if (location.getWorld() != null) {
                        try {
                            location.getWorld().spawnParticle(particle, location, 1);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().log(Level.WARNING,
                                    "[RuleGems] Cannot spawn particle {0} because it requires missing BlockData. ({1})",
                                    new Object[] { particle, e.getMessage() });
                        }
                    }
                }, 0L, -1L);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[RuleGems] Invalid particle name: {0}", execCfg.getParticle());
            }
        }
    }
}