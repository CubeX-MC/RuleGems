package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
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

    private Location location(String name, int x) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(world.getName()).thenReturn(name);
        return new Location(world, x, 64, 0);
    }

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemStateManagerSafetyTest"));
        lenient().when(plugin.getName()).thenReturn("RuleGems");
        manager = new GemStateManager(plugin, gemParser, languageManager);
    }

    @Test
    void bindAndUnbindPlacedGemKeepsMappingsConsistent() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        Location location = location("world", 1);

        manager.bindPlacedGem(location, gemId);
        assertEquals(gemId, manager.getGemUuidByLocation(location));
        assertEquals(location, manager.getGemLocation(gemId));

        manager.unbindPlacedGem(location, gemId);
        assertNull(manager.getGemUuidByLocation(location));
        assertNull(manager.getGemLocation(gemId));
    }

    @Test
    void rebindPlacedGemRemovesPreviousLocationMapping() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000013");
        Location oldLocation = location("world", 2);
        Location newLocation = location("world", 3);

        manager.bindPlacedGem(oldLocation, gemId);
        manager.bindPlacedGem(newLocation, gemId);

        assertNull(manager.getGemUuidByLocation(oldLocation));
        assertEquals(gemId, manager.getGemUuidByLocation(newLocation));
        assertEquals(newLocation, manager.getGemLocation(gemId));
    }

    @Test
    void staleUnbindDoesNotClearNewLocationMapping() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000014");
        Location oldLocation = location("world", 4);
        Location newLocation = location("world", 5);

        manager.bindPlacedGem(oldLocation, gemId);
        manager.bindPlacedGem(newLocation, gemId);
        manager.unbindPlacedGem(oldLocation, gemId);

        assertNull(manager.getGemUuidByLocation(oldLocation));
        assertEquals(gemId, manager.getGemUuidByLocation(newLocation));
        assertEquals(newLocation, manager.getGemLocation(gemId));
    }

    @Test
    void saveDataSkipsEntryWhenWorldMissing() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        Location location = new Location(null, 0, 64, 0);
        manager.bindPlacedGem(location, gemId);

        Player holder = mock(Player.class);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        when(holder.getName()).thenReturn("Alice");
        when(holder.getUniqueId()).thenReturn(playerId);
        manager.setGemHolder(gemId, holder);
        manager.setGemKey(gemId, "fire");

        Map<String, Object> snapshot = new HashMap<>();
        manager.populateSaveSnapshot(snapshot);

        assertNull(snapshot.get("placed-gems." + gemId + ".world"));
        assertEquals("Alice", snapshot.get("held-gems." + gemId + ".player"));
        assertEquals(playerId.toString(), snapshot.get("held-gems." + gemId + ".player_uuid"));
    }

    @Test
    void heldAndPlacedTransitionsRemainMutuallyExclusive() {
        UUID gemId = UUID.fromString("10000000-0000-0000-0000-000000000015");
        Location location = location("world", 6);
        Player holder = mock(Player.class);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        when(holder.getUniqueId()).thenReturn(playerId);
        when(holder.getName()).thenReturn("Bob");

        manager.bindPlacedGem(location, gemId);
        manager.setGemHolder(gemId, holder);

        assertNull(manager.getGemLocation(gemId));
        assertNull(manager.getGemUuidByLocation(location));
        assertEquals(playerId, manager.getGemUuidToHolder().get(gemId));
        assertTrue(manager.hasConsistentPlacementState());

        manager.bindPlacedGem(location, gemId);

        assertNull(manager.getGemHolder(gemId));
        assertEquals(location, manager.getGemLocation(gemId));
        assertTrue(manager.hasConsistentPlacementState());
    }
}
