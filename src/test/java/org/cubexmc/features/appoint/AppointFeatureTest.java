package org.cubexmc.features.appoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.cubexmc.RuleGems;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.GemAllowanceManager;
import org.cubexmc.manager.PowerStructureManager;
import org.cubexmc.model.AllowedCommand;
import org.cubexmc.model.AppointDefinition;
import org.cubexmc.model.EffectConfig;
import org.cubexmc.model.PowerCondition;
import org.cubexmc.model.PowerStructure;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class AppointFeatureTest {

    private static final UUID APPOINTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID APPOINTEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @TempDir
    Path tempDir;

    private RuleGems plugin;
    private PowerStructureManager psm;
    private GemManager gemManager;
    private GemAllowanceManager allowanceManager;
    private AppointFeature feature;
    private MockedStatic<Bukkit> mockedBukkit;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(RuleGems.class);
        psm = mock(PowerStructureManager.class);
        gemManager = mock(GemManager.class);
        allowanceManager = mock(GemAllowanceManager.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("AppointFeatureTest"));
        when(plugin.getPowerStructureManager()).thenReturn(psm);
        when(plugin.getGemManager()).thenReturn(gemManager);
        when(gemManager.getAllowanceManager()).thenReturn(allowanceManager);
        when(psm.applyStructure(any(Player.class), any(PowerStructure.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);

        mockedBukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
        feature = new AppointFeature(plugin);
        configureBackingStore(feature);
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void appointGrantsPowerAndChildAppointPermission() throws Exception {
        Player appointer = mockPlayer(APPOINTER_ID, "Ruler");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(true);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);

        AppointDefinition advisor = createDefinition("advisor", List.of("perm.advisor"), List.of(),
                List.of(), Map.of());
        AppointDefinition guard = createDefinition("guard", List.of("perm.guard"), List.of("guards"),
                List.of(new EffectConfig(PotionEffectType.SPEED, 1)), Map.of("advisor", advisor),
                List.of(new AllowedCommand("cxjail", 2, List.of("console:cmi jail %arg1% guard 5m"), 0)));
        appointDefinitions(feature).put("guard", guard);

        assertTrue(feature.appoint(appointer, appointee, "guard"));
        assertTrue(feature.isAppointed(APPOINTEE_ID, "guard"));

        verify(psm).clearNamespace(appointee, "appoint");
        ArgumentCaptor<PowerStructure> appliedPower = ArgumentCaptor.forClass(PowerStructure.class);
        verify(psm).applyStructure(eq(appointee), appliedPower.capture(), eq("appoint"), eq("guard"), eq(true));
        assertTrue(appliedPower.getValue().getPermissions().contains("perm.guard"));
        assertTrue(appliedPower.getValue().getPermissions().contains("rulegems.appoint.advisor"));
        assertTrue(appliedPower.getValue().getVaultGroups().contains("guards"));
        assertTrue(appliedPower.getValue().getEffects().stream()
                .anyMatch(effect -> PotionEffectType.SPEED.equals(effect.getEffectType())));
        assertTrue(appliedPower.getValue().getAllowedCommands().stream()
                .anyMatch(command -> "cxjail".equals(command.getLabel())));
        verify(allowanceManager).applyAppointmentAllowedCommands(eq(appointee), eq("guard"),
                any(PowerStructure.class), eq(false));
        verify(allowanceManager).retainAppointmentAllowedCommands(eq(APPOINTEE_ID),
                argThat(active -> active.contains("guard")));
    }

    @Test
    void dismissByOriginalAppointerRemovesAppointment() throws Exception {
        Player appointer = mockPlayer(APPOINTER_ID, "Ruler");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(true);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);
        mockedBukkit.when(() -> Bukkit.getPlayer(APPOINTEE_ID)).thenReturn(appointee);

        AppointDefinition guard = createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of());
        appointDefinitions(feature).put("guard", guard);
        assertTrue(feature.appoint(appointer, appointee, "guard"));

        reset(psm, allowanceManager);
        assertTrue(feature.dismiss(appointer, APPOINTEE_ID, "guard"));

        assertFalse(feature.isAppointed(APPOINTEE_ID, "guard"));
        verify(allowanceManager).removeAppointmentAllowedCommands(APPOINTEE_ID, "guard");
        verify(psm).clearNamespace(appointee, "appoint");
        verify(psm, never()).applyStructure(eq(appointee), any(), eq("appoint"), anyString(), anyBoolean());
    }

    @Test
    void appointeeCanToggleOwnAppointmentPower() throws Exception {
        Player appointer = mockPlayer(APPOINTER_ID, "Ruler");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(true);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);

        AppointDefinition guard = createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of(),
                List.of(new AllowedCommand("cxjail", 2, List.of("console:cmi jail %arg1% guard 5m"), 0)));
        appointDefinitions(feature).put("guard", guard);
        assertTrue(feature.appoint(appointer, appointee, "guard"));

        reset(psm, allowanceManager);
        when(psm.applyStructure(any(Player.class), any(PowerStructure.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);

        assertTrue(feature.setAppointmentPowerEnabled(appointee, "guard", false));
        assertTrue(feature.isAppointmentToggledOff(APPOINTEE_ID, "guard"));
        verify(psm).clearNamespace(appointee, "appoint");
        verify(psm, never()).applyStructure(eq(appointee), any(), eq("appoint"), anyString(), anyBoolean());
        verify(allowanceManager).retainAppointmentAllowedCommands(eq(APPOINTEE_ID),
                argThat(active -> !active.contains("guard")));

        reset(psm, allowanceManager);
        when(psm.applyStructure(any(Player.class), any(PowerStructure.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);

        assertTrue(feature.setAppointmentPowerEnabled(appointee, "guard", true));
        assertFalse(feature.isAppointmentToggledOff(APPOINTEE_ID, "guard"));
        verify(psm).applyStructure(eq(appointee), any(PowerStructure.class), eq("appoint"), eq("guard"), eq(true));
        verify(allowanceManager).retainAppointmentAllowedCommands(eq(APPOINTEE_ID),
                argThat(active -> active.contains("guard")));
    }

    @Test
    void appointerLosingPermissionCascadeRevokesAppointee() throws Exception {
        Player appointer = mockPlayer(APPOINTER_ID, "Ruler");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(true);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);

        AppointDefinition guard = createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of());
        appointDefinitions(feature).put("guard", guard);
        assertTrue(feature.appoint(appointer, appointee, "guard"));

        reset(psm, allowanceManager);
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(false);
        mockedBukkit.when(() -> Bukkit.getPlayer(APPOINTER_ID)).thenReturn(appointer);
        mockedBukkit.when(() -> Bukkit.getPlayer(APPOINTEE_ID)).thenReturn(appointee);

        feature.onAppointerLostPermission(APPOINTER_ID, "guard");

        assertFalse(feature.isAppointed(APPOINTEE_ID, "guard"));
        verify(allowanceManager).removeAppointmentAllowedCommands(APPOINTEE_ID, "guard");
        verify(psm).clearNamespace(appointee, "appoint");
    }

    @Test
    void appointFailsWithoutPermissionForNormalPlayer() throws Exception {
        Player appointer = mockPlayer(APPOINTER_ID, "Ruler");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(appointer.hasPermission("rulegems.appoint.guard")).thenReturn(false);
        when(appointer.hasPermission("rulegems.admin")).thenReturn(false);

        appointDefinitions(feature).put("guard",
                createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of()));

        assertFalse(feature.appoint(appointer, appointee, "guard"));
        assertFalse(feature.isAppointed(APPOINTEE_ID, "guard"));
        verify(psm, never()).applyStructure(eq(appointee), any(), eq("appoint"), anyString(), anyBoolean());
    }

    @Test
    void adminCanAppointWithoutSpecificPermissionNode() throws Exception {
        Player admin = mockPlayer(APPOINTER_ID, "Admin");
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        when(admin.hasPermission("rulegems.appoint.guard")).thenReturn(false);
        when(admin.hasPermission("rulegems.admin")).thenReturn(true);

        appointDefinitions(feature).put("guard",
                createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of()));

        assertTrue(feature.appoint(admin, appointee, "guard"));
        assertTrue(feature.isAppointed(APPOINTEE_ID, "guard"));
    }

    @Test
    void conditionRefreshSchedulesPermissionRefreshOnPlayerEntityThread() throws Exception {
        Player appointee = mockPlayer(APPOINTEE_ID, "Knight");
        AppointDefinition guard = createDefinition("guard", List.of("perm.guard"), List.of(), List.of(), Map.of());
        PowerCondition condition = new PowerCondition();
        condition.setTimeEnabled(true);
        condition.setTimeType(PowerCondition.TimeType.DAY);
        guard.getPowerStructure().setCondition(condition);
        appointDefinitions(feature).put("guard", guard);

        mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(appointee));

        AtomicReference<Runnable> refreshTask = new AtomicReference<>();
        try (MockedStatic<SchedulerUtil> scheduler = org.mockito.Mockito.mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.globalRun(eq(plugin), any(Runnable.class), eq(600L), eq(600L)))
                    .thenAnswer(invocation -> {
                        refreshTask.set(invocation.getArgument(1));
                        return new Object();
                    });
            scheduler.when(SchedulerUtil::isFolia).thenReturn(true);

            invokeStartConditionRefreshTask();
            assertNotNull(refreshTask.get());

            refreshTask.get().run();

            scheduler.verify(() -> SchedulerUtil.entityRun(eq(plugin), eq(appointee), any(Runnable.class),
                    eq(0L), eq(-1L)));
        }
    }

    private Player mockPlayer(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private AppointDefinition createDefinition(String key, List<String> permissions, List<String> groups,
            List<EffectConfig> effects, Map<String, AppointDefinition> childAppoints) {
        return createDefinition(key, permissions, groups, effects, childAppoints, List.of());
    }

    private AppointDefinition createDefinition(String key, List<String> permissions, List<String> groups,
            List<EffectConfig> effects, Map<String, AppointDefinition> childAppoints,
            List<AllowedCommand> allowedCommands) {
        AppointDefinition definition = new AppointDefinition(key);
        definition.setDisplayName(key);
        definition.setAppointSound("");
        definition.setRevokeSound("");
        definition.setOnAppoint(List.of());
        definition.setOnRevoke(List.of());

        PowerStructure power = new PowerStructure();
        power.setPermissions(new ArrayList<>(permissions));
        power.setVaultGroups(new ArrayList<>(groups));
        power.setEffects(new ArrayList<>(effects));
        power.setAppoints(new HashMap<>(childAppoints));
        power.setAllowedCommands(new ArrayList<>(allowedCommands));
        definition.setPowerStructure(power);
        return definition;
    }

    @SuppressWarnings("unchecked")
    private Map<String, AppointDefinition> appointDefinitions(AppointFeature appointFeature) throws Exception {
        Field field = AppointFeature.class.getDeclaredField("appointDefinitions");
        field.setAccessible(true);
        return (Map<String, AppointDefinition>) field.get(appointFeature);
    }

    private void configureBackingStore(AppointFeature appointFeature) throws Exception {
        File dataFile = tempDir.resolve("appoints.yml").toFile();
        if (!dataFile.exists()) {
            dataFile.createNewFile();
        }
        setField(appointFeature, "data", new YamlConfiguration());
        setField(appointFeature, "dataFile", dataFile);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = AppointFeature.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void invokeStartConditionRefreshTask() throws Exception {
        Method method = AppointFeature.class.getDeclaredMethod("startConditionRefreshTask");
        method.setAccessible(true);
        method.invoke(feature);
    }
}
