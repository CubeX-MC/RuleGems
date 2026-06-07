package org.cubexmc.model

import java.util.Collections
import kotlin.math.max

/**
 * A complete set of preconditions that can redeem a gem.
 */
class RedeemRecipe(
    requiresHeld: List<RedeemIngredient>?,
    consumes: List<RedeemIngredient>?,
    requiresRedeemed: List<RedeemIngredient>?,
    requiresAny: List<String>?,
    requiresCount: Int,
    requiresCountFrom: List<String>?,
) {
    val requiresHeld: List<RedeemIngredient> = immutableIngredients(requiresHeld)
    val consumes: List<RedeemIngredient> = immutableIngredients(consumes)
    val requiresRedeemed: List<RedeemIngredient> = immutableIngredients(requiresRedeemed)
    val requiresAny: List<String> = immutableStrings(requiresAny)
    val requiresCount: Int = max(0, requiresCount)
    val requiresCountFrom: List<String> = immutableStrings(requiresCountFrom)

    fun hasRequirements(): Boolean =
        requiresHeld.isNotEmpty() ||
            consumes.isNotEmpty() ||
            requiresRedeemed.isNotEmpty() ||
            requiresAny.isNotEmpty() ||
            (requiresCount > 0 && requiresCountFrom.isNotEmpty())

    companion object {
        @JvmStatic
        fun legacy(
            requiresHeld: List<String>?,
            requiresRedeemed: List<String>?,
            consumes: List<String>?,
            requiresAny: List<String>?,
            requiresCount: Int,
            requiresCountFrom: List<String>?,
        ): RedeemRecipe =
            RedeemRecipe(toIngredients(requiresHeld), toIngredients(consumes), toIngredients(requiresRedeemed), requiresAny, requiresCount, requiresCountFrom)

        private fun toIngredients(keys: List<String>?): List<RedeemIngredient> {
            if (keys.isNullOrEmpty()) return emptyList()
            val ingredients = ArrayList<RedeemIngredient>()
            for (key in keys) {
                if (!key.isNullOrBlank()) ingredients.add(RedeemIngredient(key, 1))
            }
            return ingredients
        }

        private fun immutableIngredients(values: List<RedeemIngredient>?): List<RedeemIngredient> {
            if (values.isNullOrEmpty()) return emptyList()
            return Collections.unmodifiableList(ArrayList(values))
        }

        private fun immutableStrings(values: List<String>?): List<String> {
            if (values.isNullOrEmpty()) return emptyList()
            val copy = ArrayList<String>()
            for (value in values) {
                if (!value.isNullOrBlank()) copy.add(value.trim())
            }
            return if (copy.isEmpty()) emptyList() else Collections.unmodifiableList(copy)
        }
    }
}
