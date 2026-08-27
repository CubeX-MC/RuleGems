package org.cubexmc.storage;

import org.cubexmc.features.appoint.Appointment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AppointmentStoreTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID ruler = UUID.randomUUID();

    private AppointmentStore store() {
        return new AppointmentStore(directory.resolve("data/appoints.yml").toFile(),
                directory.resolve("features/appoint_data.yml").toFile());
    }

    @Test
    void legacySchemaMigratesWithoutLosingOwnershipTimestampTogglesOrOtherKeys() throws Exception {
        Path old = directory.resolve("features/appoint_data.yml");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "custom: preserved\nappointments:\n  guard:\n    " + player
                + ":\n      appointed_by: " + ruler + "\n      appointed_at: 12345\n"
                + "toggled_off_appointments:\n  " + player + ": [guard]\n");
        AppointmentStore store = store();
        store.reload();
        assertFalse(Files.exists(old));
        Appointment appointment = store.getAppointments().get("guard").get(player);
        assertEquals(ruler, appointment.getAppointerUuid());
        assertEquals(12345, appointment.getAppointedAt());
        assertTrue(store.getToggles().get(player).contains("guard"));
        store.setEnabled(player, "guard", true);
        var restarted = store();
        restarted.reload();
        assertTrue(restarted.getToggles().isEmpty());
        assertTrue(Files.readString(directory.resolve("data/appoints.yml")).contains("custom: preserved"));
        assertThrows(UnsupportedOperationException.class, () -> store.getAppointments().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.getAppointments().get("guard").clear());
    }

    @Test
    void failedFirstReadAndClosePreserveInvalidLegacyData() throws Exception {
        Path old = directory.resolve("features/appoint_data.yml");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "appointments:\n  guard:\n    invalid-uuid: {}\n");
        var store = store();
        assertThrows(Exception.class, store::reload);
        assertThrows(IllegalStateException.class, store::flush);
        store.close();
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(directory.resolve("data/appoints.yml")));
    }

    @Test
    void failedToggleOrRemovalNeverPublishesDirtySnapshot() throws Exception {
        var store = store();
        store.reload();
        store.add(new Appointment(player, "guard", ruler, 12345));
        Path file = directory.resolve("data/appoints.yml");
        String saved = Files.readString(file);
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(Exception.class, () -> store.setEnabled(player, "guard", false));
        assertThrows(Exception.class, () -> store.remove("guard", List.of(player)));
        assertTrue(store.getToggles().isEmpty());
        assertTrue(store.getAppointments().get("guard").containsKey(player));
        Files.delete(file);
        Files.writeString(file, saved);
        store.reload();
        assertTrue(store.getAppointments().get("guard").containsKey(player));
    }
}
