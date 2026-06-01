package org.cubexmc.features.appoint;

import java.util.UUID;

/**
 * 任命数据记录
 */
public class Appointment {

    private final UUID appointeeUuid;
    private final String permSetKey;
    private final UUID appointerUuid;
    private final long appointedAt;

    public Appointment(UUID appointeeUuid, String permSetKey, UUID appointerUuid, long appointedAt) {
        this.appointeeUuid = appointeeUuid;
        this.permSetKey = permSetKey;
        this.appointerUuid = appointerUuid;
        this.appointedAt = appointedAt;
    }

    public UUID getAppointeeUuid() {
        return appointeeUuid;
    }

    public String getPermSetKey() {
        return permSetKey;
    }

    public UUID getAppointerUuid() {
        return appointerUuid;
    }

    public long getAppointedAt() {
        return appointedAt;
    }
}
