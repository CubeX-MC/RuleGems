package org.cubexmc.features.revoke;

import java.util.UUID;

public class RevokeConfirmation {
    private final String ruleKey;
    private final UUID targetUuid;
    private final String targetName;
    private final String powerKey;
    private final long expiresAtMillis;

    public RevokeConfirmation(String ruleKey, UUID targetUuid, String targetName, String powerKey,
            long expiresAtMillis) {
        this.ruleKey = ruleKey;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.powerKey = powerKey;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getRuleKey() { return ruleKey; }
    public UUID getTargetUuid() { return targetUuid; }
    public String getTargetName() { return targetName; }
    public String getPowerKey() { return powerKey; }
    public long getExpiresAtMillis() { return expiresAtMillis; }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
