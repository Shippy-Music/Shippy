/*
 * Copyright (c) 2026 Auxio Project
 * CrewCoordinatorSequencer.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.session

import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewEventResult
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewReducer
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence

private const val MAX_RECENT_SEQUENCED_REQUESTS = 512

data class CrewActionRequest(
    val id: DurableEventId,
    val issuingMemberId: CrewMemberId,
    val clientMonotonicTimestampMs: Long,
    val action: CrewAction,
    /** Canonical sequence observed when the issuer authored this request. */
    val baseSequence: EventSequence? = null,
) {
    init {
        require(clientMonotonicTimestampMs >= 0) {
            "Crew request monotonic timestamp cannot be negative"
        }
    }
}

sealed interface CrewSequenceResult {
    data class Published(val event: DurableCrewEvent, val state: CrewState) : CrewSequenceResult

    data class Duplicate(val event: DurableCrewEvent, val state: CrewState) : CrewSequenceResult

    data class Rejected(val reason: CrewSequenceRejection, val state: CrewState) :
        CrewSequenceResult
}

enum class CrewSequenceRejection {
    NOT_COORDINATOR,
    REQUESTER_MISMATCH,
    REQUESTER_NOT_ACTIVE,
    PROTOCOL_MISMATCH,
    DUPLICATE_RECEIPT_UNAVAILABLE,
    STALE_BASE_REVISION,
    ACTION_REJECTED,
}

/**
 * The coordinator's single serialization point for collaborative member actions.
 *
 * A request ID becomes the durable event ID, allowing optimistic UI to reconcile against the
 * accepted event without conflating the requesting member with the coordinator publisher.
 */
class CrewCoordinatorSequencer(
    initialState: CrewState,
    private val localMemberId: CrewMemberId,
    private val reducer: CrewReducer = CrewReducer(),
) {
    private var currentState = initialState
    private val recentEvents = LinkedHashMap<DurableEventId, DurableCrewEvent>()

    init {
        require(localMemberId.protocolVersion == initialState.protocolVersion) {
            "Crew sequencer member protocol must match the session"
        }
    }

    @Synchronized fun state(): CrewState = currentState

    @Synchronized
    fun sequence(
        request: CrewActionRequest,
        authenticatedRequester: CrewMemberId,
    ): CrewSequenceResult {
        if (request.issuingMemberId != authenticatedRequester) {
            return rejected(CrewSequenceRejection.REQUESTER_MISMATCH)
        }
        if (
            authenticatedRequester.protocolVersion != currentState.protocolVersion ||
                request.issuingMemberId.protocolVersion != currentState.protocolVersion
        ) {
            return rejected(CrewSequenceRejection.PROTOCOL_MISMATCH)
        }
        recentEvents[request.id]?.let { event ->
            return if (event.issuingMemberId == authenticatedRequester) {
                CrewSequenceResult.Duplicate(event, currentState)
            } else {
                rejected(CrewSequenceRejection.REQUESTER_MISMATCH)
            }
        }
        if (request.id in currentState.appliedEventIds) {
            return rejected(CrewSequenceRejection.DUPLICATE_RECEIPT_UNAVAILABLE)
        }
        if (currentState.members.none { it.id == authenticatedRequester }) {
            return rejected(CrewSequenceRejection.REQUESTER_NOT_ACTIVE)
        }
        if (localMemberId != currentState.coordinatorMemberId) {
            return rejected(CrewSequenceRejection.NOT_COORDINATOR)
        }
        if (!isAuthorizedAction(request.action, authenticatedRequester)) {
            return rejected(CrewSequenceRejection.ACTION_REJECTED)
        }
        if (
            request.baseSequence != null &&
                request.baseSequence != currentState.lastSequence &&
                request.action.requiresExactQueueBase()
        ) {
            return rejected(CrewSequenceRejection.STALE_BASE_REVISION)
        }

        val event =
            DurableCrewEvent(
                sessionId = currentState.sessionId,
                protocolVersion = currentState.protocolVersion,
                term = eventTerm(request.action),
                sequence = eventSequence(request.action),
                id = request.id,
                publisherMemberId = localMemberId,
                issuingMemberId = authenticatedRequester,
                clientMonotonicTimestampMs = request.clientMonotonicTimestampMs,
                action = request.action,
            )
        return when (
            val result = reducer.apply(currentState, event, authenticatedPublisher = localMemberId)
        ) {
            is CrewEventResult.Applied -> {
                currentState = result.state
                retain(event)
                CrewSequenceResult.Published(event, currentState)
            }
            is CrewEventResult.DuplicateRejected -> {
                rejected(CrewSequenceRejection.DUPLICATE_RECEIPT_UNAVAILABLE)
            }
            is CrewEventResult.Rejected,
            is CrewEventResult.SnapshotRequired,
            is CrewEventResult.StaleSequenceRejected,
            is CrewEventResult.StaleTermRejected -> rejected(CrewSequenceRejection.ACTION_REJECTED)
        }
    }

    private fun eventTerm(action: CrewAction): CoordinatorTerm =
        if (action is CrewAction.CoordinatorTransferred) {
            currentState.term.next()
        } else {
            currentState.term
        }

    private fun isAuthorizedAction(action: CrewAction, requester: CrewMemberId): Boolean =
        when (action) {
            is CrewAction.MemberJoined -> requester == currentState.coordinatorMemberId
            is CrewAction.MemberUpdated -> action.member.id == requester
            is CrewAction.MemberLeft ->
                action.memberId == requester || requester == currentState.coordinatorMemberId
            else -> true
        }

    /** Stable IDs/anchors can rebase safely; whole replacements and bare indices cannot. */
    private fun CrewAction.requiresExactQueueBase(): Boolean =
        when (this) {
            is CrewAction.QueueReplaced -> true
            is CrewAction.QueueItemInserted -> beforeItemId == null && afterItemId == null
            is CrewAction.QueueItemMoved -> beforeItemId == null && afterItemId == null
            else -> false
        }

    private fun eventSequence(action: CrewAction): EventSequence =
        if (action is CrewAction.CoordinatorTransferred) {
            EventSequence(1)
        } else {
            currentState.lastSequence.next()
        }

    private fun retain(event: DurableCrewEvent) {
        recentEvents[event.id] = event
        while (recentEvents.size > MAX_RECENT_SEQUENCED_REQUESTS) {
            recentEvents.remove(recentEvents.entries.first().key)
        }
    }

    private fun rejected(reason: CrewSequenceRejection) =
        CrewSequenceResult.Rejected(reason, currentState)
}
