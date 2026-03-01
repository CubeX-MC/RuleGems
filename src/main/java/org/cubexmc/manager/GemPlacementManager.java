package org.cubexmc.manager;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import org.cubexmc.RuleGems;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;

/**
 * 宝石放置管理器 - 负责宝石的放置、散落、逃逸
 * 包括：放置逻辑、随机散落、逃逸机制、粒子效果等
 */
public class GemPlacementManager {

    /** Maximum vertical blocks to search upward when placing a gem. */
    private static final int MAX_VERTICAL_SEARCH = 6;
    /** Maximum random placement attempts before falling back to center. */
    private static final int MAX_RANDOM_ATTEMPTS = 12;
    /** Proximity detection range (blocks) for gem sound notification. */
    private static final double PROXIMITY_DETECTION_RANGE = 16.0;

    private final RuleGems plugin;
    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;
    private final GemStateManager stateManager;
    private EffectUtils effectUtils;

    // 逃逸任务
    private final Map<UUID, Object> gemEscapeTasks = new ConcurrentHashMap<>();

    public GemPlacementManager(RuleGems plugin, GemDefinitionParser gemParser,
            GameplayConfig gameplayConfig,
            LanguageManager languageManager, GemStateManager stateManager) {
        this.plugin = plugin;
        this.gemParser = gemParser;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
        this.stateManager = stateManager;
    }

    /**
     * 设置 EffectUtils（延迟注入，避免循环依赖）
     */
    public void setEffectUtils(EffectUtils effectUtils) {
        this.effectUtils = effectUtils;
    }

    // ==================== 状态访问器 ====================

    public Map<UUID, Object> getGemEscapeTasks() {
        return gemEscapeTasks;
    }

    // ==================== 放置逻辑 ====================

    /**
     * 放置宝石到指定位置（带限制检查）
     */
    public void placeRuleGem(Location loc, UUID gemId) {
        placeRuleGemInternal(loc, gemId, false);
    }

