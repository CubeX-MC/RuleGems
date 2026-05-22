package org.cubexmc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A complete set of preconditions that can redeem a gem.
 */
public final class RedeemRecipe {
    private final List<RedeemIngredient> requiresHeld;
    private final List<RedeemIngredient> consumes;
    private final List<RedeemIngredient> requiresRedeemed;
    private final List<String> requiresAny;
    private final int requiresCount;
    private final List<String> requiresCountFrom;

    public RedeemRecipe(List<RedeemIngredient> requiresHeld,
            List<RedeemIngredient> consumes,
            List<RedeemIngredient> requiresRedeemed,
            List<String> requiresAny,
            int requiresCount,
            List<String> requiresCountFrom) {
        this.requiresHeld = immutableIngredients(requiresHeld);
        this.consumes = immutableIngredients(consumes);
        this.requiresRedeemed = immutableIngredients(requiresRedeemed);
        this.requiresAny = immutableStrings(requiresAny);
        this.requiresCount = Math.max(0, requiresCount);
        this.requiresCountFrom = immutableStrings(requiresCountFrom);
    }

    public static RedeemRecipe legacy(List<String> requiresHeld,
            List<String> requiresRedeemed,
            List<String> consumes,
            List<String> requiresAny,
            int requiresCount,
            List<String> requiresCountFrom) {
        return new RedeemRecipe(toIngredients(requiresHeld), toIngredients(consumes), toIngredients(requiresRedeemed),
                requiresAny, requiresCount, requiresCountFrom);
    }

    private static List<RedeemIngredient> toIngredients(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RedeemIngredient> ingredients = new ArrayList<>();
        for (String key : keys) {
            if (key != null && !key.trim().isEmpty()) {
                ingredients.add(new RedeemIngredient(key, 1));
            }
        }
        return ingredients;
    }

    private static List<RedeemIngredient> immutableIngredients(List<RedeemIngredient> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return copy.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(copy);
    }

    public boolean hasRequirements() {
        return !requiresHeld.isEmpty()
                || !consumes.isEmpty()
                || !requiresRedeemed.isEmpty()
                || !requiresAny.isEmpty()
                || (requiresCount > 0 && !requiresCountFrom.isEmpty());
    }

    public List<RedeemIngredient> getRequiresHeld() {
        return requiresHeld;
    }

    public List<RedeemIngredient> getConsumes() {
        return consumes;
    }

    public List<RedeemIngredient> getRequiresRedeemed() {
        return requiresRedeemed;
    }

    public List<String> getRequiresAny() {
        return requiresAny;
    }

    public int getRequiresCount() {
        return requiresCount;
    }

    public List<String> getRequiresCountFrom() {
        return requiresCountFrom;
    }
}
