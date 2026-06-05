package org.cubexmc.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.features.rule.RuleGateFeature;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;

/**
 * 宝石命令限次管理器 - 负责管理命令使用次数限制
 * 包括：持有额度、兑换额度、全局额度的管理
 * <p>
 * 使用脏标记机制避免每次额度变更都触发全量保存。
 */
public class GemAllowanceManager {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    public enum AllowanceSourceType {
        HELD,
        REDEEMED,
        APPOINTMENT,
        GLOBAL
    }

    /**
     * A command allowance resolved to the exact source that will pay for execution.
     */
    public static final class ResolvedAllowance {
        private final UUID playerId;
        private final AllowanceSourceType sourceType;
        private final UUID gemId;
        private final String sourceKey;
        private final String label;
        private final AllowedCommand command;

        public ResolvedAllowance(UUID playerId, AllowanceSourceType sourceType, UUID gemId, String sourceKey,
                String label, AllowedCommand command) {
            this.playerId = playerId;
            this.sourceType = sourceType;
            this.gemId = gemId;
            this.sourceKey = sourceKey;
            this.label = label;
            this.command = command;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public AllowanceSourceType getSourceType() {
            return sourceType;
        }

        public UUID getGemId() {
            return gemId;
        }

        public String getSourceKey() {
            return sourceKey;
        }

        public String getLabel() {
            return label;
        }

        public AllowedCommand getCommand() {
            return command;
        }

        public String getCooldownKey() {
            String source = gemId != null ? gemId.toString() : (sourceKey != null ? sourceKey : "all");
            return sourceType.name().toLowerCase(Locale.ROOT) + ":" + source + ":" + label;
        }
    }

    private final GemDefinitionParser gemParser;
    private final GameplayConfig gameplayConfig;

    // Per-player per-gem-instance 命令限次（持有）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemHeldUses = new HashMap<>();
    // Per-player per-gem-instance 命令限次（兑换）: player -> gemId -> label -> 剩余次数
    private final Map<UUID, Map<UUID, Map<String, Integer>>> playerGemRedeemUses = new HashMap<>();
    // 全局命令限次（如 redeem_all 额外额度）: player -> label -> 剩余次数
    private final Map<UUID, Map<String, Integer>> playerGlobalAllowedUses = new HashMap<>();
    // 任命命令限次: player -> appointKey -> label -> 剩余次数
    private final Map<UUID, Map<String, Map<String, Integer>>> playerAppointmentAllowedUses = new HashMap<>();

    // 脏标记：有数据变更但尚未持久化
    private volatile boolean dirty = false;

    // 反向索引缓存：player -> 可用标签集合（懒加载，数据变更时失效）
    private final Map<UUID, Set<String>> labelIndexCache = new HashMap<>();
    private final Set<UUID> labelIndexDirtyPlayers = new HashSet<>();

    // 保存回调
    private Runnable saveCallback;
    // 检查宝石是否被关闭的回调 (playerId, gemId) -> boolean
    private java.util.function.BiPredicate<UUID, UUID> isToggledOffCheck;
    private Function<UUID, String> gemKeyLookup;
    private Function<String, PowerStructure> appointmentPowerLookup;
    private RuleGateFeature ruleGateFeature;

    public GemAllowanceManager(GemDefinitionParser gemParser, GameplayConfig gameplayConfig) {
        this.gemParser = gemParser;
        this.gameplayConfig = gameplayConfig;
    }

    public void setSaveCallback(Runnable callback) {
        this.saveCallback = callback;
    }

    public void setIsToggledOffCheck(java.util.function.BiPredicate<UUID, UUID> check) {
        this.isToggledOffCheck = check;
    }

    public void setGemKeyLookup(Function<UUID, String> lookup) {
        this.gemKeyLookup = lookup;
    }

    public void setAppointmentPowerLookup(Function<String, PowerStructure> lookup) {
        this.appointmentPowerLookup = lookup;
    }

