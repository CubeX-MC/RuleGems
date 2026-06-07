package org.cubexmc.model

import java.util.Collections
import java.util.UUID

/**
 * Result of redeem precondition evaluation.
 */
class RedeemRequirementResult private constructor(
    val isAllowed: Boolean,
    val message: String?,
    consumedGemIds: List<UUID>?,
    val matchedRecipe: RedeemRecipe?,
    val isMessageLanguageKey: Boolean,
    placeholders: Map<String, String>?,
) {
    val placeholders: Map<String, String> =
        if (placeholders == null) emptyMap() else Collections.unmodifiableMap(placeholders)
    val consumedGemIds: List<UUID> =
        if (consumedGemIds == null) emptyList() else Collections.unmodifiableList(consumedGemIds)

    companion object {
        @JvmField
        val ALLOWED: RedeemRequirementResult = RedeemRequirementResult(true, null, emptyList(), null, false, emptyMap())

        @JvmStatic
        fun denied(message: String?, messageIsLanguageKey: Boolean): RedeemRequirementResult =
            denied(message, messageIsLanguageKey, emptyMap())

        @JvmStatic
        fun denied(
            message: String?,
            messageIsLanguageKey: Boolean,
            placeholders: Map<String, String>?,
        ): RedeemRequirementResult =
            RedeemRequirementResult(false, message, emptyList(), null, messageIsLanguageKey, placeholders)

        @JvmStatic
        fun allowed(consumedGemIds: List<UUID>?): RedeemRequirementResult = allowed(consumedGemIds, null)

        @JvmStatic
        fun allowed(consumedGemIds: List<UUID>?, matchedRecipe: RedeemRecipe?): RedeemRequirementResult {
            if (consumedGemIds.isNullOrEmpty()) {
                return if (matchedRecipe == null) {
                    ALLOWED
                } else {
                    RedeemRequirementResult(true, null, emptyList(), matchedRecipe, false, emptyMap())
                }
            }
            return RedeemRequirementResult(true, null, consumedGemIds, matchedRecipe, false, emptyMap())
        }
    }
}
