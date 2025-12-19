package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;

/**
 * 宝石命令限次管理器 - 负责管理命令使用次数限制
 * 包括：持有额度、兑换额度、全局额度的管理
 */
public class GemAllowanceManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    private final ConfigManager configManager;

    // Per-player per-gem-instance 命令限次（持有）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemHeldUses = new HashMap<>();
    // Per-player per-gem-instance 命令限次（兑换）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemRedeemUses = new HashMap<>();
    // 全局命令限次（如 redeem_all 额外额度）: player -> label -> 剩余次数
    private final Map<UUID, Map<String, Integer>> playerGlobalAllowedUses = new HashMap<>();

    // 保存回调
    private Runnable saveCallback;

    public GemAllowanceManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void setSaveCallback(Runnable callback) {
        this.saveCallback = callback;
    }

    // ==================== 状态访问器 ====================

    public Map<UUID, Map<UUID, Map<String, Integer>>> getPlayerGemHeldUses() {
        return playerGemHeldUses;
    }

    public Map<UUID, Map<UUID, Map<String, Integer>>> getPlayerGemRedeemUses() {
        return playerGemRedeemUses;
    }

    public Map<UUID, Map<String, Integer>> getPlayerGlobalAllowedUses() {
        return playerGlobalAllowedUses;
    }

    // ==================== 清理方法 ====================

    public void clearAll() {
        playerGemHeldUses.clear();
        playerGemRedeemUses.clear();
        playerGlobalAllowedUses.clear();
    }

    // ==================== 额度查询 ====================

    /**
     * 检查玩家是否有某命令的可用额度
     */
    public boolean hasAnyAllowed(UUID uid, String label) {
        if (uid == null || label == null) return false;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 全局额度
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v != null && (v > 0 || v < 0)) return true;
        }

        // 持有实例额度
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0)) return true;
            }
        }

        // 兑换实例额度
        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map<String, Integer> byLabel : perRed.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0)) return true;
            }
        }

        return false;
    }

    /**
     * 尝试消耗一次命令额度
     */
    public boolean tryConsumeAllowed(UUID uid, String label) {
        if (uid == null || label == null) return false;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 先尝试持有实例
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null && !perHeld.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perHeld.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                Map<String, Integer> byLabel = perHeld.get(gid);
                if (byLabel == null) continue;
                Integer v = byLabel.get(l);
                if (v == null) v = 0;
                if (v < 0) { save(); return true; }
                if (v > 0) { byLabel.put(l, v - 1); save(); return true; }
            }
        }

        // 再尝试已兑换实例
        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null && !perRed.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perRed.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                Map<String, Integer> byLabel = perRed.get(gid);
                if (byLabel == null) continue;
                Integer v = byLabel.get(l);
                if (v == null) v = 0;
                if (v < 0) { save(); return true; }
                if (v > 0) { byLabel.put(l, v - 1); save(); return true; }
            }
        }

        // 最后尝试全局
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v == null) v = 0;
            if (v < 0) { save(); return true; }
            if (v > 0) { glob.put(l, v - 1); save(); return true; }
        }

        return false;
    }

    /**
     * 退还一次命令额度
     */
    public void refundAllowed(UUID uid, String label) {
        if (uid == null || label == null) return;
        String l = label.toLowerCase(ROOT_LOCALE);

        // per-instance first: held then redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                if (byLabel.containsKey(l)) {
                    int v = byLabel.getOrDefault(l, 0);
                    byLabel.put(l, v + 1);
                    save();
                    return;
                }
            }
        }

        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map<String, Integer> byLabel : perRed.values()) {
                if (byLabel.containsKey(l)) {
                    int v = byLabel.getOrDefault(l, 0);
                    byLabel.put(l, v + 1);
                    save();
                    return;
                }
            }
        }

        // global
        Map<String, Integer> glob = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        int v = glob.getOrDefault(l, 0);
        glob.put(l, v + 1);
        save();
    }

    /**
     * 获取剩余额度
     */
    public int getRemainingAllowed(UUID uid, String label) {
        if (uid == null || label == null) return 0;
        String l = label.toLowerCase(ROOT_LOCALE);
        int sum = 0;

        // per-instance: held + redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0) return -1; // 无限
                    sum += v2;
                }
            }
        }

        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map<String, Integer> byLabel : perRed.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0) return -1;
                    sum += v2;
                }
            }
        }

        // global
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v2 = glob.get(l);
            if (v2 != null) {
                if (v2 < 0) return -1;
                sum += v2;
            }
        }

        return sum;
    }

    /**
     * 获取 AllowedCommand 对象（用于获取冷却时间等信息）
     */
    public AllowedCommand getAllowedCommand(UUID uid, String label) {
        if (uid == null || label == null) return null;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 从各个宝石定义中查找
        for (GemDefinition def : configManager.getGemDefinitions()) {
            for (AllowedCommand cmd : def.getAllowedCommands()) {
                if (cmd.getLabel().equals(l)) {
                    return cmd;
                }
            }
        }

        // 从 redeemAll 中查找
        for (AllowedCommand cmd : configManager.getRedeemAllAllowedCommands()) {
            if (cmd.getLabel().equals(l)) {
                return cmd;
            }
        }

        return null;
    }

    // ==================== 额度初始化 ====================

    /**
     * 授予全局命令额度（如 redeem_all）
     */
    public void grantGlobalAllowedCommands(Player player, GemDefinition def) {
        if (player == null || def == null) return;
        List<AllowedCommand> allows = def.getAllowedCommands();
        if (allows == null || allows.isEmpty()) return;

        UUID uid = player.getUniqueId();
        Map<String, Integer> global = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        for (AllowedCommand ac : allows) {
            if (ac == null || ac.getLabel() == null) continue;
            global.put(ac.getLabel().toLowerCase(ROOT_LOCALE), ac.getUses());
        }
        save();
    }

    /**
     * 重新分配持有实例额度（宝石转手时）
     */
    public void reassignHeldInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def) {
        if (gemId == null || newOwner == null || def == null) return;

        // 查找旧拥有者
        UUID oldOwner = null;
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemHeldUses.entrySet()) {
            if (e.getValue() != null && e.getValue().containsKey(gemId)) {
                oldOwner = e.getKey();
                break;
            }
        }

        if (newOwner.equals(oldOwner)) {
            return; // 同一人不重置
        }

        Map<String, Integer> payload = null;
        if (oldOwner != null) {
            Map<UUID, Map<String, Integer>> map = playerGemHeldUses.get(oldOwner);
            if (map != null) payload = map.remove(gemId);
            if (map != null && map.isEmpty()) playerGemHeldUses.remove(oldOwner);
        }

        Map<UUID, Map<String, Integer>> dest = playerGemHeldUses.computeIfAbsent(newOwner, k -> new HashMap<>());
        if (payload == null) {
            if (!dest.containsKey(gemId)) dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        save();
    }

    /**
     * 重新分配兑换实例额度
     */
    public void reassignRedeemInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def, boolean resetEvenIfSameOwner) {
        if (gemId == null || newOwner == null || def == null) return;

        UUID oldOwner = null;
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemRedeemUses.entrySet()) {
            if (e.getValue() != null && e.getValue().containsKey(gemId)) {
                oldOwner = e.getKey();
                break;
            }
        }

        if (newOwner.equals(oldOwner)) {
            if (resetEvenIfSameOwner) {
                playerGemRedeemUses.computeIfAbsent(newOwner, k -> new HashMap<>())
                    .put(gemId, buildAllowedMap(def));
                save();
            }
            return;
        }

        Map<String, Integer> payload = null;
        if (oldOwner != null) {
            Map<UUID, Map<String, Integer>> map = playerGemRedeemUses.get(oldOwner);
            if (map != null) payload = map.remove(gemId);
            if (map != null && map.isEmpty()) playerGemRedeemUses.remove(oldOwner);
        }

        Map<UUID, Map<String, Integer>> dest = playerGemRedeemUses.computeIfAbsent(newOwner, k -> new HashMap<>());
        if (payload == null || resetEvenIfSameOwner) {
            dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        save();
    }

    /**
     * 构建命令额度映射
     */
    private Map<String, Integer> buildAllowedMap(GemDefinition def) {
        Map<String, Integer> map = new HashMap<>();
        List<AllowedCommand> allows = def.getAllowedCommands();
        if (allows != null) {
            for (AllowedCommand ac : allows) {
                if (ac == null || ac.getLabel() == null) continue;
                map.put(ac.getLabel().toLowerCase(ROOT_LOCALE), ac.getUses());
            }
        }
        return map;
    }

    private void save() {
        if (saveCallback != null) {
            saveCallback.run();
        }
    }
}