    public void setRuleGateFeature(RuleGateFeature feature) {
        this.ruleGateFeature = feature;
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

    public Map<UUID, Map<String, Map<String, Integer>>> getPlayerAppointmentAllowedUses() {
        return playerAppointmentAllowedUses;
    }

    // ==================== 清理方法 ====================

    public void clearAll() {
        playerGemHeldUses.clear();
        playerGemRedeemUses.clear();
        playerGlobalAllowedUses.clear();
        playerAppointmentAllowedUses.clear();
        labelIndexCache.clear();
        labelIndexDirtyPlayers.clear();
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 从 gemsData 加载命令限次数据。
     */
    public void loadData(FileConfiguration gemsData) {
        ConfigurationSection au = gemsData.getConfigurationSection("allowed_uses");
        if (au == null)
            return;
        for (String playerId : au.getKeys(false)) {
            try {
                UUID uid = UUID.fromString(playerId);
                ConfigurationSection playerSec = au.getConfigurationSection(playerId);
                if (playerSec == null)
                    continue;
                loadInstanceSection(playerSec, "held_instances", uid, playerGemHeldUses);
                loadInstanceSection(playerSec, "redeemed_instances", uid, playerGemRedeemUses);
                loadStringSourceSection(playerSec, "appointments", uid, playerAppointmentAllowedUses);
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
                    if (!map.isEmpty())
                        playerGlobalAllowedUses.put(uid, map);
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to load allowance data for player: " + e.getMessage());
            }
        }
    }

    private void loadInstanceSection(ConfigurationSection playerSec, String key, UUID uid,
            Map<UUID, Map<UUID, Map<String, Integer>>> target) {
        ConfigurationSection sec = playerSec.getConfigurationSection(key);
        if (sec == null || sec.getKeys(false).isEmpty())
            return;
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
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to parse gem UUID in allowance data: " + e.getMessage());
            }
        }
        if (!perInst.isEmpty())
            target.put(uid, perInst);
    }

    private void loadStringSourceSection(ConfigurationSection playerSec, String key, UUID uid,
            Map<UUID, Map<String, Map<String, Integer>>> target) {
        ConfigurationSection sec = playerSec.getConfigurationSection(key);
        if (sec == null || sec.getKeys(false).isEmpty())
            return;
        Map<String, Map<String, Integer>> perSource = new HashMap<>();
        for (String source : sec.getKeys(false)) {
            ConfigurationSection labels = sec.getConfigurationSection(source);
            Map<String, Integer> map = new HashMap<>();
            if (labels != null) {
                for (String l : labels.getKeys(false)) {
                    map.put(normalizeLabel(l), labels.getInt(l, 0));
                }
            }
            if (!map.isEmpty()) {
                perSource.put(normalizeSourceKey(source), map);
            }
        }
        if (!perSource.isEmpty())
            target.put(uid, perSource);
    }

