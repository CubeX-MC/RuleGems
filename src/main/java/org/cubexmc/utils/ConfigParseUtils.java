package org.cubexmc.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置解析工具方法集合（静态方法）
 * 供 ConfigManager、GemDefinitionParser、GameplayConfig 共用
 */
public final class ConfigParseUtils {

    private ConfigParseUtils() {} // 不可实例化

    /**
     * 空安全的 {@code String.valueOf()}：null 返回 null，否则返回字符串表示
     */
    public static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 将对象转为 {@code List<String>}：
     * <ul>
     *   <li>null → 空列表</li>
     *   <li>List → 逐元素转为 String</li>
     *   <li>其他 → 单元素列表</li>
     * </ul>
     */
    public static List<String> toStringList(Object o) {
        if (o == null)
            return Collections.emptyList();
        if (o instanceof List) {
            List<?> raw = (List<?>) o;
            List<String> out = new ArrayList<>();
            for (Object e : raw) {
                if (e != null)
                    out.add(String.valueOf(e));
            }
            return out;
        }
        return Collections.singletonList(String.valueOf(o));
    }

    /**
     * 解析时间字符串为 tick 数量。
     * 支持的单位: s(秒), m(分钟), h(小时), d(天)。
     * 示例: "30m", "2h", "1d", "90s"。
     * 如果没有单位，默认为分钟。
     *
     * @param timeStr 时间字符串
     * @param logger  可选日志记录器，为 null 则不记录
     * @return tick 数量
     */
    public static long parseTimeToTicks(String timeStr, java.util.logging.Logger logger) {
        if (timeStr == null || timeStr.isEmpty()) {
            return 30 * 60 * 20L; // 默认 30 分钟
        }

        timeStr = timeStr.trim().toLowerCase();

        // 尝试解析带单位的格式
        long multiplier = 60 * 20L; // 默认单位：分钟 -> tick
        String numPart = timeStr;

        if (timeStr.endsWith("s")) {
            multiplier = 20L; // 秒 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("m")) {
            multiplier = 60 * 20L; // 分钟 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("h")) {
            multiplier = 60 * 60 * 20L; // 小时 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("d")) {
            multiplier = 24 * 60 * 60 * 20L; // 天 -> tick
            numPart = timeStr.substring(0, timeStr.length() - 1);
        }

        try {
            double value = Double.parseDouble(numPart.trim());
            return (long) (value * multiplier);
        } catch (NumberFormatException e) {
            if (logger != null) {
                logger.warning("Failed to parse time format: " + timeStr + ", using default 30m");
            }
            return 30 * 60 * 20L;
        }
    }

    /**
     * 宽松 boolean 解析：Boolean 直接返回，其他按 "true" 忽略大小写判断
     */
    public static boolean isTrue(Object obj) {
        if (obj instanceof Boolean)
            return (Boolean) obj;
        return obj != null && "true".equalsIgnoreCase(obj.toString());
    }
}
