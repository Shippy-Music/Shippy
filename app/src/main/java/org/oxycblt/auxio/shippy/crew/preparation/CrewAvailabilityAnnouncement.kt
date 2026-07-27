/*
 * Copyright (c) 2026 Auxio Project
 * CrewAvailabilityAnnouncement.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.crew.preparation

import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.QueueItemId

const val MAX_CREW_AVAILABILITY_ENTRIES = 32

/** A path-free ability to resolve one exact queue item at one known Crew checkpoint. */
data class CrewAvailabilityEntry(val queueItemId: QueueItemId, val availability: CrewAvailability)

/**
 * Transient capability announcement. It is deliberately not a reducer event or checkpoint field.
 * Queue-item IDs and an enum are the complete payload: provider URLs, candidate IDs, and local
 * paths must never be represented here.
 */
data class CrewAvailabilityAnnouncement(
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val publishingMemberId: CrewMemberId,
    val knownTerm: CoordinatorTerm,
    val knownSequence: EventSequence,
    val entries: List<CrewAvailabilityEntry>,
) {
    init {
        require(sessionId.protocolVersion == protocolVersion) {
            "Availability session protocol must match"
        }
        require(publishingMemberId.protocolVersion == protocolVersion) {
            "Availability publisher protocol must match"
        }
        require(entries.size <= MAX_CREW_AVAILABILITY_ENTRIES) {
            "Availability announcement exceeds $MAX_CREW_AVAILABILITY_ENTRIES items"
        }
        require(entries.map(CrewAvailabilityEntry::queueItemId).distinct().size == entries.size) {
            "Availability contains duplicate queue items"
        }
    }

    fun availabilityByQueueItem(): Map<QueueItemId, CrewAvailability> =
        entries.associate { it.queueItemId to it.availability }
}
