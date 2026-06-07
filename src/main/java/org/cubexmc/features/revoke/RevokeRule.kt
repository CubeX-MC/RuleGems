package org.cubexmc.features.revoke

import java.util.Collections
import kotlin.math.max

class RevokeRule(
    val key: String,
    displayName: String?,
    val triggerGem: String?,
    targetPowers: List<String>?,
    val isRequireHeld: Boolean,
    val isConsumeGem: Boolean,
    cooldownSeconds: Long,
    val isConfirmRequired: Boolean,
    val isBroadcast: Boolean,
    val isAllowOfflineTarget: Boolean,
) {
    val displayName: String = if (displayName.isNullOrBlank()) key else displayName
    val targetPowers: List<String> =
        if (targetPowers == null) emptyList() else Collections.unmodifiableList(targetPowers)
    val cooldownSeconds: Long = max(0L, cooldownSeconds)

    fun canTargetPower(power: String?): Boolean {
        if (power == null) return false
        for (target in targetPowers) {
            if (power.equals(target, ignoreCase = true)) return true
        }
        return false
    }
}
