package org.cubexmc.features.appoint

import java.util.UUID

/**
 * 任命数据记录
 */
class Appointment(
    val appointeeUuid: UUID,
    val permSetKey: String,
    val appointerUuid: UUID?,
    val appointedAt: Long,
)
