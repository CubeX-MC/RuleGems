package org.cubexmc.features;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.GemManager;

/**
 * 功能管理器
 * 负责管理所有权限相关功能的注册、初始化和关闭
 */
public class FeatureManager {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final Map<String, Feature> features = new HashMap<>();
    
    // 特定功能的快捷引用
    private AppointFeature appointFeature;

    public FeatureManager(RuleGems plugin, GemManager gemManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
    }

    /**
     * 注册所有功能
     */
    public void registerFeatures() {
        // 注册指南针导航功能
        registerFeature(new GemNavigator(plugin, gemManager));
        
        // 注册委任功能
        appointFeature = new AppointFeature(plugin);
        registerFeature(appointFeature);
    }

    /**
     * 注册单个功能
     */
    public void registerFeature(Feature feature) {
        features.put(feature.getPermissionNode(), feature);
        feature.initialize();
        plugin.getLogger().info("Registered feature: " + feature.getPermissionNode());
    }

    /**
     * 获取功能
     */
    public Feature getFeature(String permissionNode) {
        return features.get(permissionNode);
    }

    /**
     * 获取导航功能
     */
    public GemNavigator getNavigator() {
        Feature feature = features.get("rulegems.navigate");
        if (feature instanceof GemNavigator) {
            return (GemNavigator) feature;
        }
        return null;
    }

    /**
     * 获取委任功能
     */
    public AppointFeature getAppointFeature() {
        return appointFeature;
    }

    /**
     * 玩家加入时处理
     */
    public void onPlayerJoin(Player player) {
        if (appointFeature != null) {
            appointFeature.onPlayerJoin(player);
        }
    }

    /**
     * 玩家退出时处理
     */
    public void onPlayerQuit(Player player) {
        if (appointFeature != null) {
            appointFeature.onPlayerQuit(player);
        }
    }

    /**
     * 重载所有功能配置
     */
    public void reloadAll() {
        for (Feature feature : features.values()) {
            feature.reload();
        }
    }

    /**
     * 关闭所有功能
     */
    public void shutdownAll() {
        for (Feature feature : features.values()) {
            feature.shutdown();
        }
        features.clear();
    }

    /**
     * 获取所有已注册的功能
     */
    public Map<String, Feature> getAllFeatures() {
        return new HashMap<>(features);
    }
}
