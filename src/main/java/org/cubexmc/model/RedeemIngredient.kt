package org.cubexmc.model

import java.util.Objects
import kotlin.math.max

/**
 * One gem ingredient in a redeem recipe.
 */
class RedeemIngredient(gemKey: String?, amount: Int) {
    val gemKey: String = gemKey?.trim() ?: ""
    val amount: Int = max(1, amount)

    fun isValid(): Boolean = gemKey.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RedeemIngredient) return false
        return amount == other.amount && gemKey == other.gemKey
    }

    override fun hashCode(): Int = Objects.hash(gemKey, amount)

    override fun toString(): String = "RedeemIngredient{gemKey='$gemKey', amount=$amount}"
}