    /**
     * 内部放置（带完整检查：数量限制、垂直搜索、WorldBorder 校验、回退机制）
     */
    private void placeRuleGemInternal(Location loc, UUID gemId, boolean ignoreLimit) {
        if (loc == null)
            return;
        if (!ignoreLimit && stateManager.getTotalGemCount() >= gemParser.getRequiredCount()) {
            plugin.getLogger().info("Gem limit reached, skipping placement");
            return;
        }
        final Location base = loc.clone();
        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null)
                return;
            WorldBorder border = world.getWorldBorder();
            Location target = base.getBlock().getLocation();
            // 垂直向上寻找空气（最多尝试 MAX_VERTICAL_SEARCH 格）
            int tries = 0;
            while (tries < MAX_VERTICAL_SEARCH && target.getBlock().getType().isSolid()) {
                target.add(0, 1, 0);
                tries++;
            }
            if (!border.isInside(target) || target.getBlockY() < world.getMinHeight()
                    || target.getBlockY() > world.getMaxHeight()) {
                randomPlaceGem(gemId);
                return;
            }
            Material mat = stateManager.getGemMaterial(gemId);
            target.getBlock().setType(mat);
            stateManager.bindPlacedGem(target, gemId);
            scheduleEscape(gemId);
        }, 0L, -1L);
    }

    /**
     * 取消放置宝石
     */
    public void unplaceRuleGem(Location loc, UUID gemId) {
        if (loc == null)
            return;
        final Location fLoc = loc.getBlock().getLocation();
        SchedulerUtil.regionRun(plugin, fLoc, () -> {
            fLoc.getBlock().setType(Material.AIR);
            stateManager.unbindPlacedGem(fLoc, gemId);
        }, 0L, -1L);
    }

    /**
     * 强制放置宝石到目标位置
     */
    public void forcePlaceGem(UUID gemId, Location target, Player holder) {
        if (gemId == null || target == null)
            return;

        final Location oldLoc = stateManager.findLocationByGemId(gemId);
        final Location base = target.clone();

        SchedulerUtil.regionRun(plugin, base, () -> {
            World world = base.getWorld();
            if (world == null)
                return;
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
                if (!t.getChunk().isLoaded())
                    t.getChunk().load();
            } catch (Throwable e) {
                plugin.getLogger().fine("Failed to load chunk for gem placement: " + e.getMessage());
            }

            t.getBlock().setType(mat);
            stateManager.bindPlacedGem(t, gemId);

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
     * 随机放置宝石（三参数版本，确保 gemKey 已分配后调度）
     */
    public void randomPlaceGem(UUID gemId, Location corner1, Location corner2) {
        stateManager.ensureGemKeyAssigned(gemId);
        scheduleRandomAttempt(gemId, corner1, corner2, MAX_RANDOM_ATTEMPTS);
    }

    /**
     * 随机放置宝石（自动使用宝石特定或全局默认范围）
     */
    public void randomPlaceGem(UUID gemId) {
        if (gemId == null)
            return;
        Location[] range = getGemPlaceRange(gemId);
        if (range != null) {
            randomPlaceGem(gemId, range[0], range[1]);
        } else {
            plugin.getLogger().warning(
                    "Cannot place gem " + gemId + ": no spawn range configured, falling back to overworld spawn");
            World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (defaultWorld != null) {
                Location spawnLoc = defaultWorld.getSpawnLocation();
                placeRuleGem(spawnLoc, gemId);
            } else {
                plugin.getLogger()
                        .severe("Cannot place gem " + gemId + ": no available world! Gem will be in unknown state");
            }
        }
    }

    /**
     * Folia 安全的随机放置尝试：每次选择候选坐标，在区域线程中计算最高地面并放置
     */
    private void scheduleRandomAttempt(UUID gemId, Location corner1, Location corner2, int attemptsLeft) {
        if (corner1 == null || corner2 == null)
            return;
        if (corner1.getWorld() != corner2.getWorld())
            return;

        if (attemptsLeft <= 0) {
            plugin.getLogger().warning("Random placement failed for gem " + gemId + ", falling back to range center");
            int centerX = (corner1.getBlockX() + corner2.getBlockX()) / 2;
            int centerZ = (corner1.getBlockZ() + corner2.getBlockZ()) / 2;
            World world = corner1.getWorld();
            int y = world.getHighestBlockYAt(centerX, centerZ) + 1;
            Location fallback = new Location(world, centerX, y, centerZ);
            placeRuleGem(fallback, gemId);
            return;
        }

        World world = corner1.getWorld();
        Random rand = new Random();
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
        int x = rand.nextInt(maxX - minX + 1) + minX;
        int z = rand.nextInt(maxZ - minZ + 1) + minZ;
        final Location candidate = new Location(world, x, world.getMinHeight() + 1, z);
        SchedulerUtil.regionRun(plugin, candidate, () -> {
            try {
                int y = world.getHighestBlockYAt(x, z) + 1;
                Location place = new Location(world, x, y, z);
                WorldBorder border = world.getWorldBorder();
                if (!border.isInside(place)) {
                    scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
                    return;
                }
                placeRuleGem(place, gemId);
            } catch (Throwable t) {
                scheduleRandomAttempt(gemId, corner1, corner2, attemptsLeft - 1);
            }
        }, 0L, -1L);
    }

    /**
     * 获取宝石的随机生成范围（优先使用宝石特定的范围，否则使用全局默认）
     */
    private Location[] getGemPlaceRange(UUID gemId) {
        String gemKey = stateManager.getGemKey(gemId);
        if (gemKey != null) {
            for (GemDefinition def : gemParser.getGemDefinitions()) {
                if (def.getGemKey().equals(gemKey)) {
                    Location c1 = def.getRandomPlaceCorner1();
                    Location c2 = def.getRandomPlaceCorner2();
                    if (c1 != null && c2 != null) {
                        return new Location[] { c1, c2 };
                    }
                    break;
                }
            }
        }
        Location defaultC1 = gameplayConfig.getRandomPlaceCorner1();
        Location defaultC2 = gameplayConfig.getRandomPlaceCorner2();
        if (defaultC1 != null && defaultC2 != null) {
            return new Location[] { defaultC1, defaultC2 };
        }
        return null;
    }

    // ==================== 散落逻辑 ====================

    /**
     * 散落所有宝石
     */
    public void scatterGems() {
        cancelAllEscapeTasks();

        // 清理所有放置的宝石
        for (Map.Entry<Location, UUID> entry : stateManager.snapshotPlacedGems().entrySet()) {
            Location loc = entry.getKey();
            SchedulerUtil.regionRun(plugin, loc, () -> {
                loc.getBlock().setType(Material.AIR);
            }, 0L, -1L);
        }
        stateManager.clearPlacedMappings();
        stateManager.clearHolderMappings();

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
        if (!gameplayConfig.isGemEscapeEnabled())
            return;
        if (gemId == null)
            return;

        cancelEscape(gemId);

        long minTicks = gameplayConfig.getGemEscapeMinIntervalTicks();
        long maxTicks = gameplayConfig.getGemEscapeMaxIntervalTicks();
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
        if (gemId == null)
            return;
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
        if (!gameplayConfig.isGemEscapeEnabled())
            return;
        for (UUID gemId : stateManager.getGemUuidToLocation().keySet()) {
            scheduleEscape(gemId);
        }
    }

    /**
     * 触发宝石逃逸
     */
    private void triggerEscape(UUID gemId) {
        if (gemId == null)
            return;
        gemEscapeTasks.remove(gemId);

        Location oldLocation = stateManager.getGemUuidToLocation().get(gemId);
        if (oldLocation == null)
            return;

        playEscapeEffects(oldLocation, gemId);
        unplaceRuleGem(oldLocation, gemId);
        randomPlaceGem(gemId);

        if (gameplayConfig.isGemEscapeBroadcast()) {
            broadcastEscape(gemId);
        }
    }

    /**
     * 播放逃逸特效
     */
    private void playEscapeEffects(Location location, UUID gemId) {
        if (location == null || location.getWorld() == null)
            return;

        final Location loc = location.clone().add(0.5, 0.5, 0.5);
        SchedulerUtil.regionRun(plugin, loc, () -> {
            World world = loc.getWorld();
            if (world == null)
                return;

            String particleStr = gameplayConfig.getGemEscapeParticle();
            if (particleStr != null && !particleStr.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleStr.toUpperCase());
                    try {
                        world.spawnParticle(particle, loc, 50, 0.5, 0.5, 0.5, 0.1);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning(
                                "Could not spawn escape particle " + particle + ": requires data like BlockData.");
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger()
                            .warning("Invalid gem escape particle type '" + particleStr + "': " + e.getMessage());
                }
            }

            String soundStr = gameplayConfig.getGemEscapeSound();
            if (soundStr != null && !soundStr.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundStr.toUpperCase());
                    world.playSound(loc, sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid gem escape sound type '" + soundStr + "': " + e.getMessage());
                }
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
        if (!gemsFolder.exists())
            return;

        File[] files = gemsFolder.listFiles();
        if (files == null)
            return;

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
                        plugin.getLogger().warning("Failed to save altar config: " + e.getMessage());
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
        if (!gemsFolder.exists())
            return;

        File[] files = gemsFolder.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                if (yaml.contains(gemKey)) {
                    yaml.set(gemKey + ".altar", null);
                    try {
                        yaml.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to remove altar config: " + e.getMessage());
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
                    if (world == null)
                        return;

                    UUID id = stateManager.getLocationToGemUuid().get(target);
                    GemDefinition def = id != null
                            ? stateManager.findGemDefinition(stateManager.getGemUuidToKey().get(id))
                            : null;
                    Particle p = def != null && def.getParticle() != null ? def.getParticle() : defaultParticle;
                    try {
                        world.spawnParticle(p, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 1);
                    } catch (IllegalArgumentException e) {
                        // Silently catch to avoid spamming the console on missing BlockData for
                        // continuous particles
                    }
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
            if (loc == null || loc.getWorld() == null)
                continue;

            String key = stateManager.getGemUuidToKey().get(gemId);
            Material mat = Material.RED_STAINED_GLASS;
            if (key != null) {
                GemDefinition def = stateManager.findGemDefinition(key);
                if (def != null && def.getMaterial() != null)
                    mat = def.getMaterial();
            }

            final Material m = mat;
            final Location f = loc;
            SchedulerUtil.regionRun(plugin, f, () -> {
                try {
                    if (!f.getChunk().isLoaded())
                        f.getChunk().load();
                } catch (Throwable e) {
                    plugin.getLogger().fine("Failed to load chunk for gem block restoration: " + e.getMessage());
                }
                f.getBlock().setType(m);
            }, 0L, -1L);
        }
    }

    /**
     * 检查放置位置是否为祭坛
     */
    public String checkPlaceRedeem(Location placedLoc, String gemKey) {
        if (!gameplayConfig.isPlaceRedeemEnabled())
            return null;

        GemDefinition def = stateManager.findGemDefinition(gemKey);
        if (def == null)
            return null;

        Location altar = def.getAltarLocation();
        if (altar == null)
            return null;
        if (altar.getWorld() == null || placedLoc.getWorld() == null)
            return null;
        if (!altar.getWorld().getName().equals(placedLoc.getWorld().getName()))
            return null;

        int radius = gameplayConfig.getPlaceRedeemRadius();
        if (Math.abs(placedLoc.getBlockX() - altar.getBlockX()) <= radius &&
                Math.abs(placedLoc.getBlockY() - altar.getBlockY()) <= radius &&
                Math.abs(placedLoc.getBlockZ() - altar.getBlockZ()) <= radius) {
            return gemKey;
        }
        return null;
    }

    // ==================== 散落特效 ====================

    /**
     * 触发散落特效（优先使用宝石特定配置，回退到全局配置）
     */
    public void triggerScatterEffects(UUID gemId, Location location, String playerName) {
        triggerScatterEffects(gemId, location, playerName, true);
    }

    /**
     * 触发散落特效
     */
    public void triggerScatterEffects(UUID gemId, Location location, String playerName, boolean allowFallback) {
        if (location == null || location.getWorld() == null || effectUtils == null)
            return;
        GemDefinition definition = stateManager.findGemDefinition(stateManager.getGemUuidToKey().get(gemId));
        Map<String, String> placeholders = playerName == null
                ? Collections.emptyMap()
                : Collections.singletonMap("%player%", playerName);
        if (definition != null && definition.getOnScatter() != null) {
            effectUtils.executeCommands(definition.getOnScatter(), placeholders);
            effectUtils.playLocalSound(location, definition.getOnScatter(), 1.0f, 1.0f);
            effectUtils.playParticle(location, definition.getOnScatter());
            return;
        }
        if (allowFallback) {
            ExecuteConfig fallback = gameplayConfig.getGemScatterExecute();
            effectUtils.playLocalSound(location, fallback, 1.0f, 1.0f);
            effectUtils.playParticle(location, fallback);
        }
    }

    // ==================== 放置兑换特效 ====================

    /**
     * 播放放置兑换特效（包括信标光束）
     */
    public void playPlaceRedeemEffects(Location location) {
        if (location == null || location.getWorld() == null)
            return;
        final Location loc = location.clone().add(0.5, 0.5, 0.5);
        SchedulerUtil.regionRun(plugin, loc, () -> {
            World world = loc.getWorld();
            if (world == null)
                return;
            String particleStr = gameplayConfig.getPlaceRedeemParticle();
            if (particleStr != null && !particleStr.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleStr.toUpperCase());
                    try {
                        world.spawnParticle(particle, loc, 100, 1.0, 1.0, 1.0, 0.1);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning(
                                "Could not spawn redeem particle " + particle + ": requires data like BlockData.");
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger()
                            .warning("Invalid place-redeem particle type '" + particleStr + "': " + e.getMessage());
                }
            }
            String soundStr = gameplayConfig.getPlaceRedeemSound();
            if (soundStr != null && !soundStr.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundStr.toUpperCase());
                    world.playSound(loc, sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid place-redeem sound type '" + soundStr + "': " + e.getMessage());
                }
            }
            if (gameplayConfig.isPlaceRedeemBeaconBeam()) {
                playBeaconBeamEffect(loc, gameplayConfig.getPlaceRedeemBeaconDuration());
            }
        }, 0L, -1L);
    }

    /**
     * 播放信标光束特效
     */
    public void playBeaconBeamEffect(Location loc, int durationSeconds) {
        if (loc == null || loc.getWorld() == null)
            return;
        final World world = loc.getWorld();
        final String worldName = world.getName();
        final int height = loc.getBlockY() - world.getMinHeight();
        final long durationTicks = durationSeconds * 20L;
        final int interval = 2;
        final Object[] taskHolder = new Object[1];
        taskHolder[0] = SchedulerUtil.globalRun(plugin, () -> {
            World currentWorld = Bukkit.getWorld(worldName);
            if (currentWorld == null) {
                if (taskHolder[0] != null)
                    SchedulerUtil.cancelTask(taskHolder[0]);
                return;
            }
            for (int y = 0; y < height; y += 3) {
                Location particleLoc = loc.clone();
                particleLoc.setY(currentWorld.getMinHeight() + y);
                currentWorld.spawnParticle(Particle.END_ROD, particleLoc, 2, 0.1, 0, 0.1, 0.01);
            }
            currentWorld.spawnParticle(Particle.TOTEM, loc, 5, 0.5, 0.5, 0.5, 0.1);
        }, 0L, interval);
        SchedulerUtil.globalRun(plugin, () -> {
            if (taskHolder[0] != null)
                SchedulerUtil.cancelTask(taskHolder[0]);
        }, durationTicks, -1L);
    }

    // ==================== 近距离检测 ====================

    /**
     * 检测所有在线玩家附近是否有宝石
     */
    public void checkPlayersNearRuleGems() {
        if (stateManager.getLocationToGemUuid().isEmpty())
            return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayerNearRuleGems(player);
        }
    }

    /**
     * 检查某个玩家附近是否有宝石并播放提示音
     */
    public void checkPlayerNearRuleGems(Player player) {
        if (player == null || stateManager.getLocationToGemUuid().isEmpty())
            return;
        SchedulerUtil.entityRun(plugin, player, () -> doPlayerNearCheck(player), 0L, -1L);
    }

    private void doPlayerNearCheck(Player player) {
        if (player == null)
            return;
        Location playerLoc = player.getLocation();
        World playerWorld = playerLoc.getWorld();
        if (playerWorld == null)
            return;
        for (Location blockLoc : stateManager.getLocationToGemUuid().keySet()) {
            World w = blockLoc.getWorld();
            if (w == null || !w.equals(playerWorld))
                continue;
            double distance = playerLoc.distance(blockLoc);
            if (distance < PROXIMITY_DETECTION_RANGE) {
                float volume = (float) (1.0 - (distance / PROXIMITY_DETECTION_RANGE));
                player.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_PLING, volume, 1.0f);
            }
        }
    }
}
