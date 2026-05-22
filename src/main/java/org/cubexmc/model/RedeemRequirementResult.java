package org.cubexmc.model;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Result of redeem precondition evaluation.
 */
public class RedeemRequirementResult {
    public static final RedeemRequirementResult ALLOWED = new RedeemRequirementResult(true, null,
            Collections.emptyList(), null, false, Collections.emptyMap());

    private final boolean allowed;
    private final String message;
    private final Map<String, String> placeholders;
    private final List<UUID> consumedGemIds;
    private final RedeemRecipe matchedRecipe;
    private final boolean messageIsLanguageKey;

    private RedeemRequirementResult(boolean allowed, String message, List<UUID> consumedGemIds,
            RedeemRecipe matchedRecipe, boolean messageIsLanguageKey, Map<String, String> placeholders) {
        this.allowed = allowed;
        this.message = message;
        this.placeholders = placeholders == null ? Collections.emptyMap() : Collections.unmodifiableMap(placeholders);
        this.consumedGemIds = consumedGemIds == null ? Collections.emptyList() : Collections.unmodifiableList(consumedGemIds);
        this.matchedRecipe = matchedRecipe;
        this.messageIsLanguageKey = messageIsLanguageKey;
    }

    public static RedeemRequirementResult denied(String message, boolean messageIsLanguageKey) {
        return denied(message, messageIsLanguageKey, Collections.emptyMap());
    }

    public static RedeemRequirementResult denied(String message, boolean messageIsLanguageKey,
            Map<String, String> placeholders) {
        return new RedeemRequirementResult(false, message, Collections.emptyList(), null, messageIsLanguageKey, placeholders);
    }

    public static RedeemRequirementResult allowed(List<UUID> consumedGemIds) {
        return allowed(consumedGemIds, null);
    }

    public static RedeemRequirementResult allowed(List<UUID> consumedGemIds, RedeemRecipe matchedRecipe) {
        if (consumedGemIds == null || consumedGemIds.isEmpty()) {
            return matchedRecipe == null ? ALLOWED
                    : new RedeemRequirementResult(true, null, Collections.emptyList(), matchedRecipe, false,
                            Collections.emptyMap());
        }
        return new RedeemRequirementResult(true, null, consumedGemIds, matchedRecipe, false, Collections.emptyMap());
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getPlaceholders() {
        return placeholders;
    }

    public List<UUID> getConsumedGemIds() {
        return consumedGemIds;
    }

    public RedeemRecipe getMatchedRecipe() {
        return matchedRecipe;
    }

    public boolean isMessageLanguageKey() {
        return messageIsLanguageKey;
    }
}
