package org.cubexmc.manager;

import org.cubexmc.model.PowerStructure;
import org.cubexmc.model.AllowedCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GemDefinitionParserTest {

    @Test
    void parsesYamlArgumentConstraintsForOwnerAndAppointeeCaps() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                command_allows:
                  - command: /cxfine
                    usage: '/cxfine <player> <amount>'
                    args:
                      arg1: {type: string}
                      arg2: {type: number, min: 0.01, max: 10000}
                    execute: ['transfer:%arg1% cubex_bank %arg2%']
                    time_limit: 5
                    cooldown: 7200
                """);
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);
        AllowedCommand owner = parser.parsePowerStructure(Map.of("command_allows", yaml.getMapList("command_allows")))
                .getAllowedCommands().get(0);
        assertEquals("/cxfine <player> <amount>", owner.getUsage());
        assertEquals(5, owner.getUses());
        assertEquals(7200, owner.getCooldown());
        assertNull(owner.getArgumentConstraints().validate(new String[]{"Alex", "10000"}));
        assertEquals("allowance.args_max", owner.getArgumentConstraints().validate(new String[]{"Alex", "10001"}).getMessageKey());
        assertEquals("allowance.args_required", owner.getArgumentConstraints().validate(new String[0]).getMessageKey());

        AllowedCommand appointee = parseCommand(Map.of("arg2", Map.of("type", "number", "min", 0.01, "max", 500)));
        assertNull(appointee.getArgumentConstraints().validate(new String[]{"Alex", "500"}));
        assertEquals("allowance.args_max", appointee.getArgumentConstraints().validate(new String[]{"Alex", "500.01"}).getMessageKey());
    }

    @Test
    void malformedConstraintsKeepCommandRegisteredButBlockExecution() {
        List<Object> invalidRules = List.of(
                "not a mapping", List.of(), Map.of("arg0", Map.of()),
                Map.of("arg2147483648", Map.of()), Map.of("arg2", "number"),
                Map.of("arg2", Map.of("type", "decimal")),
                Map.of("arg2", Map.of("type", "number", "maximum", 500)),
                Map.of("arg2", Map.of("max", 500)),
                Map.of("arg2", Map.of("required", "false")),
                Map.of("arg2", Map.of("type", "number", "min", 501, "max", 500)),
                Map.of("arg2", Map.of("type", "number", "max", "NaN")),
                Map.of("arg2", Map.of("type", "number", "max", "1e999")),
                Map.of("arg2", Map.of("type", "number", "max", "0e-2147483647")));
        for (Object rules : invalidRules) {
            AllowedCommand command = parseCommand(rules);
            assertEquals("cxfine", command.getLabel());
            assertNotNull(command.getArgumentConstraints().getConfigurationError(), rules.toString());
            assertEquals("allowance.args_config_error", command.getArgumentConstraints()
                    .validate(new String[]{"Alex", "100"}).getMessageKey(), rules.toString());
        }
    }

    @Test
    void missingRulesAreCompatibleButExplicitNullRulesAreInvalid() {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);
        Map<String, Object> entry = new java.util.HashMap<>(Map.of("command", "/cxfine", "execute", List.of("console:test")));
        AllowedCommand legacy = parser.parsePowerStructure(Map.of("command_allows", List.of(entry))).getAllowedCommands().get(0);
        assertNull(legacy.getArgumentConstraints().validate(new String[0]));
        entry.put("args", null);
        AllowedCommand invalid = parser.parsePowerStructure(Map.of("command_allows", List.of(entry))).getAllowedCommands().get(0);
        assertNotNull(invalid.getArgumentConstraints().getConfigurationError());
    }

    private AllowedCommand parseCommand(Object rules) {
        GemDefinitionParser parser = new GemDefinitionParser(Logger.getLogger("RuleGemsTest"), null);
        PowerStructure power = parser.parsePowerStructure(Map.of("command_allows", List.of(Map.of(
                "command", "/cxfine", "execute", List.of("transfer:%arg1% cubex_bank %arg2%"), "args", rules))));
        assertEquals(1, power.getAllowedCommands().size());
        return power.getAllowedCommands().get(0);
    }

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
