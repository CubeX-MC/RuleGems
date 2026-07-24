package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GameplayConfigEscapeTest {

    @Test
    void loadsSafeDefaultsWhenEscapeSectionIsMissing() {
        GameplayConfig config = load(new YamlConfiguration());

        assertFalse(config.isGemEscapeEnabled());
        assertEquals(30L * 60L * 20L, config.getGemEscapeMinIntervalTicks());
        assertEquals(2L * 60L * 60L * 20L, config.getGemEscapeMaxIntervalTicks());
        assertEquals(4L * 60L * 60L * 20L, config.getGemEscapeMinimumUnmovedTicks());
        assertEquals(3, config.getGemEscapeMaxFailedRounds());
        assertEquals(10, config.getGemEscapeAttemptsPerRound());
    }

    @Test
    void loadsConfigurableAgeAndFailureRounds() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gem_escape.enabled", true);
        yaml.set("gem_escape.minimum_unmoved_duration", "6h");
        yaml.set("gem_escape.local_move.max_failed_rounds", 5);
        yaml.set("gem_escape.local_move.retry_delay", "20m");

        GameplayConfig config = load(yaml);

        assertTrue(config.isGemEscapeEnabled());
        assertEquals(6L * 60L * 60L * 20L, config.getGemEscapeMinimumUnmovedTicks());
        assertEquals(5, config.getGemEscapeMaxFailedRounds());
        assertEquals(20L * 60L * 20L, config.getGemEscapeRetryDelayTicks());
    }

    @Test
    void clampsInvalidThresholdsAndNormalizesRanges() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gem_escape.min_interval", "2h");
        yaml.set("gem_escape.max_interval", "30m");
        yaml.set("gem_escape.minimum_unmoved_duration", "-5m");
        yaml.set("gem_escape.selection.cluster_weight", 0.25);
        yaml.set("gem_escape.local_move.min_distance", 400.0);
        yaml.set("gem_escape.local_move.max_distance", 100.0);
        yaml.set("gem_escape.local_move.attempts_per_round", 0);
        yaml.set("gem_escape.local_move.max_failed_rounds", 0);
        yaml.set("gem_escape.local_move.retry_delay", "0s");
        yaml.set("gem_escape.local_move.max_local_escapes_without_pickup", 0);

        GameplayConfig config = load(yaml);

        assertEquals(30L * 60L * 20L, config.getGemEscapeMinIntervalTicks());
        assertEquals(2L * 60L * 60L * 20L, config.getGemEscapeMaxIntervalTicks());
        assertEquals(0L, config.getGemEscapeMinimumUnmovedTicks());
        assertEquals(1.0, config.getGemEscapeClusterWeight());
        assertEquals(400.0, config.getGemEscapeLocalMinDistance());
        assertEquals(400.0, config.getGemEscapeLocalMaxDistance());
        assertEquals(1, config.getGemEscapeAttemptsPerRound());
        assertEquals(1, config.getGemEscapeMaxFailedRounds());
        assertEquals(20L, config.getGemEscapeRetryDelayTicks());
        assertEquals(1, config.getGemEscapeMaxLocalEscapesWithoutPickup());
    }

    private GameplayConfig load(YamlConfiguration yaml) {
        GameplayConfig config = new GameplayConfig();
        config.loadFrom(
                yaml,
                mock(GemDefinitionParser.class),
                mock(LanguageManager.class),
                null,
                null);
        return config;
    }
}
