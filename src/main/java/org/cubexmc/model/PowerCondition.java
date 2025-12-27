package org.cubexmc.model;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 权力条件配置
 * 定义权力结构生效的条件（时间、世界等）
 * 可用于宝石和任命的条件判断
 */
public class PowerCondition {

    // 时间条件
    private boolean timeEnabled = false;
    private TimeType timeType = TimeType.ALWAYS;
    private long timeFrom = 0;
    private long timeTo = 24000;

    // 世界条件
    private boolean worldEnabled = false;
    private WorldMode worldMode = WorldMode.WHITELIST;
    private List<String> worldList = new ArrayList<>();

    /**
     * 时间类型枚举
     */
    public enum TimeType {
        ALWAYS,   // 始终生效
        DAY,      // 白天 (0-12000)
        NIGHT,    // 夜晚 (12000-24000)
        CUSTOM    // 自定义时间范围
    }

    /**
     * 世界模式枚举
     */
    public enum WorldMode {
        WHITELIST,  // 白名单模式：只在列表中的世界生效
        BLACKLIST   // 黑名单模式：在列表外的世界生效
    }

    /**
     * 检查玩家是否满足所有条件
     * @param player 玩家
     * @return 是否满足条件
     */
    public boolean checkConditions(Player player) {
        if (player == null || !player.isOnline()) return false;
        
        // 检查时间条件
        if (!checkTimeCondition(player)) return false;
        
        // 检查世界条件
        if (!checkWorldCondition(player)) return false;
        
        return true;
    }

    /**
     * 检查时间条件
     */
    public boolean checkTimeCondition(Player player) {
        if (!timeEnabled) return true;
        
        World world = player.getWorld();
        long time = world.getTime(); // 0-24000
        
        switch (timeType) {
            case ALWAYS:
                return true;
            case DAY:
                // 白天: 0-12000 (日出到日落)
                return time >= 0 && time < 12000;
            case NIGHT:
                // 夜晚: 12000-24000 (日落到日出)
                return time >= 12000 && time < 24000;
            case CUSTOM:
                // 自定义时间范围
                if (timeFrom <= timeTo) {
                    // 正常范围 (如 6000-18000)
                    return time >= timeFrom && time < timeTo;
                } else {
                    // 跨越午夜的范围 (如 18000-6000)
                    return time >= timeFrom || time < timeTo;
                }
            default:
                return true;
        }
    }

    /**
     * 检查世界条件
     */
    public boolean checkWorldCondition(Player player) {
        if (!worldEnabled || worldList.isEmpty()) return true;
        
        String worldName = player.getWorld().getName();
        boolean inList = worldList.contains(worldName);
        
        switch (worldMode) {
            case WHITELIST:
                return inList;
            case BLACKLIST:
                return !inList;
            default:
                return true;
        }
    }

    /**
     * 获取条件描述（用于显示）
     */
    public String getConditionDescription() {
        StringBuilder sb = new StringBuilder();
        
        if (timeEnabled && timeType != TimeType.ALWAYS) {
            switch (timeType) {
                case DAY:
                    sb.append("白天生效");
                    break;
                case NIGHT:
                    sb.append("夜晚生效");
                    break;
                case CUSTOM:
                    sb.append("时间 ").append(timeFrom).append("-").append(timeTo);
                    break;
                case ALWAYS:
                default:
                    // 无时间限制
                    break;
            }
        }
        
        if (worldEnabled && !worldList.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(worldMode == WorldMode.WHITELIST ? "仅在: " : "排除: ");
            sb.append(String.join(", ", worldList));
        }
        
        return sb.length() > 0 ? sb.toString() : "无限制";
    }

    /**
     * 是否有任何条件启用
     */
    public boolean hasAnyCondition() {
        return (timeEnabled && timeType != TimeType.ALWAYS) || 
               (worldEnabled && !worldList.isEmpty());
    }

    /**
     * 创建副本
     */
    public PowerCondition copy() {
        PowerCondition copy = new PowerCondition();
        copy.timeEnabled = this.timeEnabled;
        copy.timeType = this.timeType;
        copy.timeFrom = this.timeFrom;
        copy.timeTo = this.timeTo;
        copy.worldEnabled = this.worldEnabled;
        copy.worldMode = this.worldMode;
        copy.worldList = new ArrayList<>(this.worldList);
        return copy;
    }

    // ==================== Getters and Setters ====================

    public boolean isTimeEnabled() {
        return timeEnabled;
    }

    public void setTimeEnabled(boolean timeEnabled) {
        this.timeEnabled = timeEnabled;
    }

    public TimeType getTimeType() {
        return timeType;
    }

    public void setTimeType(TimeType timeType) {
        this.timeType = timeType;
    }

    public long getTimeFrom() {
        return timeFrom;
    }

    public void setTimeFrom(long timeFrom) {
        this.timeFrom = timeFrom;
    }

    public long getTimeTo() {
        return timeTo;
    }

    public void setTimeTo(long timeTo) {
        this.timeTo = timeTo;
    }

    public boolean isWorldEnabled() {
        return worldEnabled;
    }

    public void setWorldEnabled(boolean worldEnabled) {
        this.worldEnabled = worldEnabled;
    }

    public WorldMode getWorldMode() {
        return worldMode;
    }

    public void setWorldMode(WorldMode worldMode) {
        this.worldMode = worldMode;
    }

    public List<String> getWorldList() {
        return worldList;
    }

    public void setWorldList(List<String> worldList) {
        this.worldList = worldList != null ? worldList : new ArrayList<>();
    }
}
