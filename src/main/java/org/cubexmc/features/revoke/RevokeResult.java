package org.cubexmc.features.revoke;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RevokeResult {
    public enum Status {
        SUCCESS,
        CONFIRMATION_REQUIRED,
        LIST,
        DISABLED,
        RULE_NOT_FOUND,
        POWER_NOT_ALLOWED,
        TARGET_NOT_FOUND,
        TARGET_OFFLINE_NOT_ALLOWED,
        TARGET_HAS_NO_POWER,
        MISSING_TRIGGER,
        COOLDOWN,
        NO_PENDING_CONFIRMATION,
        CANCELLED,
        CONFIRMATION_EXPIRED
    }

    private final Status status;
    private final Map<String, String> placeholders;

    private RevokeResult(Status status, Map<String, String> placeholders) {
        this.status = status;
        this.placeholders = placeholders == null ? Collections.emptyMap() : Collections.unmodifiableMap(placeholders);
    }

    public static RevokeResult of(Status status) {
        return new RevokeResult(status, Collections.emptyMap());
    }

    public static RevokeResult of(Status status, Map<String, String> placeholders) {
        return new RevokeResult(status, new HashMap<>(placeholders));
    }

    public Status getStatus() {
        return status;
    }

    public Map<String, String> getPlaceholders() {
        return placeholders;
    }
}
