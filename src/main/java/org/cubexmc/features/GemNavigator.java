package org.cubexmc.features;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;

/**
 * 宝石导航功能
 * 允许拥有 rulegems.navigate 权限的玩家使用指南针找到最近的宝石
 */
public class GemNavigator extends Feature implements Listener {

    private static final String PERMISSION = "rulegems.navigate";
    
    private final GemManager gemManager;
    private File configFile;
    private YamlConfiguration config;
    
    // 配置选项
    private double maxRange = -1;
    private int cooldownSeconds = 3;
    private boolean showDistance = true;
    private String distancePrecision = "approximate";
    private int thresholdVeryClose = 50;
    private int thresholdClose = 150;
    private int thresholdFar = 500;
    
    // 冷却追踪
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public GemNavigator(RuleGems plugin, GemManager gemManager) {
        super(plugin, PERMISSION);
        this.gemManager = gemManager;
    }

    @Override
    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reload();
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
        cooldowns.clear();
    }

    @Override
    public void reload() {
        // 确保 features 文件夹存在
        File featuresFolder = new File(plugin.getDataFolder(), "features");
        if (!featuresFolder.exists()) {
            featuresFolder.mkdirs();
        }
        
        // 加载配置文件
        configFile = new File(featuresFolder, "navigate.yml");
        if (!configFile.exists()) {
            plugin.saveResource("features/navigate.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        
        this.enabled = config.getBoolean("enabled", true);
        this.maxRange = config.getDouble("max_range", -1);
        this.cooldownSeconds = config.getInt("cooldown", 3);
        this.showDistance = config.getBoolean("show_distance", true);
        this.distancePrecision = config.getString("distance_precision", "approximate");
        this.thresholdVeryClose = config.getInt("distance_thresholds.very_close", 50);
        this.thresholdClose = config.getInt("distance_thresholds.close", 150);
        this.thresholdFar = config.getInt("distance_thresholds.far", 500);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只处理主手
        if (event.getHand() != EquipmentSlot.HAND) return;
        
        // 只处理右键点击
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        
        // 检查是否手持指南针
        if (item.getType() != Material.COMPASS) return;
        
        // 检查功能是否启用
        if (!enabled) return;
        
        // 检查权限
        if (!hasPermission(player)) return;
        
        // 检查冷却
        if (!checkCooldown(player)) return;
        
        // 执行导航
        navigateToNearestGem(player);
    }
    
    /**
     * 检查冷却时间
     */
    private boolean checkCooldown(Player player) {
        if (cooldownSeconds <= 0) return true;
        
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastUse = cooldowns.get(playerId);
        
        if (lastUse != null) {
            long elapsed = (now - lastUse) / 1000;
            if (elapsed < cooldownSeconds) {
                int remaining = (int) (cooldownSeconds - elapsed);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("seconds", String.valueOf(remaining));
                String msg = plugin.getLanguageManager().formatMessage("feature.navigate.cooldown", placeholders);
                player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                return false;
            }
        }
        
        cooldowns.put(playerId, now);
        return true;
    }

    /**
     * 导航到最近的宝石
     */
    private void navigateToNearestGem(Player player) {
        Location playerLoc = player.getLocation();
        NearestGemResult result = findNearestGem(playerLoc);
        
        if (result == null) {
            String msg = plugin.getLanguageManager().formatMessage("feature.navigate.no_gem_found", null);
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }
        
        // 检查范围限制
        if (maxRange > 0 && result.distance > maxRange) {
            String msg = plugin.getLanguageManager().formatMessage("feature.navigate.out_of_range", null);
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }
        
        // 设置指南针目标
        player.setCompassTarget(result.location);
        
        // 计算方向并发送消息
        String direction = getDirection(playerLoc, result.location);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("direction", direction);
        
        if (showDistance) {
            String distanceStr = formatDistance(result.distance);
            placeholders.put("distance", distanceStr);
            String msg = plugin.getLanguageManager().formatMessage("feature.navigate.found_with_distance", placeholders);
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        } else {
            String msg = plugin.getLanguageManager().formatMessage("feature.navigate.found", placeholders);
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        }
    }
    
    /**
     * 格式化距离显示
     */
    private String formatDistance(double distance) {
        if ("exact".equalsIgnoreCase(distancePrecision)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("distance", String.valueOf((int) distance));
            return plugin.getLanguageManager().formatMessage("feature.navigate.distance.blocks", ph);
        } else {
            // approximate
            String key;
            if (distance <= thresholdVeryClose) {
                key = "feature.navigate.distance.very_close";
            } else if (distance <= thresholdClose) {
                key = "feature.navigate.distance.close";
            } else if (distance <= thresholdFar) {
                key = "feature.navigate.distance.far";
            } else {
                key = "feature.navigate.distance.very_far";
            }
            return plugin.getLanguageManager().getMessage(key);
        }
    }

    /**
     * 找到最近的宝石位置
     */
    private NearestGemResult findNearestGem(Location playerLoc) {
        Location nearest = null;
        double nearestDist = Double.MAX_VALUE;
        World playerWorld = playerLoc.getWorld();
        
        Map<UUID, Location> gemLocations = gemManager.getAllGemLocations();
        
        for (Location gemLoc : gemLocations.values()) {
            if (gemLoc == null) continue;
            
            // 只搜索同世界的宝石
            if (!gemLoc.getWorld().equals(playerWorld)) {
                continue;
            }
            
            double dist = playerLoc.distance(gemLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = gemLoc;
            }
        }
        
        if (nearest == null) return null;
        return new NearestGemResult(nearest, nearestDist);
    }
    
    /**
     * 最近宝石结果
     */
    private static class NearestGemResult {
        final Location location;
        final double distance;
        
        NearestGemResult(Location location, double distance) {
            this.location = location;
            this.distance = distance;
        }
    }

    /**
     * 获取方向描述
     */
    private String getDirection(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        
        // 计算角度
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;
        
        // 转换为方向
        if (angle >= 337.5 || angle < 22.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.south");
        } else if (angle >= 22.5 && angle < 67.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.southwest");
        } else if (angle >= 67.5 && angle < 112.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.west");
        } else if (angle >= 112.5 && angle < 157.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.northwest");
        } else if (angle >= 157.5 && angle < 202.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.north");
        } else if (angle >= 202.5 && angle < 247.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.northeast");
        } else if (angle >= 247.5 && angle < 292.5) {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.east");
        } else {
            return plugin.getLanguageManager().getMessage("feature.navigate.direction.southeast");
        }
    }
}
