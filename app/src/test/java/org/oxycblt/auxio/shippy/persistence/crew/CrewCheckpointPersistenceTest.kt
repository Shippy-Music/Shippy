/*
 * Copyright (c) 2026 Shippy contributors
 * CrewCheckpointPersistenceTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toSnapshot

class CrewCheckpointPersistenceTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("crew", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)
    private val snapshot =
        CrewState(
                sessionId = sessionId,
                protocolVersion = protocol,
                term = CoordinatorTerm(3),
                lastSequence = EventSequence(14),
                coordinatorMemberId = coordinatorId,
                members = listOf(CrewMember(coordinatorId, "Coordinator")),
            )
            .toSnapshot()

    @Test
    fun `checkpoint payload and indexed identity round trip`() {
        val entity = CrewCheckpointPersistence.encode(snapshot, nowEpochMs = 2_000)

        assertEquals(
            CrewCheckpointLoadResult.Loaded(PersistedCrewCheckpoint(snapshot, 2_000)),
            CrewCheckpointPersistence.decode(entity),
        )
    }

    @Test
    fun `payload corruption is rejected`() {
        val entity = CrewCheckpointPersistence.encode(snapshot, nowEpochMs = 2_000)
        val corrupt =
            entity.copy(
                snapshotPayload =
                    entity.snapshotPayload.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    }
            )

        assertTrue(CrewCheckpointPersistence.decode(corrupt) is CrewCheckpointLoadResult.CorruptRemoved)
    }

    @Test
    fun `indexed session mismatch is rejected`() {
        val entity =
            CrewCheckpointPersistence.encode(snapshot, nowEpochMs = 2_000)
                .copy(sessionId = "different")

        assertEquals(
            CrewCheckpointLoadResult.CorruptRemoved("different"),
            CrewCheckpointPersistence.decode(entity),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative persistence timestamp is rejected`() {
        CrewCheckpointPersistence.encode(snapshot, nowEpochMs = -1)
    }
}
