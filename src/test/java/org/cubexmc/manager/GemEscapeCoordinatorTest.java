package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.RuleGems;
import org.cubexmc.utils.SchedulerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GemEscapeCoordinatorTest {

    private static final UUID GEM_A =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GEM_B =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final long START_TIME = 1_000_000L;
    private static final long MINIMUM_UNMOVED_TICKS = 100L;

    @Mock private RuleGems plugin;
    @Mock private GameplayConfig gameplayConfig;
    @Mock private GemStateManager stateManager;
    @Mock private World world;

    private final AtomicLong now = new AtomicLong(START_TIME);
    private final AtomicInteger saves = new AtomicInteger();
    private final Map<UUID, String> gemKeys = new ConcurrentHashMap<>();
    private final Map<UUID, Location> gemLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Object> retryTasks = new ConcurrentHashMap<>();
    private final Deque<ScheduledCall> scheduledCalls = new ArrayDeque<>();
    private final Set<Object> cancelledHandles = new HashSet<>();
    private final List<GemEscapeRequest> requests = new ArrayList<>();
    private final List<Consumer<GemEscapeRelocationResult>> completions = new ArrayList<>();
    private final List<GemEscapeRequest> successes = new ArrayList<>();

    private MockedStatic<SchedulerUtil> scheduler;
    private GemEscapeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("GemEscapeCoordinatorTest"));

        lenient().when(gameplayConfig.isGemEscapeEnabled()).thenReturn(true);
        lenient().when(gameplayConfig.getGemEscapeMinIntervalTicks()).thenReturn(200L);
        lenient().when(gameplayConfig.getGemEscapeMaxIntervalTicks()).thenReturn(200L);
        lenient().when(gameplayConfig.getGemEscapeMinimumUnmovedTicks())
                .thenReturn(MINIMUM_UNMOVED_TICKS);
        lenient().when(gameplayConfig.getGemEscapeClusterRadius()).thenReturn(96.0);
        lenient().when(gameplayConfig.getGemEscapeClusterWeight()).thenReturn(2.0);
        lenient().when(gameplayConfig.getGemEscapeMaxFailedRounds()).thenReturn(3);
        lenient().when(gameplayConfig.getGemEscapeMaxLocalEscapesWithoutPickup()).thenReturn(3);
        lenient().when(gameplayConfig.getGemEscapeRetryDelayTicks()).thenReturn(7L);

        lenient().when(stateManager.getGemUuidToKey()).thenReturn(gemKeys);
        lenient().when(stateManager.getGemUuidToLocation()).thenReturn(gemLocations);
        lenient().when(stateManager.getGemLocation(any(UUID.class)))
                .thenAnswer(invocation -> gemLocations.get(invocation.getArgument(0)));
        lenient().when(stateManager.getGemHolder(any(UUID.class))).thenReturn(null);

        scheduler = mockStatic(SchedulerUtil.class);
        scheduler.when(() -> SchedulerUtil.globalRun(
                        eq(plugin), any(Runnable.class), anyLong(), eq(-1L)))
                .thenAnswer(invocation -> {
                    Object handle = new Object();
                    scheduledCalls.addLast(new ScheduledCall(
                            invocation.getArgument(1), invocation.getArgument(2), handle));
                    return handle;
                });
        scheduler.when(() -> SchedulerUtil.cancelTask(any()))
                .thenAnswer(invocation -> {
                    Object handle = invocation.getArgument(0);
                    if (handle != null) cancelledHandles.add(handle);
                    return null;
                });

        coordinator = newCoordinator();
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    void globalCycleSelectsAtMostOneEligibleGem() {
        addGem(GEM_A, 0.0, 0.0);
        addGem(GEM_B, 500.0, 500.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);

        runNextScheduledCall();

        assertEquals(1, requests.size());
        assertEquals(GEM_A, requests.get(0).getGemId());
        assertEquals(GemEscapeMode.LOCAL, requests.get(0).getMode());
        assertEquals(1, scheduledCalls.size(), "the following global cycle remains scheduled");
    }

    @Test
    void minimumUnmovedDurationUsesAnInclusiveBoundary() {
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();

        now.set(START_TIME + MINIMUM_UNMOVED_TICKS * 50L - 1L);
        runNextScheduledCall();
        assertTrue(requests.isEmpty());

        now.incrementAndGet();
        runNextScheduledCall();
        assertEquals(1, requests.size());
        assertEquals(GEM_A, requests.get(0).getGemId());
    }

    @Test
    void configuredFailedRoundLimitImmediatelyEscalatesOneGemToGlobalFallback() {
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();

        assertRequest(0, GemEscapeMode.LOCAL, 0);
        complete(0, GemEscapeRelocationStatus.FAILED, null);
        runRetry(GEM_A);

        assertRequest(1, GemEscapeMode.LOCAL, 1);
        complete(1, GemEscapeRelocationStatus.FAILED, null);
        runRetry(GEM_A);

        assertRequest(2, GemEscapeMode.LOCAL, 2);
        complete(2, GemEscapeRelocationStatus.FAILED, null);

        assertEquals(4, requests.size(), "the Nth failure should trigger fallback without another delay");
        assertRequest(3, GemEscapeMode.GLOBAL_FALLBACK, 3);
        assertNull(retryTasks.get(GEM_A));

        Location destination = new Location(world, 1000.0, 70.0, -1000.0);
        complete(3, GemEscapeRelocationStatus.SUCCESS, destination);

        Map<String, Object> snapshot = snapshot();
        String base = "escape-state.gems." + GEM_A;
        assertFalse(snapshot.containsKey(base + ".failed_rounds"));
        assertFalse(snapshot.containsKey(base + ".local_escapes_without_pickup"));
        assertEquals(now.get(), snapshot.get(base + ".last_moved_at"));
        assertEquals(List.of(requests.get(3)), successes);
    }

    @Test
    void successfulLocalRetryClearsFailureState() {
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();

        complete(0, GemEscapeRelocationStatus.FAILED, null);
        runRetry(GEM_A);
        assertRequest(1, GemEscapeMode.LOCAL, 1);

        complete(1, GemEscapeRelocationStatus.SUCCESS, new Location(world, 50.0, 64.0, 50.0));

        Map<String, Object> snapshot = snapshot();
        String base = "escape-state.gems." + GEM_A;
        assertFalse(snapshot.containsKey(base + ".failed_rounds"));
        assertEquals(1, snapshot.get(base + ".local_escapes_without_pickup"));
        assertNull(retryTasks.get(GEM_A));
    }

    @Test
    void localEscapeStreakTriggersFallbackAndFallbackSuccessResetsTheStreak() {
        lenient().when(gameplayConfig.getGemEscapeMaxLocalEscapesWithoutPickup()).thenReturn(1);
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();

        Location localDestination = new Location(world, 100.0, 64.0, 100.0);
        complete(0, GemEscapeRelocationStatus.SUCCESS, localDestination);
        gemLocations.put(GEM_A, localDestination);
        String streakPath =
                "escape-state.gems." + GEM_A + ".local_escapes_without_pickup";
        assertEquals(1, snapshot().get(streakPath));

        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();

        assertRequest(1, GemEscapeMode.GLOBAL_FALLBACK, 0);
        complete(1, GemEscapeRelocationStatus.SUCCESS, new Location(world, 900.0, 64.0, 900.0));
        assertFalse(snapshot().containsKey(streakPath));
    }

    @Test
    void reloadAndShutdownCancelTasksAndInvalidateOldCallbacks() {
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();
        assertEquals(1, requests.size());

        ScheduledCall nextGlobal = scheduledCalls.removeFirst();
        int savesBeforeReload = saves.get();
        coordinator.prepareReload();
        complete(0, GemEscapeRelocationStatus.SUCCESS, new Location(world, 20.0, 64.0, 20.0));
        nextGlobal.task.run();

        assertEquals(1, requests.size());
        assertTrue(successes.isEmpty());
        assertEquals(savesBeforeReload, saves.get());
        assertFalse(coordinator.isRelocating(GEM_A));
        scheduler.verify(() -> SchedulerUtil.cancelTask(nextGlobal.handle));

        coordinator.initialize();
        ScheduledCall afterReload = scheduledCalls.removeFirst();
        coordinator.shutdown();
        afterReload.task.run();

        assertEquals(1, requests.size());
        scheduler.verify(() -> SchedulerUtil.cancelTask(afterReload.handle));
    }

    @Test
    void reloadCancelsAndInvalidatesAnAlreadyScheduledRetry() {
        addGem(GEM_A, 0.0, 0.0);
        coordinator.initialize();
        now.addAndGet(MINIMUM_UNMOVED_TICKS * 50L);
        runNextScheduledCall();
        complete(0, GemEscapeRelocationStatus.FAILED, null);

        ScheduledCall retry = findScheduledCall(retryTasks.get(GEM_A));
        coordinator.prepareReload();
        retry.task.run();

        assertEquals(1, requests.size());
        assertFalse(coordinator.isRelocating(GEM_A));
        scheduler.verify(() -> SchedulerUtil.cancelTask(retry.handle));
    }

    @Test
    void persistedEscapeStateRoundTripsAcrossReload() {
        addGem(GEM_A, 0.0, 0.0);
        long nextCycleAt = START_TIME + 5_000L;
        long lastMovedAt = START_TIME - 1_000L;
        String base = "escape-state.gems." + GEM_A;
        YamlConfiguration data = new YamlConfiguration();
        data.set("escape-state.next_cycle_at", nextCycleAt);
        data.set(base + ".last_moved_at", lastMovedAt);
        data.set(base + ".failed_rounds", 2);
        data.set(base + ".local_escapes_without_pickup", 1);

        coordinator.loadState(data);
        coordinator.initialize();
        Map<String, Object> firstSnapshot = snapshot();

        assertEquals(nextCycleAt, firstSnapshot.get("escape-state.next_cycle_at"));
        assertEquals(lastMovedAt, firstSnapshot.get(base + ".last_moved_at"));
        assertEquals(2, firstSnapshot.get(base + ".failed_rounds"));
        assertEquals(1, firstSnapshot.get(base + ".local_escapes_without_pickup"));

        YamlConfiguration reloadedData = new YamlConfiguration();
        firstSnapshot.forEach(reloadedData::set);
        coordinator.prepareReload();
        coordinator.loadState(reloadedData);
        coordinator.initialize();

        assertEquals(firstSnapshot, snapshot());
    }

    private GemEscapeCoordinator newCoordinator() {
        GemEscapeRelocator relocator = (request, completion) -> {
            requests.add(request);
            completions.add(completion);
        };
        GemEscapeSuccessListener successListener = (request, result) -> successes.add(request);
        return new GemEscapeCoordinator(
                plugin,
                gameplayConfig,
                stateManager,
                retryTasks,
                relocator,
                successListener,
                saves::incrementAndGet,
                now::get,
                new Random(7L));
    }

    private void addGem(UUID gemId, double x, double z) {
        gemKeys.put(gemId, "gem");
        gemLocations.put(gemId, new Location(world, x, 64.0, z));
    }

    private void runNextScheduledCall() {
        ScheduledCall call;
        do {
            call = scheduledCalls.pollFirst();
            assertNotNull(call, "expected a scheduled task");
        } while (cancelledHandles.contains(call.handle));
        call.task.run();
    }

    private void runRetry(UUID gemId) {
        Object retryHandle = retryTasks.get(gemId);
        assertNotNull(retryHandle, "expected a retry task");
        ScheduledCall retry = findScheduledCall(retryHandle);
        assertEquals(7L, retry.delay);
        retry.task.run();
    }

    private ScheduledCall findScheduledCall(Object handle) {
        assertNotNull(handle, "expected a scheduled task handle");
        Iterator<ScheduledCall> iterator = scheduledCalls.iterator();
        while (iterator.hasNext()) {
            ScheduledCall call = iterator.next();
            if (call.handle == handle) {
                iterator.remove();
                return call;
            }
        }
        throw new AssertionError("task handle was not present in the scheduled task queue");
    }

    private void complete(
            int requestIndex,
            GemEscapeRelocationStatus status,
            Location destination) {
        completions.get(requestIndex).accept(new GemEscapeRelocationResult(status, destination));
    }

    private void assertRequest(int index, GemEscapeMode mode, int failedRounds) {
        GemEscapeRequest request = requests.get(index);
        assertEquals(GEM_A, request.getGemId());
        assertEquals(mode, request.getMode());
        assertEquals(failedRounds, request.getFailedRounds());
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        coordinator.populateSaveSnapshot(snapshot);
        return snapshot;
    }

    private static final class ScheduledCall {
        private final Runnable task;
        private final long delay;
        private final Object handle;

        private ScheduledCall(Runnable task, long delay, Object handle) {
            this.task = task;
            this.delay = delay;
            this.handle = handle;
        }
    }
}
