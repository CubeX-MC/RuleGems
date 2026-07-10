package org.cubexmc.features

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.features.appoint.AppointFeature
import org.cubexmc.features.revoke.RevokeFeature
import org.cubexmc.features.rule.RuleGateFeature
import org.cubexmc.manager.GemManager

/**
 * 功能管理器
 * 负责管理所有权限相关功能的注册、初始化和关闭
 */
class FeatureManager(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
) {
    private val features: MutableMap<String, Feature> = HashMap()

    // 特定功能的快捷引用
    var appointFeature: AppointFeature? = null
        private set
    var ruleGateFeature: RuleGateFeature? = null
        private set
    var revokeFeature: RevokeFeature? = null
        private set
    var intelBroadcaster: GemIntelBroadcaster? = null
        private set

    /**
     * 注册所有功能
     */
    fun registerFeatures() {
        // 注册指南针导航功能
        registerFeature(GemNavigator(plugin, gemManager))

        // 注册权力情报碎片功能
        intelBroadcaster = GemIntelBroadcaster(plugin, gemManager)
        registerFeature(intelBroadcaster)

        // 注册委任功能
        appointFeature = AppointFeature(plugin)
        registerFeature(appointFeature)

        // 注册 Rule 权力门控功能
        ruleGateFeature = RuleGateFeature(plugin, gemManager)
        registerFeature(ruleGateFeature)

        // 注册撤销宝石制衡功能
        revokeFeature = RevokeFeature(plugin, gemManager)
        registerFeature(revokeFeature)
    }

    /**
     * 注册单个功能
     */
    fun registerFeature(feature: Feature?) {
        if (feature == null) {
            return
        }
        features[feature.permissionNode] = feature
        feature.initialize()
        plugin.logger.info("Registered feature: " + feature.permissionNode)
    }

    /**
     * 获取导航功能
     */
    fun getNavigator(): GemNavigator? {
        val feature = features["rulegems.navigate"]
        if (feature is GemNavigator) {
            return feature
        }
        return null
    }

    /**
     * 玩家加入时处理
     */
    fun onPlayerJoin(player: Player) {
        appointFeature?.onPlayerJoin(player)
    }

    /**
     * 玩家退出时处理
     */
    fun onPlayerQuit(player: Player) {
        appointFeature?.onPlayerQuit(player)
    }

    /**
     * 重载所有功能配置
     */
    fun reloadAll() {
        for (feature in features.values) {
            feature.reload()
        }
    }

    /**
     * 关闭所有功能
     */
    fun shutdownAll() {
        for (feature in features.values) {
            feature.shutdown()
        }
        features.clear()
    }
}
