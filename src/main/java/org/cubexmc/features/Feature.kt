package org.cubexmc.features

import org.bukkit.entity.Player
import org.cubexmc.RuleGems

/**
 * 功能特性基类
 * 所有权限相关的功能都应该继承此类
 */
abstract class Feature(
    @JvmField
    protected val plugin: RuleGems,
    val permissionNode: String,
) {
    @JvmField
    protected var enabled: Boolean = true

    /**
     * 功能是否启用
     */
    val isEnabled: Boolean
        get() = enabled

    /**
     * 检查玩家是否有此功能的权限
     */
    fun hasPermission(player: Player?): Boolean {
        return player != null && player.hasPermission(permissionNode)
    }

    /**
     * 设置功能启用状态
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * 初始化功能（注册监听器等）
     */
    abstract fun initialize()

    /**
     * 关闭功能（清理资源）
     */
    abstract fun shutdown()

    /**
     * 重载功能配置
     */
    abstract fun reload()
}
