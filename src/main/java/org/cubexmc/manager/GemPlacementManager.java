package org.cubexmc.manager;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.utils.SchedulerUtil;

/**
 * 宝石放置管理器 - 负责宝石的放置、散落、逃逸
 * 包括：放置逻辑、随机散落、逃逸机制、粒子效果等
 */
public class GemPlacementManager {

    private final RuleGems plugin;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;
    private final GemStateManager stateManager;

    // 逃逸任务
    private final Map<UUID, Object> gemEscapeTasks = new HashMap<>();

    public GemPlacementManager(RuleGems plugin, ConfigManager configManager, 
                               LanguageManager languageManager, GemStateManager stateManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.languageManager = languageManager;
        this.stateManager = stateManager;
    }

    // ==================== 放置逻辑 ====================

    /**
     * 放置宝石到指定位置
     */
    public void placeRuleGem(Location loc, UUID gemId) {
        if (loc == null || gemId == null) return;
        Location blockLoc = loc.getBlock().getLocation();
        
        Material mat = stateManager.getGemMaterial(gemId);
        stateManager.getLocationToGemUuid().put(blockLoc, gemId);
        stateManager.getGemUuidToLocation().put(gemId, blockLoc);
        
        SchedulerUtil.regionRun(plugin, blockLoc, () -> {
            blockLoc.getBlock().setType(mat);
        }, 0L, -1L);
        
        // 调度逃逸
        scheduleEscape(gemId);
    }

    /**
     * 内部放置（带完整检查）
     */
    public void placeRuleGemInternal(Location loc, UUID gemId) {
        if (loc == null || gemId == null) return;
        Location blockLoc = loc.getBlock().getLocation();
        
        Material mat = stateManager.getGemMaterial(gemId);
        stateManager.getLocationToGemUuid().put(blockLoc, gemId);
        stateManager.getGemUuidToLocation().put(gemId, blockLoc);
        
        SchedulerUtil.regionRun(plugin, blockLoc, () -> {
            Block b = blockLoc.getBlock();
            if (!b.getChunk().isLoaded()) {
                b.getChunk().load();
            }
            b.setType(mat);
        }, 0L, -1L);
        
        // 调度逃逸
        scheduleEscape(gemId);
    }

    /**
     * 取消放置宝石
     */
    public void unplaceRuleGem(Location loc, UUID gemId) {
        if (loc == null || gemId == null) return;
        Location blockLoc = loc.getBlock().getLocation();
        
        // 取消逃逸
        cancelEscape(gemId);
        
        stateManager.getLocationToGemUuid().remove(blockLoc);
        stateManager.getGemUuidToLocation().remove(gemId);
        
        SchedulerUtil.regionRun(plugin, blockLoc, () -> {
            blockLoc.getBlock().setType(Material.AIR);
        }, 0L, -1L);
    }

    /**
     * 强制放置宝石到目标位置
     */
    public void forcePlaceGem(UUID gemId, Location target, Player holder) {
        if (gemId == null || target == null) return;
        
        final Location oldLoc = stateManager.findLocationByGemId(gemId);
        final Location base = target.clone();
        
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null) return;
            WorldBorder border = world.getWorldBorder();
            Location t = base.getBlock().getLocation();
            if (!border.isInside(t) || t.getBlockY() < world.getMinHeight() || t.getBlockY() > world.getMaxHeight()) {
                return;
            }
            
            Material mat = stateManager.getGemMaterial(gemId);
            if (stateManager.isSupportRequired(mat) && !stateManager.hasBlockSupport(t)) {
                return;
            }
            
            try {
                if (!t.getChunk().isLoaded()) t.getChunk().load();
            } catch (Throwable ignored) {}
            
            t.getBlock().setType(mat);
            stateManager.getLocationToGemUuid().put(t, gemId);
            stateManager.getGemUuidToLocation().put(gemId, t);
            
            // 清理旧位置
            if (oldLoc != null) {
                unplaceRuleGem(oldLoc, gemId);
            }
            
