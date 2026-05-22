package org.cubexmc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional preconditions for redeeming a gem.
 */
public class RedeemRequirements {
    public static final RedeemRequirements NONE = new RedeemRequirements(
            Collections.emptyList(),
            true,
            null);

    private final List<RedeemRecipe> recipes;
    private final boolean allowRedeemAll;
    private final String failureMessage;

    public RedeemRequirements(List<RedeemRecipe> recipes, boolean allowRedeemAll, String failureMessage) {
        this.recipes = immutableRecipes(recipes);
        this.allowRedeemAll = allowRedeemAll;
        this.failureMessage = failureMessage;
    }

    /**
     * Legacy constructor kept so older tests and internal callers can migrate gradually.
     */
    public RedeemRequirements(List<String> requiresHeld,
            List<String> requiresRedeemed,
            List<String> consumes,
            List<String> requiresAny,
            int requiresCount,
            List<String> requiresCountFrom,
            boolean allowRedeemAll,
            String failureMessage) {
        RedeemRecipe recipe = RedeemRecipe.legacy(requiresHeld, requiresRedeemed, consumes, requiresAny, requiresCount,
                requiresCountFrom);
        this.recipes = recipe.hasRequirements()
                ? Collections.unmodifiableList(Collections.singletonList(recipe))
                : Collections.emptyList();
        this.allowRedeemAll = allowRedeemAll;
        this.failureMessage = failureMessage;
    }

    private static List<RedeemRecipe> immutableRecipes(List<RedeemRecipe> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<RedeemRecipe> copy = new ArrayList<>();
        for (RedeemRecipe recipe : values) {
            if (recipe != null && recipe.hasRequirements()) {
                copy.add(recipe);
            }
        }
        return copy.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(copy);
    }

    public boolean hasRequirements() {
        return !recipes.isEmpty();
    }

    public List<RedeemRecipe> getRecipes() {
        return recipes;
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getRequiresHeld()}.
     */
    @Deprecated
    public List<String> getRequiresHeld() {
        return flattenIngredientKeys(RedeemRecipe::getRequiresHeld);
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getRequiresRedeemed()}.
     */
    @Deprecated
    public List<String> getRequiresRedeemed() {
        return flattenIngredientKeys(RedeemRecipe::getRequiresRedeemed);
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getConsumes()}.
     */
    @Deprecated
    public List<String> getConsumes() {
        return flattenIngredientKeys(RedeemRecipe::getConsumes);
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getRequiresAny()}.
     */
    @Deprecated
    public List<String> getRequiresAny() {
        if (recipes.isEmpty()) {
            return Collections.emptyList();
        }
        return recipes.get(0).getRequiresAny();
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getRequiresCount()}.
     */
    @Deprecated
    public int getRequiresCount() {
        return recipes.isEmpty() ? 0 : recipes.get(0).getRequiresCount();
    }

    /**
     * @deprecated Use {@link #getRecipes()} and {@link RedeemRecipe#getRequiresCountFrom()}.
     */
    @Deprecated
    public List<String> getRequiresCountFrom() {
        return recipes.isEmpty() ? Collections.emptyList() : recipes.get(0).getRequiresCountFrom();
    }

    public boolean isAllowRedeemAll() {
        return allowRedeemAll;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    private List<String> flattenIngredientKeys(java.util.function.Function<RedeemRecipe, List<RedeemIngredient>> getter) {
        if (recipes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (RedeemRecipe recipe : recipes) {
            for (RedeemIngredient ingredient : getter.apply(recipe)) {
                for (int i = 0; i < ingredient.getAmount(); i++) {
                    result.add(ingredient.getGemKey());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
