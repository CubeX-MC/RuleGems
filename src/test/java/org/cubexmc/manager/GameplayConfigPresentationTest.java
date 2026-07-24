package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GameplayConfigPresentationTest {

    @Test
    void defaultsToTraditionalBlockMode() {
        GameplayConfig config = load(new YamlConfiguration());

        assertEquals(GemPresentationMode.BLOCK, config.getGemPresentationMode());
        assertEquals(16.0, config.getGemDisplayRevealRange());
        assertEquals(20.0, config.getGemDisplayHideRange());
    }

    @Test
    void loadsProximityDisplayAndClampsHideRange() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gem_presentation.mode", "proximity_display");
        yaml.set("gem_presentation.reveal_range", 24.0);
        yaml.set("gem_presentation.hide_range", 12.0);

        GameplayConfig config = load(yaml);

        assertEquals(GemPresentationMode.PROXIMITY_DISPLAY, config.getGemPresentationMode());
        assertEquals(24.0, config.getGemDisplayRevealRange());
        assertEquals(24.0, config.getGemDisplayHideRange());
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