    /**
     * 将将要保存的数据结构提取到快照中，用于线程安全的异步落盘
     */
    public void populateSaveSnapshot(Map<String, Object> snapshot) {
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemHeldUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<UUID, Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    snapshot.put(base + ".held_instances." + inst.getKey().toString() + "." + l.getKey(), l.getValue());
                }
            }
        }
        for (Map.Entry<UUID, Map<UUID, Map<String, Integer>>> e : playerGemRedeemUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<UUID, Map<String, Integer>> inst : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : inst.getValue().entrySet()) {
                    snapshot.put(base + ".redeemed_instances." + inst.getKey().toString() + "." + l.getKey(),
                            l.getValue());
                }
            }
        }
        for (Map.Entry<UUID, Map<String, Integer>> e : playerGlobalAllowedUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<String, Integer> l : e.getValue().entrySet()) {
                snapshot.put(base + ".global." + l.getKey(), l.getValue());
            }
        }
        for (Map.Entry<UUID, Map<String, Map<String, Integer>>> e : playerAppointmentAllowedUses.entrySet()) {
            String base = "allowed_uses." + e.getKey().toString();
            for (Map.Entry<String, Map<String, Integer>> source : e.getValue().entrySet()) {
                for (Map.Entry<String, Integer> l : source.getValue().entrySet()) {
                    snapshot.put(base + ".appointments." + source.getKey() + "." + l.getKey(), l.getValue());
                }
            }
        }
    }

    /**
     * 清除指定玩家的所有额度数据
     */
    public void clearPlayerData(UUID uid) {
        if (uid == null)
            return;
        playerGemHeldUses.remove(uid);
        playerGemRedeemUses.remove(uid);
        playerGlobalAllowedUses.remove(uid);
        playerAppointmentAllowedUses.remove(uid);
        invalidateLabelIndex(uid);
    }

    public void removeRedeemInstanceAllowance(UUID uid, UUID gemId) {
        if (uid == null || gemId == null)
            return;
        Map<UUID, Map<String, Integer>> byGem = playerGemRedeemUses.get(uid);
        if (byGem == null)
            return;
        byGem.remove(gemId);
        if (byGem.isEmpty()) {
            playerGemRedeemUses.remove(uid);
        }
        markDirty(uid);
    }

    // ==================== 额度查询 ====================

    /**
     * 检查玩家是否有某命令的可用额度
     */
    public boolean hasAnyAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return false;
        String l = normalizeLabel(label);

        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map.Entry<UUID, Map<String, Integer>> entry : perHeld.entrySet()) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, entry.getKey())) continue;
                if (!canUseSource(uid, entry.getKey())) continue;
                if (hasRemaining(entry.getValue(), l))
                    return true;
            }
        }

        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map.Entry<UUID, Map<String, Integer>> entry : perRed.entrySet()) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, entry.getKey())) continue;
                if (!canUseSource(uid, entry.getKey())) continue;
                if (hasRemaining(entry.getValue(), l))
                    return true;
            }
        }

        Map<String, Map<String, Integer>> perAppointment = playerAppointmentAllowedUses.get(uid);
        if (perAppointment != null && canUseAppointment(uid)) {
            for (Map<String, Integer> byLabel : perAppointment.values()) {
                if (hasRemaining(byLabel, l))
                    return true;
            }
        }

        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null && canUseGlobal(uid) && hasRemaining(glob, l)) {
            return true;
        }

        return false;
    }

    /**
     * Resolve the specific source that should execute and pay for an allowed command.
     */
    public ResolvedAllowance resolveAllowedCommand(UUID uid, String label) {
        if (uid == null || label == null)
            return null;
        String l = normalizeLabel(label);

        ResolvedAllowance held = resolveFromGemMap(uid, l, playerGemHeldUses.get(uid), AllowanceSourceType.HELD);
        if (held != null)
            return held;

        ResolvedAllowance redeemed = resolveFromGemMap(uid, l, playerGemRedeemUses.get(uid),
                AllowanceSourceType.REDEEMED);
        if (redeemed != null)
            return redeemed;

        ResolvedAllowance appointment = resolveFromAppointments(uid, l);
        if (appointment != null)
            return appointment;

        return resolveFromGlobal(uid, l);
    }

    /**
     * 尝试消耗一次命令额度
     */
    public boolean tryConsumeAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return false;
        String l = normalizeLabel(label);

        // 先尝试持有实例
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null && !perHeld.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perHeld.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, gid)) continue;
                if (!canUseSource(uid, gid)) continue;
                Map<String, Integer> byLabel = perHeld.get(gid);
                if (consumeFromMap(uid, byLabel, l))
                    return true;
            }
        }

        // 再尝试已兑换实例
        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null && !perRed.isEmpty()) {
            List<UUID> ids = new ArrayList<>(perRed.keySet());
            ids.sort(UUID::compareTo);
            for (UUID gid : ids) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, gid)) continue;
                if (!canUseSource(uid, gid)) continue;
                Map<String, Integer> byLabel = perRed.get(gid);
                if (consumeFromMap(uid, byLabel, l))
                    return true;
            }
        }

        Map<String, Map<String, Integer>> perAppointment = playerAppointmentAllowedUses.get(uid);
        if (perAppointment != null && !perAppointment.isEmpty() && canUseAppointment(uid)) {
            List<String> ids = new ArrayList<>(perAppointment.keySet());
            ids.sort(String::compareTo);
            for (String appointKey : ids) {
                if (consumeFromMap(uid, perAppointment.get(appointKey), l))
                    return true;
            }
        }

        // 最后尝试全局
        Map<String, Integer> glob = playerGlobalAllowedUses.get(uid);
        if (glob != null) {
            if (!canUseGlobal(uid)) {
                return false;
            }
            return consumeFromMap(uid, glob, l);
        }

        return false;
    }

    /**
     * 尝试消耗已解析来源的一次命令额度。
     */
    public boolean tryConsumeAllowed(UUID uid, ResolvedAllowance resolved) {
        if (uid == null || resolved == null || !uid.equals(resolved.getPlayerId()))
            return false;
        Map<String, Integer> source = getSourceMap(uid, resolved);
        return consumeFromMap(uid, source, resolved.getLabel());
    }

    /**
     * 退还一次命令额度
     */
    public void refundAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return;
        String l = normalizeLabel(label);

        // per-instance first: held then redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map<String, Integer> byLabel : perHeld.values()) {
                if (byLabel.containsKey(l)) {
                    int v = byLabel.getOrDefault(l, 0);
                    if (v < 0)
                        return;
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
                    if (v < 0)
                        return;
                    byLabel.put(l, v + 1);
                    markDirty(uid);
                    return;
                }
            }
        }

        Map<String, Map<String, Integer>> perAppointment = playerAppointmentAllowedUses.get(uid);
        if (perAppointment != null) {
            for (Map<String, Integer> byLabel : perAppointment.values()) {
                if (byLabel.containsKey(l)) {
                    int v = byLabel.getOrDefault(l, 0);
                    if (v < 0)
                        return;
                    byLabel.put(l, v + 1);
                    markDirty(uid);
                    return;
                }
            }
        }

        // global
        Map<String, Integer> glob = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        int v = glob.getOrDefault(l, 0);
        if (v < 0)
            return;
        glob.put(l, v + 1);
        markDirty(uid);
    }

    /**
     * 退还到已解析来源，避免同名命令失败时退错额度池。
     */
    public void refundAllowed(UUID uid, ResolvedAllowance resolved) {
        if (uid == null || resolved == null || !uid.equals(resolved.getPlayerId()))
            return;
        Map<String, Integer> source = getOrCreateSourceMap(uid, resolved);
        if (source == null)
            return;
        String l = resolved.getLabel();
        int v = source.getOrDefault(l, 0);
        if (v < 0)
            return;
        source.put(l, v + 1);
        markDirty(uid);
    }

    /**
     * 获取剩余额度
     */
    public int getRemainingAllowed(UUID uid, String label) {
        if (uid == null || label == null)
            return 0;
        String l = normalizeLabel(label);
        int sum = 0;

        // per-instance: held + redeemed
        Map<UUID, Map<String, Integer>> perHeld = playerGemHeldUses.get(uid);
        if (perHeld != null) {
            for (Map.Entry<UUID, Map<String, Integer>> entry : perHeld.entrySet()) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, entry.getKey())) continue;
                if (!canUseSource(uid, entry.getKey())) continue;
                Integer v2 = entry.getValue().get(l);
                if (v2 != null) {
                    if (v2 < 0)
                        return -1; // 无限
                    sum += v2;
                }
            }
        }

        Map<UUID, Map<String, Integer>> perRed = playerGemRedeemUses.get(uid);
        if (perRed != null) {
            for (Map.Entry<UUID, Map<String, Integer>> entry : perRed.entrySet()) {
                if (isToggledOffCheck != null && isToggledOffCheck.test(uid, entry.getKey())) continue;
                if (!canUseSource(uid, entry.getKey())) continue;
                Integer v2 = entry.getValue().get(l);
                if (v2 != null) {
                    if (v2 < 0)
                        return -1;
                    sum += v2;
                }
            }
        }

        Map<String, Map<String, Integer>> perAppointment = playerAppointmentAllowedUses.get(uid);
        if (perAppointment != null && canUseAppointment(uid)) {
            for (Map<String, Integer> byLabel : perAppointment.values()) {
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
        if (glob != null && canUseGlobal(uid)) {
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
        String l = normalizeLabel(label);

        ResolvedAllowance resolved = resolveAllowedCommand(uid, l);
        if (resolved != null) {
            return resolved.getCommand();
        }

        // 从各个宝石定义中查找
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs != null) {
            for (GemDefinition def : defs) {
                if (def == null || def.getAllowedCommands() == null)
                    continue;
                for (AllowedCommand cmd : def.getAllowedCommands()) {
                    if (cmd != null && cmd.getLabel() != null && normalizeLabel(cmd.getLabel()).equals(l)) {
                        return cmd;
                    }
                }
            }
        }

        Map<String, Map<String, Integer>> appointments = playerAppointmentAllowedUses.get(uid);
        if (appointments != null) {
            List<String> keys = new ArrayList<>(appointments.keySet());
            keys.sort(String::compareTo);
            for (String appointKey : keys) {
                AllowedCommand cmd = findCommandForAppointment(appointKey, l);
                if (cmd != null) {
                    return cmd;
                }
            }
        }

        // 从 redeemAll 中查找
        org.cubexmc.model.PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
        if (redeemAllPower != null && redeemAllPower.getAllowedCommands() != null) {
            for (AllowedCommand cmd : redeemAllPower.getAllowedCommands()) {
                if (cmd != null && cmd.getLabel() != null && normalizeLabel(cmd.getLabel()).equals(l)) {
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
        if (ruleGateFeature != null && !ruleGateFeature.canUsePower(player, def.getGemKey()))
            return;
        List<AllowedCommand> allows = def.getAllowedCommands();
        if (allows == null || allows.isEmpty())
            return;

        UUID uid = player.getUniqueId();
        Map<String, Integer> global = playerGlobalAllowedUses.computeIfAbsent(uid, k -> new HashMap<>());
        for (AllowedCommand ac : allows) {
            if (ac == null || ac.getLabel() == null)
                continue;
            global.put(normalizeLabel(ac.getLabel()), ac.getUses());
        }
        markDirty(uid);
    }

    /**
     * 授予或同步任命来源的命令额度。
     * reset=false 时保留既有剩余次数，只补齐新增命令并移除已不在配置中的命令。
     */
    public void applyAppointmentAllowedCommands(Player player, String appointKey, PowerStructure power, boolean reset) {
        if (player == null || appointKey == null || power == null)
            return;
        if (!canUseAppointment(player.getUniqueId()))
            return;

        UUID uid = player.getUniqueId();
        String sourceKey = normalizeSourceKey(appointKey);
        Map<String, Integer> defaults = buildAllowedMap(power);
        if (defaults.isEmpty()) {
            removeAppointmentAllowedCommands(uid, sourceKey);
            return;
        }

        Map<String, Map<String, Integer>> byAppointment = playerAppointmentAllowedUses.computeIfAbsent(uid,
                unused -> new HashMap<>());
        Map<String, Integer> current = byAppointment.computeIfAbsent(sourceKey, unused -> new HashMap<>());
        if (reset) {
            current.clear();
        } else {
            current.keySet().retainAll(defaults.keySet());
        }
        for (Map.Entry<String, Integer> entry : defaults.entrySet()) {
            current.putIfAbsent(entry.getKey(), entry.getValue());
        }
        markDirty(uid);
    }

    /**
     * 移除指定玩家不再拥有的任命额度来源。
     */
    public void retainAppointmentAllowedCommands(UUID uid, Set<String> activeAppointmentKeys) {
        if (uid == null)
            return;
        Map<String, Map<String, Integer>> byAppointment = playerAppointmentAllowedUses.get(uid);
        if (byAppointment == null || byAppointment.isEmpty())
            return;

        Set<String> active = new HashSet<>();
        if (activeAppointmentKeys != null) {
            for (String key : activeAppointmentKeys) {
                if (key != null && !key.isBlank()) {
                    active.add(normalizeSourceKey(key));
                }
            }
        }

        boolean changed = byAppointment.keySet().removeIf(key -> !active.contains(key));
        if (byAppointment.isEmpty()) {
            playerAppointmentAllowedUses.remove(uid);
        }
        if (changed) {
            markDirty(uid);
        }
    }

    public void removeAppointmentAllowedCommands(UUID uid, String appointKey) {
        if (uid == null || appointKey == null)
            return;
        Map<String, Map<String, Integer>> byAppointment = playerAppointmentAllowedUses.get(uid);
        if (byAppointment == null)
            return;
        String sourceKey = normalizeSourceKey(appointKey);
        if (byAppointment.remove(sourceKey) != null) {
            if (byAppointment.isEmpty()) {
                playerAppointmentAllowedUses.remove(uid);
            }
            markDirty(uid);
        }
    }

    /**
     * 重新分配持有实例额度（宝石转手时）
     */
    public void reassignHeldInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def) {
        if (gemId == null || newOwner == null || def == null)
            return;
        if (ruleGateFeature != null) {
            Player player = Bukkit.getPlayer(newOwner);
            if (player != null && !ruleGateFeature.canUsePower(player, def.getGemKey()))
                return;
        }

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
        if (oldOwner != null)
            invalidateLabelIndex(oldOwner);
    }

    /**
     * 重新分配兑换实例额度
     */
    public void reassignRedeemInstanceAllowance(UUID gemId, UUID newOwner, GemDefinition def,
            boolean resetEvenIfSameOwner) {
        if (gemId == null || newOwner == null || def == null)
            return;
        if (ruleGateFeature != null) {
            Player player = Bukkit.getPlayer(newOwner);
            if (player != null && !ruleGateFeature.canUsePower(player, def.getGemKey()))
                return;
        }

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
        if (oldOwner != null)
            invalidateLabelIndex(oldOwner);
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
                map.put(normalizeLabel(ac.getLabel()), ac.getUses());
            }
        }
        return map;
    }

    private Map<String, Integer> buildAllowedMap(PowerStructure power) {
        Map<String, Integer> map = new HashMap<>();
        if (power == null || power.getAllowedCommands() == null) {
            return map;
        }
        for (AllowedCommand ac : power.getAllowedCommands()) {
            if (ac == null || ac.getLabel() == null)
                continue;
            map.put(normalizeLabel(ac.getLabel()), ac.getUses());
        }
        return map;
    }

    // ==================== 可用标签查询 ====================

    /**
     * 获取玩家所有可用的命令标签（使用反向索引缓存）
     */
    public Set<String> getAvailableCommandLabels(UUID uid) {
        if (uid == null)
            return new HashSet<>();
        if (labelIndexDirtyPlayers.contains(uid) || !labelIndexCache.containsKey(uid)) {
            Set<String> labels = rebuildLabelIndex(uid);
            labelIndexCache.put(uid, labels);
            labelIndexDirtyPlayers.remove(uid);
        }
        return new HashSet<>(labelIndexCache.get(uid));
    }

    private Set<String> rebuildLabelIndex(UUID uid) {
        Set<String> labels = new HashSet<>();
        collectActiveLabelsFromNestedMap(uid, labels, playerGemHeldUses.get(uid));
        collectActiveLabelsFromNestedMap(uid, labels, playerGemRedeemUses.get(uid));
        collectActiveLabelsFromStringNestedMap(uid, labels, playerAppointmentAllowedUses.get(uid));
        if (canUseGlobal(uid)) {
            collectActiveLabelsFromFlatMap(labels, playerGlobalAllowedUses.get(uid));
        }
        return labels;
    }

    private void invalidateLabelIndex(UUID uid) {
        if (uid != null)
            labelIndexDirtyPlayers.add(uid);
    }

    private void collectActiveLabelsFromNestedMap(UUID uid, Set<String> labels,
            Map<UUID, Map<String, Integer>> nested) {
        if (nested == null || nested.isEmpty())
            return;
        for (Map.Entry<UUID, Map<String, Integer>> entry : nested.entrySet()) {
            if (isToggledOffCheck != null && isToggledOffCheck.test(uid, entry.getKey())) continue;
            if (!canUseSource(uid, entry.getKey())) continue;
            collectActiveLabelsFromFlatMap(labels, entry.getValue());
        }
    }

    private void collectActiveLabelsFromStringNestedMap(UUID uid, Set<String> labels,
            Map<String, Map<String, Integer>> nested) {
        if (nested == null || nested.isEmpty() || !canUseAppointment(uid))
            return;
        for (Map<String, Integer> byLabel : nested.values()) {
            collectActiveLabelsFromFlatMap(labels, byLabel);
        }
    }

    private void collectActiveLabelsFromFlatMap(Set<String> labels, Map<String, Integer> map) {
        if (map == null || map.isEmpty())
            return;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String key = entry.getKey();
            Integer remaining = entry.getValue();
            if (key == null || key.isBlank() || remaining == null)
                continue;
            if (remaining == 0)
                continue;
            String base = key.split(" ")[0].toLowerCase(ROOT_LOCALE);
            if (!base.isEmpty())
                labels.add(base);
        }
    }

    private ResolvedAllowance resolveFromGemMap(UUID uid, String label, Map<UUID, Map<String, Integer>> perGem,
            AllowanceSourceType sourceType) {
        if (perGem == null || perGem.isEmpty())
            return null;
        List<UUID> ids = new ArrayList<>(perGem.keySet());
        ids.sort(UUID::compareTo);
        for (UUID gid : ids) {
            if (isToggledOffCheck != null && isToggledOffCheck.test(uid, gid))
                continue;
            if (!canUseSource(uid, gid))
                continue;
            if (!hasRemaining(perGem.get(gid), label))
                continue;
            AllowedCommand command = findCommandForGem(gid, label);
            if (command != null) {
                String sourceKey = gemKeyLookup != null ? gemKeyLookup.apply(gid) : null;
                return new ResolvedAllowance(uid, sourceType, gid, sourceKey, label, command);
            }
        }
        return null;
    }

    private ResolvedAllowance resolveFromAppointments(UUID uid, String label) {
        Map<String, Map<String, Integer>> perAppointment = playerAppointmentAllowedUses.get(uid);
        if (perAppointment == null || perAppointment.isEmpty() || !canUseAppointment(uid))
            return null;
        List<String> keys = new ArrayList<>(perAppointment.keySet());
        keys.sort(String::compareTo);
        for (String appointKey : keys) {
            if (!hasRemaining(perAppointment.get(appointKey), label))
                continue;
            AllowedCommand command = findCommandForAppointment(appointKey, label);
            if (command != null) {
                return new ResolvedAllowance(uid, AllowanceSourceType.APPOINTMENT, null, appointKey, label, command);
            }
        }
        return null;
    }

    private ResolvedAllowance resolveFromGlobal(UUID uid, String label) {
        Map<String, Integer> global = playerGlobalAllowedUses.get(uid);
        if (global == null || !canUseGlobal(uid) || !hasRemaining(global, label))
            return null;
        AllowedCommand command = findCommandForRedeemAll(label);
        return command != null
                ? new ResolvedAllowance(uid, AllowanceSourceType.GLOBAL, null, "all", label, command)
                : null;
    }

    private boolean hasRemaining(Map<String, Integer> byLabel, String label) {
        if (byLabel == null)
            return false;
        Integer v = byLabel.get(label);
        return v != null && (v > 0 || v < 0);
    }

    private boolean consumeFromMap(UUID uid, Map<String, Integer> byLabel, String label) {
        if (byLabel == null)
            return false;
        int v = byLabel.getOrDefault(label, 0);
        if (v < 0) {
            markDirty(uid);
            return true;
        }
        if (v > 0) {
            byLabel.put(label, v - 1);
            markDirty(uid);
            return true;
        }
        return false;
    }

    private Map<String, Integer> getSourceMap(UUID uid, ResolvedAllowance resolved) {
        if (resolved == null)
            return null;
        switch (resolved.getSourceType()) {
            case HELD:
                return getNestedGemMap(playerGemHeldUses.get(uid), resolved.getGemId());
            case REDEEMED:
                return getNestedGemMap(playerGemRedeemUses.get(uid), resolved.getGemId());
            case APPOINTMENT:
                return getNestedStringMap(playerAppointmentAllowedUses.get(uid), resolved.getSourceKey());
            case GLOBAL:
                return playerGlobalAllowedUses.get(uid);
            default:
                return null;
        }
    }

    private Map<String, Integer> getOrCreateSourceMap(UUID uid, ResolvedAllowance resolved) {
        if (resolved == null)
            return null;
        switch (resolved.getSourceType()) {
            case HELD:
                if (resolved.getGemId() == null)
                    return null;
                return playerGemHeldUses.computeIfAbsent(uid, unused -> new HashMap<>())
                        .computeIfAbsent(resolved.getGemId(), unused -> new HashMap<>());
            case REDEEMED:
                if (resolved.getGemId() == null)
                    return null;
                return playerGemRedeemUses.computeIfAbsent(uid, unused -> new HashMap<>())
                        .computeIfAbsent(resolved.getGemId(), unused -> new HashMap<>());
            case APPOINTMENT:
                if (resolved.getSourceKey() == null)
                    return null;
                return playerAppointmentAllowedUses.computeIfAbsent(uid, unused -> new HashMap<>())
                        .computeIfAbsent(normalizeSourceKey(resolved.getSourceKey()), unused -> new HashMap<>());
            case GLOBAL:
                return playerGlobalAllowedUses.computeIfAbsent(uid, unused -> new HashMap<>());
            default:
                return null;
        }
    }

    private Map<String, Integer> getNestedGemMap(Map<UUID, Map<String, Integer>> nested, UUID gemId) {
        return nested != null && gemId != null ? nested.get(gemId) : null;
    }

    private Map<String, Integer> getNestedStringMap(Map<String, Map<String, Integer>> nested, String sourceKey) {
        return nested != null && sourceKey != null ? nested.get(normalizeSourceKey(sourceKey)) : null;
    }

    private AllowedCommand findCommandForGem(UUID gemId, String label) {
        String gemKey = gemKeyLookup != null ? gemKeyLookup.apply(gemId) : null;
        if (gemKey != null) {
            GemDefinition def = findGemDefinitionByKey(gemKey);
            AllowedCommand command = def != null ? findCommand(def.getAllowedCommands(), label) : null;
            if (command != null) {
                return command;
            }
        }
        return findFirstGemCommand(label);
    }

    private GemDefinition findGemDefinitionByKey(String gemKey) {
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs == null || gemKey == null)
            return null;
        for (GemDefinition def : defs) {
            if (def != null && def.getGemKey() != null && def.getGemKey().equalsIgnoreCase(gemKey)) {
                return def;
            }
        }
        return null;
    }

    private AllowedCommand findFirstGemCommand(String label) {
        List<GemDefinition> defs = gemParser.getGemDefinitions();
        if (defs == null)
            return null;
        for (GemDefinition def : defs) {
            if (def == null)
                continue;
            AllowedCommand command = findCommand(def.getAllowedCommands(), label);
            if (command != null) {
                return command;
            }
        }
        return null;
    }

    private AllowedCommand findCommandForAppointment(String appointKey, String label) {
        if (appointmentPowerLookup == null || appointKey == null)
            return null;
        PowerStructure power = appointmentPowerLookup.apply(appointKey);
        return power != null ? findCommand(power.getAllowedCommands(), label) : null;
    }

    private AllowedCommand findCommandForRedeemAll(String label) {
        PowerStructure redeemAllPower = gameplayConfig.getRedeemAllPowerStructure();
        return redeemAllPower != null ? findCommand(redeemAllPower.getAllowedCommands(), label) : null;
    }

    private AllowedCommand findCommand(List<AllowedCommand> commands, String label) {
        if (commands == null)
            return null;
        for (AllowedCommand cmd : commands) {
            if (cmd != null && cmd.getLabel() != null && normalizeLabel(cmd.getLabel()).equals(label)) {
                return cmd;
            }
        }
        return null;
    }

    private String normalizeLabel(String label) {
        return label == null ? "" : label.trim().toLowerCase(ROOT_LOCALE);
    }

    private String normalizeSourceKey(String sourceKey) {
        return sourceKey == null ? "" : sourceKey.trim().toLowerCase(ROOT_LOCALE);
    }

    private void save() {
        if (saveCallback != null) {
            saveCallback.run();
        }
        dirty = false;
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

    private boolean canUseSource(UUID playerId, UUID gemId) {
        if (ruleGateFeature == null) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return true;
        }
        String gemKey = gemKeyLookup != null ? gemKeyLookup.apply(gemId) : null;
        return ruleGateFeature.canUsePower(player, gemKey);
    }

    private boolean canUseGlobal(UUID playerId) {
        if (ruleGateFeature == null) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player == null || ruleGateFeature.canUsePower(player);
    }

    private boolean canUseAppointment(UUID playerId) {
        return canUseGlobal(playerId);
    }
}
