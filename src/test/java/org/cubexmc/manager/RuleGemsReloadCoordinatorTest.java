package org.cubexmc.manager;

import org.cubexmc.RuleGems;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuleGemsReloadCoordinatorTest {
    private final GlobalOperationCoordinator operations = new GlobalOperationCoordinator();
    private final List<String> stages = new ArrayList<>();

    private RuleGemsReloadCoordinator coordinator(boolean featuresOk, boolean saveOk, boolean loadOk) {
        return new RuleGemsReloadCoordinator(operations, () -> stages.add("close"),
                () -> { stages.add("features"); if (!featuresOk) throw new IllegalStateException("storage failure"); },
                () -> { stages.add("save"); return saveOk; },
                () -> { stages.add("load"); return loadOk; },
                () -> stages.add("proxies"));
    }

    @Test
    void persistsBeforeLoadingAndRefreshesProxiesLast() {
        assertEquals(RuleGems.ReloadResult.SUCCESS, coordinator(true, true, true).reload());
        assertEquals(List.of("close", "features", "save", "load", "proxies"), stages);
        assertNull(operations.current());
    }

    @Test
    void featureSaveFailureCannotOverwriteRuntimeFromDiskAndReleasesLock() {
        var reload = coordinator(false, true, true);
        assertEquals(RuleGems.ReloadResult.FAILED, reload.reload());
        assertEquals(List.of("close", "features"), stages);
        assertEquals("feature-save", reload.getReport().failures().get(0).stage());
        assertNull(operations.current());
    }

    @Test
    void gemSaveFailureSkipsAllLoadStages() {
        var reload = coordinator(true, false, true);
        assertEquals(RuleGems.ReloadResult.FAILED, reload.reload());
        assertEquals(List.of("close", "features", "save"), stages);
        assertEquals("gem-save", reload.getReport().failures().get(0).stage());
        assertNull(operations.current());
    }

    @Test
    void failedLoadDoesNotPublishNewCommandProxies() {
        var reload = coordinator(true, true, false);
        assertEquals(RuleGems.ReloadResult.FAILED, reload.reload());
        assertEquals(List.of("close", "features", "save", "load"), stages);
        assertNull(operations.current());
    }

    @Test
    void busyOperationDoesNotTouchAnyResource() {
        assertTrue(operations.tryBegin(GlobalOperation.SCATTER));
        assertEquals(RuleGems.ReloadResult.BUSY, coordinator(true, true, true).reload());
        assertTrue(stages.isEmpty());
        assertEquals(GlobalOperation.SCATTER, operations.current());
    }
}
