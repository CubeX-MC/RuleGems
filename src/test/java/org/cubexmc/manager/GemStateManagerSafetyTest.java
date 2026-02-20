package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemStateManagerSafetyTest {

    @Mock private RuleGems plugin;
    @Mock private GemDefinitionParser gemParser;
    @Mock private LanguageManager languageManager;

    private GemStateManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemStateManagerSafetyTest"));
        lenient().when(plugin.getName()).thenReturn("RuleGems");
        manager = new GemStateManager(plugin, gemParser, languageManager);
    }

    @Test
    void bindAndUnbindPlacedGemKeepsMappingsConsistent() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        Location location = mock(Location.class);

        manager.bindPlacedGem(location, gemId);
        assertEquals(gemId, manager.getGemUuidByLocation(location));
        assertEquals(location, manager.getGemLocation(gemId));

        manager.unbindPlacedGem(location, gemId);
        assertNull(manager.getGemUuidByLocation(location));
        assertNull(manager.getGemLocation(gemId));
    }

    @Test
    void saveDataSkipsEntryWhenWorldMissing() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(null);
        manager.bindPlacedGem(location, gemId);

        Player holder = mock(Player.class);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        when(holder.getName()).thenReturn("Alice");
        when(holder.getUniqueId()).thenReturn(playerId);
        manager.setGemHolder(gemId, holder);
        manager.setGemKey(gemId, "fire");

        FileConfiguration gemsData = mock(FileConfiguration.class);
        manager.saveData(gemsData);

        verify(gemsData, never()).set(contains("placed-gems." + gemId), any());
        verify(gemsData).set(eq("held-gems." + gemId + ".player"), eq("Alice"));
        verify(gemsData).set(eq("held-gems." + gemId + ".player_uuid"), eq(playerId.toString()));
    }
}
