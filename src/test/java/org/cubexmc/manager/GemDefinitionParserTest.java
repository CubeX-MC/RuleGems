package org.cubexmc.manager;

import org.cubexmc.model.PowerStructure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GemDefinitionParserTest {

    @Test
    void parsesPermissionGroupsCanonicalKey() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        PowerStructure power = parser.parsePowerStructure(Map.of("permission_groups", List.of("noble", "ruler")));

        assertEquals(List.of("noble", "ruler"), power.getVaultGroups());
    }

    @Test
    void mergesLegacyGroupKeysWithoutDuplicates() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);

        PowerStructure power = parser.parsePowerStructure(Map.of(
                "permission_groups", List.of("noble"),
                "vault_group", "ruler",
                "vault_groups", List.of("noble", "guard"),
                "permission_group", "ruler"));

        assertEquals(List.of("noble", "ruler", "guard"), power.getVaultGroups());
    }
}
