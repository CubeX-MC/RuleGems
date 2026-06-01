package org.cubexmc.features.revoke;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.RuleGems;
import org.cubexmc.features.Feature;
import org.cubexmc.manager.GemManager;
import org.cubexmc.model.GemDefinition;

public class RevokeFeature extends Feature {

    private static final Locale ROOT_LOCALE = Locale.ROOT;

    private final GemManager gemManager;
    private final LongSupplier clock;
    private final Map<String, RevokeRule> rules = new LinkedHashMap<>();
    private final Map<UUID, RevokeConfirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownUntil = new ConcurrentHashMap<>();
    private int confirmTimeoutSeconds = 30;
    private File dataFile;

    public RevokeFeature(RuleGems plugin, GemManager gemManager) {
        this(plugin, gemManager, System::currentTimeMillis);
    }

    RevokeFeature(RuleGems plugin, GemManager gemManager, LongSupplier clock) {
        super(plugin, "rulegems.revoke");
        this.gemManager = gemManager;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    @Override
    public void initialize() {
        reload();
    }

    @Override
    public void shutdown() {
        confirmations.clear();
        saveData();
    }

    @Override
    public void reload() {
        File configFile = new File(plugin.getDataFolder(), "features/revoke.yml");
        if (!configFile.exists()) {
            plugin.saveResource("features/revoke.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        this.enabled = config.getBoolean("enabled", false);
        this.confirmTimeoutSeconds = Math.max(1, config.getInt("confirm_timeout", 30));
        loadRules(config.getConfigurationSection("rules"));

        this.dataFile = new File(plugin.getDataFolder(), "data/revokes.yml");
        loadData();

        if (enabled) {
            plugin.getLogger().info("Revoke feature enabled with " + rules.size() + " rules.");
        }
    }

    private void loadRules(ConfigurationSection section) {
        rules.clear();
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection ruleSection = section.getConfigurationSection(key);
            if (ruleSection == null) {
                continue;
            }
            String triggerGem = ruleSection.getString("trigger_gem");
            List<String> targetPowers = ruleSection.getStringList("target_powers");
            if (triggerGem == null || triggerGem.isBlank() || targetPowers.isEmpty()) {
                plugin.getLogger().warning("Skipping revoke rule '" + key + "': trigger_gem and target_powers are required.");
                continue;
            }
            RevokeRule rule = new RevokeRule(
                    key,
                    ruleSection.getString("display_name", key),
                    triggerGem,
                    targetPowers,
                    ruleSection.getBoolean("require_held", true),
                    ruleSection.getBoolean("consume_gem", false),
                    ruleSection.getLong("cooldown", 0L),
                    ruleSection.getBoolean("confirm_required", true),
                    ruleSection.getBoolean("broadcast", true),
                    ruleSection.getBoolean("allow_offline_target", false));
            rules.put(normalize(key), rule);
        }
    }

    public Map<String, RevokeRule> getRules() {
        return Collections.unmodifiableMap(rules);
    }

    public List<RevokeRule> getAvailableRules(Player actor) {
        if (!enabled || actor == null || !hasPermission(actor)) {
            return Collections.emptyList();
        }
        List<RevokeRule> available = new ArrayList<>();
        for (RevokeRule rule : rules.values()) {
            if (canActorUseRule(actor, rule)) {
                available.add(rule);
            }
        }
        return available;
    }

    public List<String> getRevokablePowers(Player actor, UUID targetUuid, Iterable<String> candidatePowers) {
        if (targetUuid == null || candidatePowers == null) {
            return Collections.emptyList();
        }
        List<RevokeRule> availableRules = getAvailableRules(actor);
        if (availableRules.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String candidate : candidatePowers) {
            String normalized = normalize(candidate);
            if (normalized.isEmpty() || !targetHasPower(targetUuid, normalized)) {
                continue;
            }
            for (RevokeRule rule : availableRules) {
                if (rule.canTargetPower(normalized)) {
                    result.add(normalized);
                    break;
                }
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public RevokeResult requestRevoke(Player actor, String ruleKey, String targetName, String powerKey) {
        RevokeContext context = validateRequest(actor, ruleKey, targetName, powerKey);
        if (context.result != null) {
            return context.result;
        }
        if (context.rule.isConfirmRequired()) {
            RevokeConfirmation confirmation = new RevokeConfirmation(
                    context.rule.getKey(), context.targetUuid, context.targetName, context.powerKey,
                    nowMillis() + confirmTimeoutSeconds * 1000L);
            confirmations.put(actor.getUniqueId(), confirmation);
            return RevokeResult.of(RevokeResult.Status.CONFIRMATION_REQUIRED,
                    placeholders(context.rule, context.targetName, context.targetUuid, context.powerKey, 0L));
        }
        return executeRevoke(actor, context);
    }

    public RevokeResult confirm(Player actor) {
        RevokeConfirmation confirmation = confirmations.remove(actor.getUniqueId());
        if (confirmation == null) {
            return RevokeResult.of(RevokeResult.Status.NO_PENDING_CONFIRMATION);
        }
        long now = nowMillis();
        if (confirmation.isExpired(now)) {
            return RevokeResult.of(RevokeResult.Status.CONFIRMATION_EXPIRED);
        }
        RevokeContext context = validateRequest(actor, confirmation.getRuleKey(),
                confirmation.getTargetName(), confirmation.getPowerKey());
        if (context.result != null) {
            return context.result;
        }
        return executeRevoke(actor, context);
    }

    public RevokeResult cancel(Player actor) {
        RevokeConfirmation removed = confirmations.remove(actor.getUniqueId());
        return removed == null
                ? RevokeResult.of(RevokeResult.Status.NO_PENDING_CONFIRMATION)
                : RevokeResult.of(RevokeResult.Status.CANCELLED);
    }

    public RevokeResult listRules() {
        Map<String, String> placeholders = new HashMap<>();
        if (rules.isEmpty()) {
            placeholders.put("rules", "");
        } else {
            List<String> lines = new ArrayList<>();
            for (RevokeRule rule : rules.values()) {
                lines.add(rule.getKey() + " -> " + String.join(", ", rule.getTargetPowers()));
            }
            placeholders.put("rules", String.join("\n", lines));
        }
        return RevokeResult.of(RevokeResult.Status.LIST, placeholders);
    }

    private RevokeContext validateRequest(Player actor, String ruleKey, String targetName, String powerKey) {
        RevokeContext context = new RevokeContext();
        if (!enabled) {
            context.result = RevokeResult.of(RevokeResult.Status.DISABLED);
            return context;
        }
        RevokeRule rule = rules.get(normalize(ruleKey));
        if (rule == null) {
            context.result = RevokeResult.of(RevokeResult.Status.RULE_NOT_FOUND,
                    Map.of("rule", String.valueOf(ruleKey)));
            return context;
        }
        String normalizedPower = normalize(powerKey);
        if (!rule.canTargetPower(normalizedPower)) {
            context.result = RevokeResult.of(RevokeResult.Status.POWER_NOT_ALLOWED,
                    Map.of("rule", rule.getKey(), "power", String.valueOf(powerKey)));
            return context;
        }

        Target target = resolveTarget(targetName, rule);
        if (target.status != null) {
            context.result = RevokeResult.of(target.status, Map.of("player", String.valueOf(targetName)));
            return context;
        }
        if (!targetHasPower(target.uuid, normalizedPower)) {
            context.result = RevokeResult.of(RevokeResult.Status.TARGET_HAS_NO_POWER,
                    Map.of("player", target.name, "power", normalizedPower));
            return context;
        }

        long remaining = cooldownRemainingSeconds(actor.getUniqueId(), rule);
        if (remaining > 0L) {
            context.result = RevokeResult.of(RevokeResult.Status.COOLDOWN,
                    Map.of("rule", rule.getKey(), "seconds", String.valueOf(remaining)));
            return context;
        }

        UUID triggerGemId = findTriggerGem(actor, rule);
        if (triggerGemId == null && !hasRedeemedOrOwned(actor.getUniqueId(), rule.getTriggerGem())) {
            context.result = RevokeResult.of(RevokeResult.Status.MISSING_TRIGGER,
                    Map.of("gem", rule.getTriggerGem(), "rule", rule.getKey()));
            return context;
        }
        if ((rule.isRequireHeld() || rule.isConsumeGem()) && triggerGemId == null) {
            context.result = RevokeResult.of(RevokeResult.Status.MISSING_TRIGGER,
                    Map.of("gem", rule.getTriggerGem(), "rule", rule.getKey()));
            return context;
        }

        context.rule = rule;
        context.targetUuid = target.uuid;
        context.targetName = target.name;
        context.powerKey = normalizedPower;
        context.triggerGemId = triggerGemId;
        return context;
    }

    private RevokeResult executeRevoke(Player actor, RevokeContext context) {
        int revokedInstances = revokeTargetPower(context.targetUuid, context.powerKey);
        if (revokedInstances <= 0) {
            return RevokeResult.of(RevokeResult.Status.TARGET_HAS_NO_POWER,
                    Map.of("player", context.targetName, "power", context.powerKey));
        }
        if (context.rule.isConsumeGem() && context.triggerGemId != null) {
            gemManager.getStateManager().removeGemItemFromInventory(actor, context.triggerGemId);
            gemManager.getStateManager().getGemUuidToHolder().remove(context.triggerGemId);
            gemManager.getPlacementManager().randomPlaceGem(context.triggerGemId);
            gemManager.recalculateGrants(actor);
        }
        setCooldown(actor.getUniqueId(), context.rule);
        gemManager.saveGems();

        if (plugin.getHistoryLogger() != null) {
            GemDefinition def = gemManager.findGemDefinitionByKey(context.powerKey);
            plugin.getHistoryLogger().logPermissionRevoke(
                    context.targetUuid.toString(), context.targetName, context.powerKey,
                    def != null ? def.getDisplayName() : context.powerKey,
                    def != null ? def.getPermissions() : Collections.emptyList(),
                    def != null ? def.getVaultGroup() : null,
                    "Revoke rule " + context.rule.getKey() + " by " + actor.getName());
        }

        return RevokeResult.of(RevokeResult.Status.SUCCESS,
                placeholders(context.rule, context.targetName, context.targetUuid, context.powerKey, 0L));
    }

    private int revokeTargetPower(UUID targetUuid, String powerKey) {
        GemDefinition def = gemManager.findGemDefinitionByKey(powerKey);
        List<UUID> matchingGemIds = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : gemManager.getPermissionManager().getGemIdToRedeemer().entrySet()) {
            if (!targetUuid.equals(entry.getValue())) {
                continue;
            }
            String gemKey = gemManager.getStateManager().getGemKey(entry.getKey());
            if (powerKey.equalsIgnoreCase(gemKey)) {
                matchingGemIds.add(entry.getKey());
            }
        }
        for (UUID gemId : matchingGemIds) {
            gemManager.getPermissionManager().decrementOwnerKeyCount(targetUuid, powerKey, def);
            gemManager.getPermissionManager().getGemIdToRedeemer().remove(gemId, targetUuid);
            gemManager.getAllowanceManager().removeRedeemInstanceAllowance(targetUuid, gemId);
        }
        Map<String, Integer> counts = gemManager.getPermissionManager().getOwnerKeyCount().get(targetUuid);
        if (counts == null || counts.getOrDefault(powerKey, 0) <= 0) {
            java.util.Set<String> redeemed = gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys()
                    .get(targetUuid);
            if (redeemed != null) {
                redeemed.remove(powerKey);
                if (redeemed.isEmpty()) {
                    gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().remove(targetUuid);
                }
            }
        }
        return matchingGemIds.size();
    }

    private Target resolveTarget(String targetName, RevokeRule rule) {
        Player online = Bukkit.getPlayer(targetName);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), null);
        }
        if (!rule.isAllowOfflineTarget()) {
            return new Target(null, targetName, RevokeResult.Status.TARGET_OFFLINE_NOT_ALLOWED);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (offline == null || offline.getUniqueId() == null || (!offline.hasPlayedBefore() && offline.getName() == null)) {
            return new Target(null, targetName, RevokeResult.Status.TARGET_NOT_FOUND);
        }
        return new Target(offline.getUniqueId(),
                offline.getName() != null ? offline.getName() : targetName,
                null);
    }

    private boolean targetHasPower(UUID targetUuid, String powerKey) {
        Map<String, Integer> counts = gemManager.getPermissionManager().getOwnerKeyCount()
                .getOrDefault(targetUuid, Collections.emptyMap());
        if (counts.getOrDefault(powerKey, 0) > 0) {
            return true;
        }
        return gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys()
                .getOrDefault(targetUuid, Collections.emptySet()).contains(powerKey);
    }

    private UUID findTriggerGem(Player player, RevokeRule rule) {
        String triggerKey = normalize(rule.getTriggerGem());
        for (ItemStack item : player.getInventory().getContents()) {
            UUID id = getMatchingHeldGemId(item, triggerKey);
            if (id != null) {
                return id;
            }
        }
        return getMatchingHeldGemId(player.getInventory().getItemInOffHand(), triggerKey);
    }

    private UUID getMatchingHeldGemId(ItemStack item, String triggerKey) {
        if (!gemManager.getStateManager().isRuleGem(item)) {
            return null;
        }
        UUID gemId = gemManager.getStateManager().getGemUUID(item);
        String gemKey = gemManager.getStateManager().getGemKey(gemId);
        return triggerKey.equals(normalize(gemKey)) ? gemId : null;
    }

    private boolean hasRedeemedOrOwned(UUID playerUuid, String gemKey) {
        String normalized = normalize(gemKey);
        if (gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys()
                .getOrDefault(playerUuid, Collections.emptySet()).contains(normalized)) {
            return true;
        }
        return gemManager.getPermissionManager().getOwnerKeyCount()
                .getOrDefault(playerUuid, Collections.emptyMap()).getOrDefault(normalized, 0) > 0;
    }

    private boolean canActorUseRule(Player actor, RevokeRule rule) {
        UUID triggerGemId = findTriggerGem(actor, rule);
        if (triggerGemId != null) {
            return true;
        }
        return !rule.isRequireHeld() && !rule.isConsumeGem()
                && hasRedeemedOrOwned(actor.getUniqueId(), rule.getTriggerGem());
    }

    private long cooldownRemainingSeconds(UUID playerUuid, RevokeRule rule) {
        long until = cooldownUntil.getOrDefault(playerUuid, Collections.emptyMap())
                .getOrDefault(rule.getKey(), 0L);
        long remainingMillis = until - nowMillis();
        return remainingMillis <= 0L ? 0L : (remainingMillis + 999L) / 1000L;
    }

    private void setCooldown(UUID playerUuid, RevokeRule rule) {
        if (rule.getCooldownSeconds() <= 0L) {
            return;
        }
        long until = nowMillis() + rule.getCooldownSeconds() * 1000L;
        cooldownUntil.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>())
                .put(rule.getKey(), until);
        saveData();
    }

    private Map<String, String> placeholders(RevokeRule rule, String targetName, UUID targetUuid, String powerKey,
            long seconds) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("rule", rule.getKey());
        placeholders.put("rule_name", rule.getDisplayName());
        placeholders.put("player", targetName);
        placeholders.put("target", targetName);
        placeholders.put("target_uuid", targetUuid.toString().substring(0, 8));
        placeholders.put("power", powerKey);
        placeholders.put("consume", String.valueOf(rule.isConsumeGem()));
        placeholders.put("broadcast", String.valueOf(rule.isBroadcast()));
        placeholders.put("seconds", String.valueOf(seconds));
        return placeholders;
    }

