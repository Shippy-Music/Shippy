/*
 * Copyright (c) 2026 Auxio Project
 * CrewEvent.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.core

import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId

data class DurableCrewEvent(
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val term: CoordinatorTerm,
    val sequence: EventSequence,
    val id: DurableEventId,
    /** Coordinator/sequencer identity asserted by the event envelope. */
    val publisherMemberId: CrewMemberId,
    /** Member who requested or issued the collaborative action. */
    val issuingMemberId: CrewMemberId,
    val clientMonotonicTimestampMs: Long,
    val action: CrewAction,
) {
    init {
        require(sequence.value > 0) { "Durable event sequence must be positive" }
        require(clientMonotonicTimestampMs >= 0) { "Client monotonic timestamp cannot be negative" }
    }
}

sealed interface CrewAction {
    data class MemberJoined(val member: CrewMember) : CrewAction

    data class MemberUpdated(val member: CrewMember) : CrewAction

    data class MemberLeft(val memberId: CrewMemberId) : CrewAction

    data class QueueReplaced(val items: List<QueueItem>) : CrewAction

    data class QueueItemInserted(val item: QueueItem, val index: Int) : CrewAction

    data class QueueItemMoved(val itemId: QueueItemId, val newIndex: Int) : CrewAction

    data class QueueItemRemoved(val itemId: QueueItemId) : CrewAction

    data class CurrentItemChanged(val itemId: QueueItemId) : CrewAction

    data class Play(val positionAtEpochMs: Long, val sessionEpochMs: Long) : CrewAction

    data class Pause(val positionAtEpochMs: Long, val sessionEpochMs: Long) : CrewAction

    data class Seek(val positionAtEpochMs: Long, val sessionEpochMs: Long) : CrewAction

    data class ShuffleChanged(val enabled: Boolean) : CrewAction

    data class RepeatChanged(val mode: CrewRepeatMode) : CrewAction

    data class CoordinatorTransferred(val newCoordinatorMemberId: CrewMemberId) : CrewAction

    /** Irreversibly closes the active Crew after this ordered event converges. */
    data object SessionEnded : CrewAction
}

sealed interface CrewEventResult {
    data class Applied(val state: CrewState) : CrewEventResult

    data class DuplicateRejected(val state: CrewState) : CrewEventResult

    data class StaleTermRejected(
        val currentTerm: CoordinatorTerm,
        val receivedTerm: CoordinatorTerm,
    ) : CrewEventResult

    data class StaleSequenceRejected(
        val lastSequence: EventSequence,
        val receivedSequence: EventSequence,
    ) : CrewEventResult

    data class SnapshotRequired(
        val currentTerm: CoordinatorTerm,
        val expectedSequence: EventSequence,
        val receivedTerm: CoordinatorTerm,
        val receivedSequence: EventSequence,
    ) : CrewEventResult

    data class Rejected(val reason: String) : CrewEventResult
}