            // 调度逃逸
            scheduleEscape(gemId);
        }, 0L, -1L);
    }

    // ==================== 随机放置 ====================

    /**
     * 随机放置宝石
     */
    public void randomPlaceGem(UUID gemId) {
        if (gemId == null) return;
        
        String gemKey = stateManager.getGemKey(gemId);
        Location[] range = getRandomPlaceRange(gemKey);
        Location corner1 = range[0];
        Location corner2 = range[1];
        
        if (corner1 == null || corner2 == null) return;
        
        World world = corner1.getWorld();
        if (world == null) return;
        
        scheduleRandomAttempt(gemId, corner1, corner2, world, 20);
    }

    /**
     * 调度随机放置尝试
     */
    private void scheduleRandomAttempt(UUID gemId, Location c1, Location c2, World world, int attemptsLeft) {
        if (attemptsLeft <= 0) return;
        
        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());
        
        Random rand = new Random();
        int x = minX + rand.nextInt(Math.max(1, maxX - minX + 1));
        int y = minY + rand.nextInt(Math.max(1, maxY - minY + 1));
        int z = minZ + rand.nextInt(Math.max(1, maxZ - minZ + 1));
        
        Location candidate = new Location(world, x, y, z);
        
        SchedulerUtil.regionRun(plugin, candidate, () -> {
            if (!candidate.getChunk().isLoaded()) {
                candidate.getChunk().load();
            }
            
            WorldBorder border = world.getWorldBorder();
            if (!border.isInside(candidate)) {
                SchedulerUtil.globalRun(plugin, 
                    () -> scheduleRandomAttempt(gemId, c1, c2, world, attemptsLeft - 1), 1L, -1L);
                return;
            }
            
            Material mat = stateManager.getGemMaterial(gemId);
            if (stateManager.isSupportRequired(mat) && !stateManager.hasBlockSupport(candidate)) {
                SchedulerUtil.globalRun(plugin, 
                    () -> scheduleRandomAttempt(gemId, c1, c2, world, attemptsLeft - 1), 1L, -1L);
                return;
            }
            
            Block b = candidate.getBlock();
            if (b.getType() != Material.AIR) {
                SchedulerUtil.globalRun(plugin, 
                    () -> scheduleRandomAttempt(gemId, c1, c2, world, attemptsLeft - 1), 1L, -1L);
                return;
            }
            
            placeRuleGemInternal(candidate, gemId);
        }, 0L, -1L);
    }

    /**
     * 获取宝石的随机放置范围
     */
    private Location[] getRandomPlaceRange(String gemKey) {
        if (gemKey != null) {
            GemDefinition def = stateManager.findGemDefinition(gemKey);
            if (def != null) {
                Location c1 = def.getRandomPlaceCorner1();
                Location c2 = def.getRandomPlaceCorner2();
                if (c1 != null && c2 != null) {
                    return new Location[]{c1, c2};
                }
            }
        }
        return new Location[]{
            configManager.getRandomPlaceCorner1(),
            configManager.getRandomPlaceCorner2()
        };
    }

    // ==================== 散落逻辑 ====================

    /**
     * 散落所有宝石
     */
    public void scatterGems() {
        cancelAllEscapeTasks();
        
        // 清理所有放置的宝石
        for (Map.Entry<Location, UUID> entry : 
                new HashMap<>(stateManager.getLocationToGemUuid()).entrySet()) {
            Location loc = entry.getKey();
            SchedulerUtil.regionRun(plugin, loc, () -> {
                loc.getBlock().setType(Material.AIR);
            }, 0L, -1L);
        }
        stateManager.getLocationToGemUuid().clear();
        stateManager.getGemUuidToLocation().clear();
        stateManager.getGemUuidToHolder().clear();
        
        // 重新随机放置
        for (UUID gemId : stateManager.getAllGemUuids()) {
            randomPlaceGem(gemId);
        }
    }

    // ==================== 逃逸机制 ====================

    /**
     * 调度宝石逃逸任务
     */
    public void scheduleEscape(UUID gemId) {
        if (!configManager.isGemEscapeEnabled()) return;
        if (gemId == null) return;
        
        cancelEscape(gemId);
        
        long minTicks = configManager.getGemEscapeMinIntervalTicks();
        long maxTicks = configManager.getGemEscapeMaxIntervalTicks();
        Random rand = new Random();
        long range = Math.max(1L, maxTicks - minTicks);
        long delayTicks = minTicks + (long) (rand.nextDouble() * range);
        
        Object task = SchedulerUtil.globalRun(plugin, () -> triggerEscape(gemId), delayTicks, -1L);
        if (task != null) {
            gemEscapeTasks.put(gemId, task);
        }
    }

    /**
     * 取消宝石逃逸任务
     */
    public void cancelEscape(UUID gemId) {
        if (gemId == null) return;
        Object task = gemEscapeTasks.remove(gemId);
        if (task != null) {
            SchedulerUtil.cancelTask(task);
        }
    }

    /**
     * 取消所有逃逸任务
     */
    public void cancelAllEscapeTasks() {
        for (Object task : gemEscapeTasks.values()) {
            if (task != null) {
                SchedulerUtil.cancelTask(task);
            }
        }
        gemEscapeTasks.clear();
    }

    /**
     * 初始化所有已放置宝石的逃逸任务
     */
    public void initializeEscapeTasks() {
        if (!configManager.isGemEscapeEnabled()) return;
        for (UUID gemId : stateManager.getGemUuidToLocation().keySet()) {
            scheduleEscape(gemId);
        }
    }

    /**
     * 触发宝石逃逸
     */
    private void triggerEscape(UUID gemId) {
        if (gemId == null) return;
        gemEscapeTasks.remove(gemId);
        
        Location oldLocation = stateManager.getGemUuidToLocation().get(gemId);
        if (oldLocation == null) return;
        
        playEscapeEffects(oldLocation, gemId);
        unplaceRuleGem(oldLocation, gemId);
        randomPlaceGem(gemId);
        
        if (configManager.isGemEscapeBroadcast()) {
            broadcastEscape(gemId);
        }
    }

    /**
     * 播放逃逸特效
     */
    private void playEscapeEffects(Location location, UUID gemId) {
        if (location == null || location.getWorld() == null) return;
        
        final Location loc = location.clone().add(0.5, 0.5, 0.5);
        SchedulerUtil.regionRun(plugin, loc, () -> {
            World world = loc.getWorld();
            if (world == null) return;
            
            String particleStr = configManager.getGemEscapeParticle();
            if (particleStr != null && !particleStr.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleStr.toUpperCase());
                    world.spawnParticle(particle, loc, 50, 0.5, 0.5, 0.5, 0.1);
                } catch (IllegalArgumentException ignored) {}
            }
            
            String soundStr = configManager.getGemEscapeSound();
            if (soundStr != null && !soundStr.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundStr.toUpperCase());
                    world.playSound(loc, sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {}
            }
        }, 0L, -1L);
    }

    /**
     * 广播逃逸消息
     */
    private void broadcastEscape(UUID gemId) {
        String gemKey = stateManager.getGemUuidToKey().getOrDefault(gemId, "unknown");
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        String gemName = def != null ? def.getDisplayName() : gemKey;
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("gem_name", gemName);
        placeholders.put("gem_key", gemKey);
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            languageManager.sendMessage(player, "gem_escape.broadcast", placeholders);
        }
        languageManager.logMessage("gem_escape", placeholders);
    }

    // ==================== 祭坛管理 ====================

    /**
     * 设置宝石祭坛位置
     */
    public void setGemAltarLocation(String gemKey, Location location) {
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def != null) {
            def.setAltarLocation(location);
            saveGemAltarToConfig(gemKey, location);
        }
    }

    /**
     * 移除宝石祭坛位置
     */
    public void removeGemAltarLocation(String gemKey) {
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def != null) {
            def.setAltarLocation(null);
            removeGemAltarFromConfig(gemKey);
        }
    }

    /**
     * 保存祭坛位置到配置
     */
    private void saveGemAltarToConfig(String gemKey, Location loc) {
        File gemsFolder = new File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) return;
        
        File[] files = gemsFolder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                if (yaml.contains(gemKey)) {
                    yaml.set(gemKey + ".altar.world", loc.getWorld().getName());
                    yaml.set(gemKey + ".altar.x", loc.getBlockX());
                    yaml.set(gemKey + ".altar.y", loc.getBlockY());
                    yaml.set(gemKey + ".altar.z", loc.getBlockZ());
                    try {
                        yaml.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("保存祭坛配置失败: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    /**
     * 从配置移除祭坛位置
     */
    private void removeGemAltarFromConfig(String gemKey) {
        File gemsFolder = new File(plugin.getDataFolder(), "gems");
        if (!gemsFolder.exists()) return;
        
        File[] files = gemsFolder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                if (yaml.contains(gemKey)) {
                    yaml.set(gemKey + ".altar", null);
                    try {
                        yaml.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("移除祭坛配置失败: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    // ==================== 粒子效果 ====================

    /**
     * 启动粒子效果任务
     */
    public void startParticleEffectTask(Particle defaultParticle) {
        SchedulerUtil.globalRun(plugin, () -> {
            for (Location loc : stateManager.getLocationToGemUuid().keySet()) {
                Location target = loc;
                SchedulerUtil.regionRun(plugin, target, () -> {
                    World world = target.getWorld();
                    if (world == null) return;
                    
                    UUID id = stateManager.getLocationToGemUuid().get(target);
                    GemDefinition def = id != null ? 
                        stateManager.findGemDefinition(stateManager.getGemUuidToKey().get(id)) : null;
                    Particle p = def != null && def.getParticle() != null ? def.getParticle() : defaultParticle;
                    world.spawnParticle(p, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 1);
                }, 0L, -1L);
            }
        }, 0L, 20L);
    }

    // ==================== 初始化放置方块 ====================

    /**
     * 初始化已放置宝石的方块材质
     */
    public void initializePlacedGemBlocks() {
        for (Map.Entry<UUID, Location> entry : stateManager.getGemUuidToLocation().entrySet()) {
            UUID gemId = entry.getKey();
            Location loc = entry.getValue();
            if (loc == null || loc.getWorld() == null) continue;
            
            String key = stateManager.getGemUuidToKey().get(gemId);
            Material mat = Material.RED_STAINED_GLASS;
            if (key != null) {
                GemDefinition def = stateManager.findGemDefinition(key);
                if (def != null && def.getMaterial() != null) mat = def.getMaterial();
            }
            
            final Material m = mat;
            final Location f = loc;
            SchedulerUtil.regionRun(plugin, f, () -> {
                try {
                    if (!f.getChunk().isLoaded()) f.getChunk().load();
                } catch (Throwable ignored) {}
                f.getBlock().setType(m);
            }, 0L, -1L);
        }
    }

    /**
     * 检查放置位置是否为祭坛
     */
    public String checkPlaceRedeem(Location placedLoc, String gemKey) {
        if (!configManager.isPlaceRedeemEnabled()) return null;
        
        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def == null) return null;
        
        Location altar = def.getAltarLocation();
        if (altar == null) return null;
        if (altar.getWorld() == null || placedLoc.getWorld() == null) return null;
        if (!altar.getWorld().getName().equals(placedLoc.getWorld().getName())) return null;
        
        int radius = configManager.getPlaceRedeemRadius();
        if (Math.abs(placedLoc.getBlockX() - altar.getBlockX()) <= radius &&
            Math.abs(placedLoc.getBlockY() - altar.getBlockY()) <= radius &&
            Math.abs(placedLoc.getBlockZ() - altar.getBlockZ()) <= radius) {
            return gemKey;
        }
        return null;
    }
}

