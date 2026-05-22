package org.cubexmc.features.revoke;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.features.FeatureManager;
import org.cubexmc.features.appoint.AppointFeature;
import org.cubexmc.manager.ConfigManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemDefinitionParser;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.model.AppointDefinition;
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.utils.EffectUtils;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevokeFeatureTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID FIRE_GEM_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID JUDGMENT_GEM_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    @Mock
    private RuleGems plugin;
    @Mock
    private ConfigManager configManager;
    @Mock
    private GemDefinitionParser gemParser;
    @Mock
    private GameplayConfig gameplayConfig;
    @Mock
    private EffectUtils effectUtils;
    @Mock
    private LanguageManager languageManager;
    @Mock
    private Player actor;
    @Mock
    private Player target;

    private MockedStatic<Bukkit> mockedBukkit;
    private MockedStatic<SchedulerUtil> mockedSchedulerUtil;
    private GemManager gemManager;
    private RevokeFeature feature;
    private AtomicLong now;

    @BeforeEach
    void setUp() throws Exception {
        mockedBukkit = mockStatic(Bukkit.class);
        mockedSchedulerUtil = mockStatic(SchedulerUtil.class);
        now = new AtomicLong(1_000_000L);

        writeRevokeConfig(true, true, 60, true, false, 30);

        lenient().when(plugin.getName()).thenReturn("RuleGems");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("RuleGemsTest"));
        lenient().when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        lenient().when(plugin.getPowerStructureManager()).thenReturn(null);
        lenient().when(plugin.getHistoryLogger()).thenReturn(null);
        lenient().when(plugin.getLanguageManager()).thenReturn(languageManager);
        lenient().when(plugin.isEnabled()).thenReturn(false);
        lenient().when(configManager.getGemsData()).thenReturn(new YamlConfiguration());

        PowerStructure firePower = new PowerStructure();
        firePower.setAppoints(Map.of("guard", new AppointDefinition("guard")));
        GemDefinition fire = new GemDefinition.Builder("fire")
                .material(Material.DIAMOND_BLOCK)
                .displayName("Fire Gem")
                .powerStructure(firePower)
                .build();
        GemDefinition judgment = new GemDefinition.Builder("judgment")
                .material(Material.EMERALD_BLOCK)
                .displayName("Judgment Gem")
                .powerStructure(new PowerStructure())
                .build();
        lenient().when(gemParser.getGemDefinitions()).thenReturn(List.of(fire, judgment));

        lenient().when(actor.getUniqueId()).thenReturn(ACTOR_ID);
        lenient().when(actor.getName()).thenReturn("Actor");
        lenient().when(target.getUniqueId()).thenReturn(TARGET_ID);
        lenient().when(target.getName()).thenReturn("Target");
        lenient().when(target.isOnline()).thenReturn(true);

        mockedBukkit.when(() -> Bukkit.getPlayer("Target")).thenReturn(target);
        mockedBukkit.when(() -> Bukkit.getPlayer(TARGET_ID)).thenReturn(target);

        setActorInventory(ruleGemItem(JUDGMENT_GEM_ID));

        gemManager = new GemManager(plugin, configManager, gemParser, gameplayConfig, effectUtils, languageManager);
        gemManager.getStateManager().getGemUuidToKey().put(FIRE_GEM_ID, "fire");
        gemManager.getStateManager().getGemUuidToKey().put(JUDGMENT_GEM_ID, "judgment");
        gemManager.getStateManager().getGemUuidToHolder().put(JUDGMENT_GEM_ID, actor);
        gemManager.getPermissionManager().getGemIdToRedeemer().put(FIRE_GEM_ID, TARGET_ID);
        gemManager.getPermissionManager().getOwnerKeyCount().put(TARGET_ID,
                new java.util.HashMap<>(java.util.Map.of("fire", 1)));
        gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().put(TARGET_ID,
                new java.util.HashSet<>(Set.of("fire")));

        feature = new RevokeFeature(plugin, gemManager, now::get);
        feature.initialize();
    }

    @AfterEach
    void tearDown() {
        if (mockedSchedulerUtil != null) {
            mockedSchedulerUtil.close();
        }
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void requestCreatesConfirmationWithoutMutatingPowerState() {
        RevokeResult result = feature.requestRevoke(actor, "judgment", "Target", "fire");

        assertEquals(RevokeResult.Status.CONFIRMATION_REQUIRED, result.getStatus());
        assertEquals(TARGET_ID, gemManager.getPermissionManager().getGemIdToRedeemer().get(FIRE_GEM_ID));
        assertTrue(gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().get(TARGET_ID).contains("fire"));
        assertTrue(gemManager.getStateManager().getGemUuidToHolder().containsKey(JUDGMENT_GEM_ID));
    }

    @Test
    void confirmRevokesTargetPowerConsumesTriggerGemAndStartsCooldown() {
        feature.requestRevoke(actor, "judgment", "Target", "fire");

        RevokeResult result = feature.confirm(actor);

        assertEquals(RevokeResult.Status.SUCCESS, result.getStatus());
        assertFalse(gemManager.getPermissionManager().getGemIdToRedeemer().containsKey(FIRE_GEM_ID));
        assertEquals(0, gemManager.getPermissionManager().getOwnerKeyCount().get(TARGET_ID).get("fire"));
        assertFalse(gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().containsKey(TARGET_ID));
        assertFalse(gemManager.getStateManager().getGemUuidToHolder().containsKey(JUDGMENT_GEM_ID));

        RevokeResult second = feature.requestRevoke(actor, "judgment", "Target", "fire");
        assertEquals(RevokeResult.Status.TARGET_HAS_NO_POWER, second.getStatus());
    }

    @Test
    void missingTriggerGemFailsWithoutMutatingPowerState() {
        setActorInventory();

        RevokeResult result = feature.requestRevoke(actor, "judgment", "Target", "fire");

        assertEquals(RevokeResult.Status.MISSING_TRIGGER, result.getStatus());
        assertEquals(TARGET_ID, gemManager.getPermissionManager().getGemIdToRedeemer().get(FIRE_GEM_ID));
        assertTrue(gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().get(TARGET_ID).contains("fire"));
    }

    @Test
    void expiredConfirmationCannotRevokePower() {
        feature.requestRevoke(actor, "judgment", "Target", "fire");
        now.addAndGet(31_000L);

        RevokeResult result = feature.confirm(actor);

        assertEquals(RevokeResult.Status.CONFIRMATION_EXPIRED, result.getStatus());
        assertEquals(TARGET_ID, gemManager.getPermissionManager().getGemIdToRedeemer().get(FIRE_GEM_ID));
        assertTrue(gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().get(TARGET_ID).contains("fire"));
        assertTrue(gemManager.getStateManager().getGemUuidToHolder().containsKey(JUDGMENT_GEM_ID));
    }

    @Test
    void offlineTargetAllowedRevokesRecordedPower() throws Exception {
        writeRevokeConfig(true, false, 0, true, true, 30);
        feature.reload();
        OfflinePlayer offlineTarget = mock(OfflinePlayer.class);
        when(offlineTarget.getUniqueId()).thenReturn(TARGET_ID);
        when(offlineTarget.getName()).thenReturn("OfflineTarget");
        when(offlineTarget.hasPlayedBefore()).thenReturn(true);
        mockedBukkit.when(() -> Bukkit.getPlayer("OfflineTarget")).thenReturn(null);
        mockedBukkit.when(() -> Bukkit.getOfflinePlayer("OfflineTarget")).thenReturn(offlineTarget);

        RevokeResult request = feature.requestRevoke(actor, "judgment", "OfflineTarget", "fire");
        RevokeResult confirm = feature.confirm(actor);

        assertEquals(RevokeResult.Status.CONFIRMATION_REQUIRED, request.getStatus());
        assertEquals(RevokeResult.Status.SUCCESS, confirm.getStatus());
        assertFalse(gemManager.getPermissionManager().getGemIdToRedeemer().containsKey(FIRE_GEM_ID));
        assertFalse(gemManager.getPermissionManager().getPlayerUuidToRedeemedKeys().containsKey(TARGET_ID));
        assertTrue(gemManager.getStateManager().getGemUuidToHolder().containsKey(JUDGMENT_GEM_ID));
    }

    @Test
    void revokingPowerTriggersAppointCascadeForOnlineTarget() {
        FeatureManager featureManager = mock(FeatureManager.class);
        AppointFeature appointFeature = mock(AppointFeature.class);
        when(plugin.getFeatureManager()).thenReturn(featureManager);
        when(featureManager.getAppointFeature()).thenReturn(appointFeature);
        when(appointFeature.isEnabled()).thenReturn(true);
        feature.requestRevoke(actor, "judgment", "Target", "fire");

        RevokeResult result = feature.confirm(actor);

        assertEquals(RevokeResult.Status.SUCCESS, result.getStatus());
        verify(appointFeature).onAppointerLostPermission(TARGET_ID, "guard");
    }

    @Test
    void guiHelpersExposeRevokableTargetPowers() {
        when(actor.hasPermission("rulegems.revoke")).thenReturn(true);

        List<String> powers = feature.getRevokablePowers(actor, TARGET_ID, Set.of("fire", "judgment"));

        assertEquals(List.of("fire"), powers);
    }

    private void writeRevokeConfig(boolean requireHeld, boolean consumeGem, long cooldown, boolean confirmRequired,
            boolean allowOfflineTarget, int confirmTimeoutSeconds) throws Exception {
        Path featuresDir = tempDir.resolve("features");
        Files.createDirectories(featuresDir);
        Files.writeString(featuresDir.resolve("revoke.yml"), """
                enabled: true
                confirm_timeout: %d
                rules:
                  judgment:
                    display_name: "&cJudgment Gem"
                    trigger_gem: judgment
                    target_powers:
                      - fire
                    require_held: %s
                    consume_gem: %s
                    cooldown: %d
                    confirm_required: %s
                    broadcast: false
                    allow_offline_target: %s
                """.formatted(confirmTimeoutSeconds, requireHeld, consumeGem, cooldown, confirmRequired,
                allowOfflineTarget));
    }

    private void setActorInventory(ItemStack... contents) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(actor.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getContents()).thenReturn(contents);
        lenient().when(inventory.getItemInOffHand()).thenReturn(null);
    }

    private ItemStack ruleGemItem(UUID gemId) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        lenient().when(item.hasItemMeta()).thenReturn(true);
        lenient().when(item.getItemMeta()).thenReturn(meta);
        lenient().when(meta.getPersistentDataContainer()).thenReturn(pdc);
        lenient().when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        lenient().when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(gemId.toString());
        return item;
    }
}
