package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;

/**
 * 宝石命令限次管理器 - 负责管理命令使用次数限制
 * 包括：持有额度、兑换额度、全局额度的管理
 * <p>
 * 使用脏标记机制避免每次额度变更都触发全量保存。
 */
public class GemAllowanceManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;

    // Per-player per-gem-instance 命令限次（持有）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemHeldUses = new HashMap<>();
    // Per-player per-gem-instance 命令限次（兑换）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemRedeemUses = new HashMap<>();
    // 全局命令限次（如 redeem_all 额外额度）: player -> label -> 剩余次数
    private final Map<UUID, Map<String, Integer>> playerGlobalAllowedUses = new HashMap<>();

    // 脏标记：有数据变更但尚未持久化
    private volatile boolean dirty = false;

    // 反向索引缓存：player -> 可用标签集合（懒加载，数据变更时失效）
    private final Map<UUID, Set<String>> labelIndexCache = new HashMap<>();
    private final Set<UUID> labelIndexDirtyPlayers = new HashSet<>();

    // 保存回调
    private Runnable saveCallback;

    public GemAllowanceManager(GemDefinitionParser gemParser, GameplayConfig gameplayConfig) {
        this.gemParser = gemParser;
        this.gameplayConfig = gameplayConfig;
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
        labelIndexCache.clear();
        labelIndexDirtyPlayers.clear();
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 从 gemsData 加载命令限次数据。
     */
    public void loadData(FileConfiguration gemsData) {
        ConfigurationSection au = gemsData.getConfigurationSection("allowed_uses");
        if (au == null) return;
        for (String playerId : au.getKeys(false)) {
            try {
                UUID uid = UUID.fromString(playerId);
                ConfigurationSection playerSec = au.getConfigurationSection(playerId);
                if (playerSec == null) continue;
                loadInstanceSection(playerSec, "held_instances", uid, playerGemHeldUses);
                loadInstanceSection(playerSec, "redeemed_instances", uid, playerGemRedeemUses);
                // 向后兼容: legacy "instances" → 视为 redeemed_instances
                if (!playerGemRedeemUses.containsKey(uid)) {
                    loadInstanceSection(playerSec, "instances", uid, playerGemRedeemUses);
                }
                // 全局
                ConfigurationSection globSec = playerSec.getConfigurationSection("global");
                if (globSec != null) {
                    Map<String, Integer> map = new HashMap<>();
                    for (String l : globSec.getKeys(false)) {
                        map.put(l.toLowerCase(ROOT_LOCALE), globSec.getInt(l, 0));
                    }
                    if (!map.isEmpty()) playerGlobalAllowedUses.put(uid, map);
                }
            } catch (Exception e) { Bukkit.getLogger().warning("Failed to load allowance data for player: " + e.getMessage()); }
        }
    }

    private void loadInstanceSection(ConfigurationSection playerSec, String key, UUID uid,
                                     Map<UUID, Map<UUID, Map<String, Integer>>> target) {
        ConfigurationSection sec = playerSec.getConfigurationSection(key);
        if (sec == null || sec.getKeys(false).isEmpty()) return;
        Map<UUID, Map<String, Integer>> perInst = new HashMap<>();
        for (String gid : sec.getKeys(false)) {
            try {
                UUID gem = UUID.fromString(gid);
                ConfigurationSection labels = sec.getConfigurationSection(gid);
                Map<String, Integer> map = new HashMap<>();
                if (labels != null) {
                    for (String l : labels.getKeys(false)) {
                        map.put(l.toLowerCase(ROOT_LOCALE), labels.getInt(l, 0));
                    }
                }
                perInst.put(gem, map);
            } catch (Exception e) { Bukkit.getLogger().warning("Failed to parse gem UUID in allowance data: " + e.getMessage()); }
        }
        if (!perInst.isEmpty()) target.put(uid, perInst);
    }

    /**
     * 将命令限次数据写入 gemsData。
     */
    public void saveData(FileConfiguration gemsData) {
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemHeldUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<UUID, Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    gemsData.set(base + ".held_instances." + inst.getKey().toString() + "." + l.getKey(), l.getValue());
                }
            }
        }
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemRedeemUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<UUID, Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    gemsData.set(base + ".redeemed_instances." + inst.getKey().toString() + "." + l.getKey(), l.getValue());
                }
            }
        }
        for (Map.Entry<UUID, Map<String, Integer>> e : playerGlobalAllowedUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<String, Integer> l : e.getValue().entrySet()) {
                gemsData.set(base + ".global." + l.getKey(), l.getValue());
            }
        }
    }

    /**
     * 清除指定玩家的所有额度数据
     */
    public void clearPlayerData(UUID uid) {
        if (uid == null) return;
        playerGemHeldUses.remove(uid);
        playerGemRedeemUses.remove(uid);
        playerGlobalAllowedUses.remove(uid);
        invalidateLabelIndex(uid);
    }

    // ==================== 额度查询 ====================

    /**
     * 检查玩家是否有某命令的可用额度
     */
    public boolean hasAnyAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return false;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 全局额度
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v != null && (v > 0 || v < 0))
                return true;
        }

        // 持有实例额度
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0))
                    return true;
            }
        }

        // 兑换实例额度
        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map<String, Integer> byLabel : perRed.values()) {
                Integer v = byLabel.get(l);
                if (v != null && (v > 0 || v < 0))
                    return true;
            }
        }

        return false;
    }

    /**
     * 尝试消耗一次命令额度
     */
    public boolean tryConsumeAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return false;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 先尝试持有实例
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null && !perHeld.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perHeld.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                Map<String, Integer> byLabel = perHeld.get(gid);
                if (byLabel == null)
                    continue;
                Integer v = byLabel.get(l);
                if (v == null)
                    v = 0;
                if (v < 0) {
                    markDirty(uid);
                    return true;
                }
                if (v > 0) {
                    byLabel.put(l, v - 1);
                    markDirty(uid);
                    return true;
                }
            }
        }

        // 再尝试已兑换实例
        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null && !perRed.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perRed.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                Map<String, Integer> byLabel = perRed.get(gid);
                if (byLabel == null)
                    continue;
                Integer v = byLabel.get(l);
                if (v == null)
                    v = 0;
                if (v < 0) {
                    markDirty(uid);
                    return true;
                }
                if (v > 0) {
                    byLabel.put(l, v - 1);
                    markDirty(uid);
                    return true;
                }
            }
        }

        // 最后尝试全局
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v = glob.get(l);
            if (v == null)
                v = 0;
            if (v < 0) {
                markDirty(uid);
                return true;
            }
            if (v > 0) {
                glob.put(l, v - 1);
                markDirty(uid);
                return true;
            }
        }

        return false;
    }

    /**
     * 退还一次命令额度
     */
    public void refundAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return;
        String l = label.toLowerCase(ROOT_LOCALE);

        // per-instance first: held then redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                if (byLabel.containsKey(l)) {
                    int v = byLabel.getOrDefault(l, 0);
                    byLabel.put(l, v + 1);
                    markDirty(uid);
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
                    markDirty(uid);
                    return;
                }
            }
        }

        // global
        Map<String, Integer> glob = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        int v = glob.getOrDefault(l, 0);
        glob.put(l, v + 1);
        markDirty(uid);
    }

    /**
     * 获取剩余额度
     */
    public int getRemainingAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return 0;
        String l = label.toLowerCase(ROOT_LOCALE);
        int sum = 0;

        // per-instance: held + redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0)
                        return -1; // 无限
                    sum += v2;
                }
            }
        }

        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map<String, Integer> byLabel : perRed.values()) {
                Integer v2 = byLabel.get(l);
                if (v2 != null) {
                    if (v2 < 0)
                        return -1;
                    sum += v2;
                }
            }
        }

        // global
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            Integer v2 = glob.get(l);
            if (v2 != null) {
                if (v2 < 0)
                    return -1;
                sum += v2;
            }
        }

        return sum;
    }

    /**
     * 获取 AllowedCommand 对象（用于获取冷却时间等信息）
     */
    public AllowedCommand getAllowedCommand(UUID uid, String label) {
        if (uid == null || label == null)
            return null;
        String l = label.toLowerCase(ROOT_LOCALE);

        // 从各个宝石定义中查找
        for (GemDefinition def : gemParser.getGemDefinitions()) {
            for (AllowedCommand cmd : def.getAllowedCommands()) {
                if (cmd.getLabel().equals(l)) {
                    return cmd;
                }
            }
        }

        // 从 redeemAll 中查找
        org.cubexmc.model.PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
        if (redeemAllPower != null && redeemAllPower.getAllowedCommands() != null) {
            for (AllowedCommand cmd : redeemAllPower.getAllowedCommands()) {
                if (cmd.getLabel().equals(l)) {
                    return cmd;
                }
            }
        }

        return null;
    }

    // ==================== 额度初始化 ====================

    /**
     * 授予全局命令额度（如 redeem_all）
     */
    public void grantGlobalAllowedCommands(Player player, GemDefinition def) {
        if (player == null || def == null)
            return;
        List<AllowedCommand> allows = def.getAllowedCommands();
        if (allows == null || allows.isEmpty())
            return;

        UUID uid = player.getUniqueId();
        Map<String, Integer> global = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        for (AllowedCommand ac : allows) {
            if (ac == null || ac.getLabel() == null)
                continue;
            global.put(ac.getLabel().toLowerCase(ROOT_LOCALE), ac.getUses());
        }
        markDirty(uid);
    }

    /**
     * 重新分配持有实例额度（宝石转手时）
     */
    public void reassignHeldInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def) {
        if (gemId == null || newOwner == null || def == null)
            return;

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
            if (map != null)
                payload = map.remove(gemId);
            if (map != null && map.isEmpty())
                playerGemHeldUses.remove(oldOwner);
        }

        Map<UUID, Map<String, Integer>> dest = playerGemHeldUses.computeIfAbsent(newOwner, k -> new HashMap<>());
        if (payload == null) {
            if (!dest.containsKey(gemId))
                dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        markDirty(newOwner);
        if (oldOwner != null) invalidateLabelIndex(oldOwner);
    }

    /**
     * 重新分配兑换实例额度
     */
    public void reassignRedeemInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def,
            boolean resetEvenIfSameOwner) {
        if (gemId == null || newOwner == null || def == null)
            return;

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
                markDirty(newOwner);
            }
            return;
        }

        Map<String, Integer> payload = null;
        if (oldOwner != null) {
            Map<UUID, Map<String, Integer>> map = playerGemRedeemUses.get(oldOwner);
            if (map != null)
                payload = map.remove(gemId);
            if (map != null && map.isEmpty())
                playerGemRedeemUses.remove(oldOwner);
        }

        Map<UUID, Map<String, Integer>> dest = playerGemRedeemUses.computeIfAbsent(newOwner, k -> new HashMap<>());
        if (payload == null || resetEvenIfSameOwner) {
            dest.put(gemId, buildAllowedMap(def));
        } else {
            dest.put(gemId, payload);
        }
        markDirty(newOwner);
        if (oldOwner != null) invalidateLabelIndex(oldOwner);
    }

    /**
     * 构建命令额度映射
     */
    private Map<String, Integer> buildAllowedMap(GemDefinition def) {
        Map<String, Integer> map = new HashMap<>();
        List<AllowedCommand> allows = def.getAllowedCommands();
        if (allows != null) {
            for (AllowedCommand ac : allows) {
                if (ac == null || ac.getLabel() == null)
                    continue;
                map.put(ac.getLabel().toLowerCase(ROOT_LOCALE), ac.getUses());
            }
        }
        return map;
    }

    // ==================== 可用标签查询 ====================

    /**
     * 获取玩家所有可用的命令标签（使用反向索引缓存）
     */
    public Set<String> getAvailableCommandLabels(UUID uid) {
        if (uid == null) return new HashSet<>();
        if (labelIndexDirtyPlayers.contains(uid) || !labelIndexCache.containsKey(uid)) {
            Set<String> labels = rebuildLabelIndex(uid);
            labelIndexCache.put(uid, labels);
            labelIndexDirtyPlayers.remove(uid);
        }
        return new HashSet<>(labelIndexCache.get(uid));
    }

    private Set<String> rebuildLabelIndex(UUID uid) {
        Set<String> labels = new HashSet<>();
        collectActiveLabelsFromNestedMap(labels, playerGemHeldUses.get(uid));
        collectActiveLabelsFromNestedMap(labels, playerGemRedeemUses.get(uid));
        collectActiveLabelsFromFlatMap(labels, playerGlobalAllowedUses.get(uid));
        return labels;
    }

    private void invalidateLabelIndex(UUID uid) {
        if (uid != null) labelIndexDirtyPlayers.add(uid);
    }

    private void collectActiveLabelsFromNestedMap(Set<String> labels,
            Map<UUID, Map<String, Integer>> nested) {
        if (nested == null || nested.isEmpty()) return;
        for (Map<String, Integer> inner : nested.values()) {
            collectActiveLabelsFromFlatMap(labels, inner);
        }
    }

    private void collectActiveLabelsFromFlatMap(Set<String> labels, Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String key = entry.getKey();
            Integer remaining = entry.getValue();
            if (key == null || key.isBlank() || remaining == null) continue;
            if (remaining == 0) continue;
            String base = key.split(" ")[0].toLowerCase(ROOT_LOCALE);
            if (!base.isEmpty()) labels.add(base);
        }
    }

    private void save() {
        if (saveCallback != null) {
            saveCallback.run();
        }
        dirty = false;
    }

    /**
     * 标记数据已变更，等待下一次定时刷盘，并使指定玩家的标签缓存失效。
     */
    private void markDirty() {
        dirty = true;
    }

    /**
     * 标记数据已变更并使指定玩家的标签索引缓存失效。
     */
    private void markDirty(UUID uid) {
        dirty = true;
        invalidateLabelIndex(uid);
    }

    /**
     * 如果有未保存的变更则立即持久化。
     * 由 GemManager 的定时任务调用（默认每 60 秒一次），以及插件关闭时调用。
     */
    public void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    /**
     * 是否有未保存的数据变更
     */
    public boolean isDirty() {
        return dirty;
    }
}
