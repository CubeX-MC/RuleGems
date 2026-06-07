package org.cubexmc.features.revoke

import java.util.Collections

class RevokeResult private constructor(
    val status: Status,
    placeholders: Map<String, String>?,
) {
    enum class Status {
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
        CONFIRMATION_EXPIRED,
    }

    val placeholders: Map<String, String> =
        if (placeholders == null) emptyMap() else Collections.unmodifiableMap(placeholders)

    companion object {
        @JvmStatic
        fun of(status: Status): RevokeResult = RevokeResult(status, emptyMap())

        @JvmStatic
        fun of(status: Status, placeholders: Map<String, String>): RevokeResult =
            RevokeResult(status, HashMap(placeholders))
    }
}
