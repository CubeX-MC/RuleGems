package org.cubexmc.gui;

import java.util.UUID;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * GUI 持有者 - 用于标识不同类型的 GUI
 */
public class GUIHolder implements InventoryHolder {

    /**
     * GUI 类型枚举
     */
    public enum GUIType {
        MAIN_MENU,          // 主菜单
        GEMS,               // 宝石列表
        RULERS,             // 统治者列表
        PROFILE,            // 玩家个人状态
        CABINET,            // 委任职位总览
        CABINET_MEMBERS,    // 某职位的任命/解雇界面
        RULER_APPOINTEES,   // 统治者任命详情
        POWER_TOGGLES       // 宝石能力开关
    }

    private final GUIType type;
    private final UUID viewerId;
    private final boolean isAdmin;
    private final int page;
    private final String filter;
    private Inventory inventory;

    /**
     * 创建 GUI 持有者
     * @param type GUI 类型
     * @param viewerId 查看者 UUID
     * @param isAdmin 是否为管理员视图
     */
    public GUIHolder(GUIType type, UUID viewerId, boolean isAdmin) {
        this(type, viewerId, isAdmin, 0, null);
    }

    /**
     * 创建 GUI 持有者（带页码）
     * @param type GUI 类型
     * @param viewerId 查看者 UUID
     * @param isAdmin 是否为管理员视图
     * @param page 当前页码
     */
    public GUIHolder(GUIType type, UUID viewerId, boolean isAdmin, int page) {
        this(type, viewerId, isAdmin, page, null);
    }

    /**
     * 创建 GUI 持有者（完整参数）
     * @param type GUI 类型
     * @param viewerId 查看者 UUID
     * @param isAdmin 是否为管理员视图
     * @param page 当前页码
     * @param filter 额外上下文；可用于筛选条件、目标 UUID 或 appointKey
     */
    public GUIHolder(GUIType type, UUID viewerId, boolean isAdmin, int page, String filter) {
        this.type = type;
        this.viewerId = viewerId;
        this.isAdmin = isAdmin;
        this.page = page;
        this.filter = filter;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 设置关联的 Inventory
     * @param inventory Inventory 实例
     */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * 获取 GUI 类型
     */
    public GUIType getType() {
        return type;
    }

    /**
     * 获取查看者 UUID
     */
    public UUID getViewerId() {
        return viewerId;
    }

    /**
     * 是否为管理员视图
     */
    public boolean isAdmin() {
        return isAdmin;
    }

    /**
     * 获取当前页码
     */
    public int getPage() {
        return page;
    }

    /**
     * 获取额外上下文。
     * 对 GEMS 来说是筛选条件；对其他分页界面可能是目标 UUID 或 appointKey。
     */
    public String getContext() {
        return filter;
    }

    /**
     * 获取筛选条件/上下文。
     * 保留该命名用于兼容旧调用点，优先使用 {@link #getContext()}。
     */
    public String getFilter() {
        return filter;
    }

    /**
     * 检查 Inventory 是否属于我们的 GUI
     * @param inventory 待检查的 Inventory
     * @return 如果是我们的 GUI 返回对应的 GUIHolder，否则返回 null
     */
    public static GUIHolder getHolder(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof GUIHolder) {
            return (GUIHolder) holder;
        }
        return null;
    }

}

