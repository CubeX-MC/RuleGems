package org.cubexmc.features.revoke;

import java.util.Collections;
import java.util.List;

public class RevokeRule {
    private final String key;
    private final String displayName;
    private final String triggerGem;
    private final List<String> targetPowers;
    private final boolean requireHeld;
    private final boolean consumeGem;
    private final long cooldownSeconds;
    private final boolean confirmRequired;
    private final boolean broadcast;
    private final boolean allowOfflineTarget;

    public RevokeRule(String key, String displayName, String triggerGem, List<String> targetPowers,
            boolean requireHeld, boolean consumeGem, long cooldownSeconds, boolean confirmRequired,
            boolean broadcast, boolean allowOfflineTarget) {
        this.key = key;
        this.displayName = displayName == null || displayName.isBlank() ? key : displayName;
        this.triggerGem = triggerGem;
        this.targetPowers = targetPowers == null ? Collections.emptyList() : Collections.unmodifiableList(targetPowers);
        this.requireHeld = requireHeld;
        this.consumeGem = consumeGem;
        this.cooldownSeconds = Math.max(0L, cooldownSeconds);
        this.confirmRequired = confirmRequired;
        this.broadcast = broadcast;
        this.allowOfflineTarget = allowOfflineTarget;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getTriggerGem() { return triggerGem; }
    public List<String> getTargetPowers() { return targetPowers; }
    public boolean isRequireHeld() { return requireHeld; }
    public boolean isConsumeGem() { return consumeGem; }
    public long getCooldownSeconds() { return cooldownSeconds; }
    public boolean isConfirmRequired() { return confirmRequired; }
    public boolean isBroadcast() { return broadcast; }
    public boolean isAllowOfflineTarget() { return allowOfflineTarget; }

    public boolean canTargetPower(String power) {
        if (power == null) {
            return false;
        }
        for (String target : targetPowers) {
            if (power.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
