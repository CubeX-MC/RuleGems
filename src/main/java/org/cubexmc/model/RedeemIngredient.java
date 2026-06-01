package org.cubexmc.model;

import java.util.Objects;

/**
 * One gem ingredient in a redeem recipe.
 */
public final class RedeemIngredient {
    private final String gemKey;
    private final int amount;

    public RedeemIngredient(String gemKey, int amount) {
        this.gemKey = gemKey == null ? "" : gemKey.trim();
        this.amount = Math.max(1, amount);
    }

    public String getGemKey() {
        return gemKey;
    }

    public int getAmount() {
        return amount;
    }

    public boolean isValid() {
        return !gemKey.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof RedeemIngredient that))
            return false;
        return amount == that.amount && Objects.equals(gemKey, that.gemKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gemKey, amount);
    }

    @Override
    public String toString() {
        return "RedeemIngredient{gemKey='" + gemKey + "', amount=" + amount + '}';
    }
}
