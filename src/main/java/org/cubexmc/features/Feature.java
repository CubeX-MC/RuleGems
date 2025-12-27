package org.cubexmc.features;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;

/**
 * 功能特性基类
 * 所有权限相关的功能都应该继承此类
 */
public abstract class Feature {

    protected final RuleGems plugin;
    protected final String permissionNode;
    protected boolean enabled;

    public Feature(RuleGems plugin, String permissionNode) {
        this.plugin = plugin;
        this.permissionNode = permissionNode;
        this.enabled = true;
    }

    /**
     * 获取权限节点
     */
    public String getPermissionNode() {
        return permissionNode;
    }

    /**
     * 检查玩家是否有此功能的权限
     */
    public boolean hasPermission(Player player) {
        return player.hasPermission(permissionNode);
    }

    /**
     * 功能是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置功能启用状态
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 初始化功能（注册监听器等）
     */
    public abstract void initialize();

    /**
     * 关闭功能（清理资源）
     */
    public abstract void shutdown();

    /**
     * 重载功能配置
     */
    public abstract void reload();
}
