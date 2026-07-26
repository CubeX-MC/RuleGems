package org.cubexmc.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobalOperationCoordinatorTest {
    @Test
    void serializesReloadAndScatter() {
        GlobalOperationCoordinator coordinator = new GlobalOperationCoordinator();

        assertTrue(coordinator.tryBegin(GlobalOperation.RELOAD));
        assertEquals(GlobalOperation.RELOAD, coordinator.current());
        assertFalse(coordinator.tryBegin(GlobalOperation.SCATTER));

        coordinator.end(GlobalOperation.RELOAD);
        assertNull(coordinator.current());
        assertTrue(coordinator.tryBegin(GlobalOperation.SCATTER));
        coordinator.end(GlobalOperation.SCATTER);
    }
}
