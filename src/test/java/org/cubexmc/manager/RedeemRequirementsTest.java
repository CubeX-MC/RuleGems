package org.cubexmc.manager;

import org.cubexmc.model.RedeemRequirements;
import org.cubexmc.model.RedeemRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedeemRequirementsTest {

    @Test
    void missingConfigUsesSharedNoopRequirements() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        assertSame(RedeemRequirements.NONE, parser.parseRedeemRequirements(null));
    }

    @Test
    void parsesAllRedeemRequirementFields() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);
        Map<String, Object> config = Map.of(
                "requires_held", List.of("earth", "law"),
                "requires_redeemed", List.of("crown"),
                "consumes", List.of("oath"),
                "requires_any", List.of("seal", "warrant"),
                "requires_count", 2,
                "requires_count_from", List.of("ruby", "sapphire", "emerald"),
                "allow_redeem_all", true,
                "failure_message", "&cMissing tribute");

        RedeemRequirements requirements = parser.parseRedeemRequirements(config);
        RedeemRecipe recipe = requirements.getRecipes().get(0);

        assertEquals(List.of("earth", "law"), recipe.getRequiresHeld().stream().map(i -> i.getGemKey()).toList());
        assertEquals(List.of("crown"), recipe.getRequiresRedeemed().stream().map(i -> i.getGemKey()).toList());
        assertEquals(List.of("oath"), recipe.getConsumes().stream().map(i -> i.getGemKey()).toList());
        assertEquals(List.of("seal", "warrant"), recipe.getRequiresAny());
        assertEquals(2, recipe.getRequiresCount());
        assertEquals(List.of("ruby", "sapphire", "emerald"), recipe.getRequiresCountFrom());
        assertTrue(requirements.isAllowRedeemAll());
        assertEquals("&cMissing tribute", requirements.getFailureMessage());
    }

    @Test
    void configuredRequirementsDoNotAllowRedeemAllUnlessExplicit() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        RedeemRequirements requirements = parser.parseRedeemRequirements(Map.of("requires_held", List.of("oath")));

        assertTrue(requirements.hasRequirements());
        assertFalse(requirements.isAllowRedeemAll());
    }

    @Test
    void parsesMapIngredientsWithAmounts() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        RedeemRequirements requirements = parser.parseRedeemRequirements(Map.of(
                "consumes", List.of(Map.of("gem", "dragon", "amount", 3), "scale")));

        RedeemRecipe recipe = requirements.getRecipes().get(0);
        assertEquals("dragon", recipe.getConsumes().get(0).getGemKey());
        assertEquals(3, recipe.getConsumes().get(0).getAmount());
        assertEquals("scale", recipe.getConsumes().get(1).getGemKey());
        assertEquals(1, recipe.getConsumes().get(1).getAmount());
    }

    @Test
    void normalizesNonPositiveIngredientAmountToOne() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        RedeemRequirements requirements = parser.parseRedeemRequirements(Map.of(
                "requires_held", List.of(Map.of("gem", "dragon", "amount", 0))));

        assertEquals(1, requirements.getRecipes().get(0).getRequiresHeld().get(0).getAmount());
    }

    @Test
    void parsesAnyOfAsMultipleRecipesAndPrefersItOverTopLevelRecipe() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        RedeemRequirements requirements = parser.parseRedeemRequirements(Map.of(
                "requires_held", List.of("ignored"),
                "any_of", List.of(
                        Map.of("consumes", List.of(Map.of("gem", "dragon", "amount", 3))),
                        Map.of("consumes", List.of("egg")))));

        assertEquals(2, requirements.getRecipes().size());
        assertEquals("dragon", requirements.getRecipes().get(0).getConsumes().get(0).getGemKey());
        assertEquals(3, requirements.getRecipes().get(0).getConsumes().get(0).getAmount());
        assertEquals("egg", requirements.getRecipes().get(1).getConsumes().get(0).getGemKey());
        assertTrue(requirements.getRecipes().stream().allMatch(recipe -> recipe.getRequiresHeld().isEmpty()));
    }
}