    private void loadData() {
        cooldownUntil.clear();
        if (dataFile == null || !dataFile.exists()) {
            return;
        }
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection cooldowns = data.getConfigurationSection("cooldowns");
        if (cooldowns == null) {
            return;
        }
        for (String uuidStr : cooldowns.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection playerSection = cooldowns.getConfigurationSection(uuidStr);
                if (playerSection == null) {
                    continue;
                }
                Map<String, Long> values = new ConcurrentHashMap<>();
                for (String rule : playerSection.getKeys(false)) {
                    values.put(rule, playerSection.getLong(rule));
                }
                cooldownUntil.put(uuid, values);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load revoke cooldown entry '" + uuidStr + "': " + e.getMessage());
            }
        }
    }

    private void saveData() {
        if (dataFile == null) {
            return;
        }
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Long>> entry : cooldownUntil.entrySet()) {
            for (Map.Entry<String, Long> cooldown : entry.getValue().entrySet()) {
                data.set("cooldowns." + entry.getKey() + "." + cooldown.getKey(), cooldown.getValue());
            }
        }
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save revoke cooldown data: " + e.getMessage());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(ROOT_LOCALE);
    }

    private long nowMillis() {
        return clock.getAsLong();
    }

    private static class RevokeContext {
        RevokeResult result;
        RevokeRule rule;
        UUID targetUuid;
        String targetName;
        String powerKey;
        UUID triggerGemId;
    }

    private static class Target {
        final UUID uuid;
        final String name;
        final RevokeResult.Status status;

        Target(UUID uuid, String name, RevokeResult.Status status) {
            this.uuid = uuid;
            this.name = name;
            this.status = status;
        }
    }
}
