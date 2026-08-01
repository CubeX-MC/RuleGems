package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.model.GemDefinition;
import org.junit.jupiter.api.Test;

class GemDataValidatorTest {
    private static final UUID GEM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final List<GemDefinition> DEFINITIONS =
            List.of(new GemDefinition.Builder("flight").count(1).build());

    @Test
    void acceptsValidPlacedAndHeldData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("placed-gems." + GEM_ID + ".world", "world");
        data.set("placed-gems." + GEM_ID + ".x", 1.0);
        data.set("placed-gems." + GEM_ID + ".y", 64.0);
        data.set("placed-gems." + GEM_ID + ".z", 2.0);
        data.set("placed-gems." + GEM_ID + ".gem_key", "flight");
        data.set("redeemed." + PLAYER_ID, List.of("flight"));

        assertTrue(GemDataValidator.INSTANCE.validate(data, DEFINITIONS).getValid());
    }

    @Test
    void rejectsInvalidUuidAndUnknownGemKey() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("held-gems.not-a-uuid.player_uuid", "also-not-a-uuid");
        data.set("held-gems.not-a-uuid.gem_key", "removed_power");

        GemDataValidator.ValidationResult result =
                GemDataValidator.INSTANCE.validate(data, DEFINITIONS);

        assertFalse(result.getValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("invalid UUID")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("unknown configured gem key")));
    }

    @Test
    void rejectsGemPresentInPlacedAndHeldSections() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("placed-gems." + GEM_ID + ".world", "world");
        data.set("placed-gems." + GEM_ID + ".x", 1);
        data.set("placed-gems." + GEM_ID + ".y", 64);
        data.set("placed-gems." + GEM_ID + ".z", 2);
        data.set("placed-gems." + GEM_ID + ".gem_key", "flight");
        data.set("held-gems." + GEM_ID + ".player_uuid", PLAYER_ID.toString());
        data.set("held-gems." + GEM_ID + ".gem_key", "flight");

        GemDataValidator.ValidationResult result =
                GemDataValidator.INSTANCE.validate(data, DEFINITIONS);

        assertFalse(result.getValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("both placed-gems and held-gems")));
    }

    @Test
    void acceptsUnlimitedAllowanceSentinelAcrossPersistedSources() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("allowed_uses." + PLAYER_ID + ".held_instances." + GEM_ID + ".home", -1);
        data.set("allowed_uses." + PLAYER_ID + ".redeemed_instances." + GEM_ID + ".announce", -1);
        data.set("allowed_uses." + PLAYER_ID + ".appointments.police.jaillist", -1);
        data.set("allowed_uses." + PLAYER_ID + ".global.help", -1);

        GemDataValidator.ValidationResult result =
                GemDataValidator.INSTANCE.validate(data, DEFINITIONS);

        assertTrue(result.getValid());
    }

    @Test
    void rejectsAllowanceBelowUnlimitedSentinel() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("allowed_uses." + PLAYER_ID + ".held_instances." + GEM_ID + ".home", -2);

        GemDataValidator.ValidationResult result =
                GemDataValidator.INSTANCE.validate(data, DEFINITIONS);

        assertFalse(result.getValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("-1 (unlimited)")));
    }

    @Test
    void rejectsFractionalAllowanceCount() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("allowed_uses." + PLAYER_ID + ".held_instances." + GEM_ID + ".home", 1.5);

        GemDataValidator.ValidationResult result =
                GemDataValidator.INSTANCE.validate(data, DEFINITIONS);

        assertFalse(result.getValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("-1 (unlimited)")));
    }
}
