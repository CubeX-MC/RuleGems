package org.cubexmc.model

import java.util.Collections

/**
 * Optional preconditions for redeeming a gem.
 */
class RedeemRequirements {
    val recipes: List<RedeemRecipe>
    val isAllowRedeemAll: Boolean
    val failureMessage: String?

    constructor(recipes: List<RedeemRecipe>?, allowRedeemAll: Boolean, failureMessage: String?) {
        this.recipes = immutableRecipes(recipes)
        this.isAllowRedeemAll = allowRedeemAll
        this.failureMessage = failureMessage
    }

    constructor(
        requiresHeld: List<String>?,
        requiresRedeemed: List<String>?,
        consumes: List<String>?,
        requiresAny: List<String>?,
        requiresCount: Int,
        requiresCountFrom: List<String>?,
        allowRedeemAll: Boolean,
        failureMessage: String?,
    ) {
        val recipe = RedeemRecipe.legacy(requiresHeld, requiresRedeemed, consumes, requiresAny, requiresCount, requiresCountFrom)
        this.recipes = if (recipe.hasRequirements()) {
            Collections.unmodifiableList(Collections.singletonList(recipe))
        } else {
            emptyList()
        }
        this.isAllowRedeemAll = allowRedeemAll
        this.failureMessage = failureMessage
    }

    fun hasRequirements(): Boolean = recipes.isNotEmpty()

    @Deprecated("Use recipes and RedeemRecipe.requiresHeld.")
    fun getRequiresHeld(): List<String> = flattenIngredientKeys { it.requiresHeld }

    @Deprecated("Use recipes and RedeemRecipe.requiresRedeemed.")
    fun getRequiresRedeemed(): List<String> = flattenIngredientKeys { it.requiresRedeemed }

    @Deprecated("Use recipes and RedeemRecipe.consumes.")
    fun getConsumes(): List<String> = flattenIngredientKeys { it.consumes }

    @Deprecated("Use recipes and RedeemRecipe.requiresAny.")
    fun getRequiresAny(): List<String> = if (recipes.isEmpty()) emptyList() else recipes[0].requiresAny

    @Deprecated("Use recipes and RedeemRecipe.requiresCount.")
    fun getRequiresCount(): Int = if (recipes.isEmpty()) 0 else recipes[0].requiresCount

    @Deprecated("Use recipes and RedeemRecipe.requiresCountFrom.")
    fun getRequiresCountFrom(): List<String> = if (recipes.isEmpty()) emptyList() else recipes[0].requiresCountFrom

    private fun flattenIngredientKeys(getter: (RedeemRecipe) -> List<RedeemIngredient>): List<String> {
        if (recipes.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (recipe in recipes) {
            for (ingredient in getter(recipe)) {
                for (i in 0 until ingredient.amount) {
                    result.add(ingredient.gemKey)
                }
            }
        }
        return Collections.unmodifiableList(result)
    }

    companion object {
        @JvmField
        val NONE: RedeemRequirements = RedeemRequirements(emptyList(), true, null)

        private fun immutableRecipes(values: List<RedeemRecipe>?): List<RedeemRecipe> {
            if (values.isNullOrEmpty()) return emptyList()
            val copy = ArrayList<RedeemRecipe>()
            for (recipe in values) {
                if (recipe.hasRequirements()) copy.add(recipe)
            }
            return if (copy.isEmpty()) emptyList() else Collections.unmodifiableList(copy)
        }
    }
}
