package org.cubexmc.features.revoke

import java.util.UUID

class RevokeConfirmation(
    val ruleKey: String,
    val targetUuid: UUID,
    val targetName: String,
    val powerKey: String,
    val expiresAtMillis: Long,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis > expiresAtMillis
}
