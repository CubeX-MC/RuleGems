package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.provider.PermissionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证 scatter 专用重置 {@link GemPermissionManager#resetForScatter()}：
 * 离线统治者的持久化权限/Vault 组会被入队待下次登录撤销；在线统治者则当场剥夺、不入队。
 */
@ExtendWith(MockitoExtension.class)
class GemPermissionManagerScatterTest {

    @Mock private RuleGems plugin;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private GemStateManager stateManager;
    @Mock private PermissionProvider permissionProvider;

    private GemPermissionManager manager;
    private MockedStatic<Bukkit> mockedBukkit;

    private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ONLINE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("ScatterTest"));
        lenient().when(plugin.getPermissionProvider()).thenReturn(permissionProvider);
        lenient().when(gameplayConfig.getGemCollectThresholdGroups()).thenReturn(Collections.emptyMap());
        mockedBukkit = mockStatic(Bukkit.class);
        mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());
        mockedBukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        manager = new GemPermissionManager(plugin, gameplayConfig, stateManager);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    private GemDefinition gemDef(String key, List<String> perms, String group) {
        PowerStructure ps = new PowerStructure();
        ps.setPermissions(new ArrayList<>(perms));
        ps.setVaultGroups(new ArrayList<>(Collections.singletonList(group)));
        return new GemDefinition.Builder(key).displayName(key).powerStructure(ps).build();
    }

    @Test
    void resetForScatterQueuesOfflineRulerPersistentRevokes() {
        manager.getOwnerKeyCount().put(OFFLINE, new HashMap<>(Map.of("fire", 1)));
        when(stateManager.findGemDefinition("fire"))
                .thenReturn(gemDef("fire", List.of("fire.fly"), "fire_group"));

        manager.resetForScatter();

        Set<String> perms = manager.getPendingPermRevokes().get(OFFLINE);
        Set<String> groups = manager.getPendingGroupRevokes().get(OFFLINE);
        assertNotNull(perms);
        assertTrue(perms.contains("fire.fly"));
        assertNotNull(groups);
        assertTrue(groups.contains("fire_group"));
        // Ownership state is reset by scatter.
        assertTrue(manager.getOwnerKeyCount().getOrDefault(OFFLINE, Collections.emptyMap()).isEmpty());
    }

    @Test
    void resetForScatterDoesNotQueueOnlineRuler() {
        Player onlinePlayer = mock(Player.class);
        lenient().when(onlinePlayer.isOnline()).thenReturn(true);
        mockedBukkit.when(() -> Bukkit.getPlayer(ONLINE)).thenReturn(onlinePlayer);

        manager.getOwnerKeyCount().put(ONLINE, new HashMap<>(Map.of("fire", 1)));
        lenient().when(stateManager.findGemDefinition("fire"))
                .thenReturn(gemDef("fire", List.of("fire.fly"), "fire_group"));

        manager.resetForScatter();

        // Online ruler is stripped live via clearRuntimeState, never queued for offline revoke.
        assertNull(manager.getPendingPermRevokes().get(ONLINE));
    }
}
