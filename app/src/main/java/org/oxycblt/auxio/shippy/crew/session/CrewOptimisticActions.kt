/*
 * Copyright (c) 2026 Shippy contributors
 * CrewOptimisticActions.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.session

import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId

private const val DEFAULT_MAX_PENDING_ACTIONS = 64
private const val DEFAULT_PENDING_TIMEOUT_MS = 15_000L

sealed interface CrewOptimisticSubmitResult {
    data class Pending(val request: CrewActionRequest) : CrewOptimisticSubmitResult

    data class AlreadyPending(val request: CrewActionRequest) : CrewOptimisticSubmitResult

    data object CapacityReached : CrewOptimisticSubmitResult
}

sealed interface CrewOptimisticReconcileResult {
    data class Accepted(
        val request: CrewActionRequest,
        val event: DurableCrewEvent,
    ) : CrewOptimisticReconcileResult

    data class Conflict(
        val request: CrewActionRequest,
        val event: DurableCrewEvent,
    ) : CrewOptimisticReconcileResult

    data object NotPending : CrewOptimisticReconcileResult
}

enum class CrewOptimisticRejectionReason {
    COORDINATOR_REJECTED,
    TIMED_OUT,
    SESSION_CHANGED,
}

data class CrewOptimisticRejection(
    val request: CrewActionRequest,
    val reason: CrewOptimisticRejectionReason,
)

/**
 * Bounded client-side optimistic intent ledger.
 *
 * The UI may immediately reflect a pending action, but only an event with the exact request ID,
 * issuing member, and action confirms it. A mismatched reuse of an ID is surfaced as a protocol
 * conflict instead of silently accepting a different operation.
 */
class CrewOptimisticActionTracker(
    private val maxPendingActions: Int = DEFAULT_MAX_PENDING_ACTIONS,
    private val pendingTimeoutMs: Long = DEFAULT_PENDING_TIMEOUT_MS,
) {
    private data class PendingAction(
        val request: CrewActionRequest,
        val submittedAtMonotonicMs: Long,
    )

    private val pending = LinkedHashMap<DurableEventId, PendingAction>()

    init {
        require(maxPendingActions in 1..256) { "Crew pending action limit is invalid" }
        require(pendingTimeoutMs in 1_000..60_000) { "Crew pending action timeout is invalid" }
    }

    @Synchronized
    fun submit(
        request: CrewActionRequest,
        submittedAtMonotonicMs: Long,
    ): CrewOptimisticSubmitResult {
        require(submittedAtMonotonicMs >= 0) {
            "Crew optimistic submit timestamp cannot be negative"
        }
        pending[request.id]?.let {
            return CrewOptimisticSubmitResult.AlreadyPending(it.request)
        }
        if (pending.size >= maxPendingActions) {
            return CrewOptimisticSubmitResult.CapacityReached
        }
        pending[request.id] = PendingAction(request, submittedAtMonotonicMs)
        return CrewOptimisticSubmitResult.Pending(request)
    }

    @Synchronized
    fun reconcile(event: DurableCrewEvent): CrewOptimisticReconcileResult {
        val pendingAction =
            pending.remove(event.id) ?: return CrewOptimisticReconcileResult.NotPending
        return if (
            pendingAction.request.issuingMemberId == event.issuingMemberId &&
                pendingAction.request.action == event.action
        ) {
            CrewOptimisticReconcileResult.Accepted(pendingAction.request, event)
        } else {
            CrewOptimisticReconcileResult.Conflict(pendingAction.request, event)
        }
    }

    @Synchronized
    fun reject(
        requestId: DurableEventId,
        reason: CrewOptimisticRejectionReason,
    ): CrewOptimisticRejection? =
        pending.remove(requestId)?.let { CrewOptimisticRejection(it.request, reason) }

    @Synchronized
    fun expire(nowMonotonicMs: Long): List<CrewOptimisticRejection> {
        require(nowMonotonicMs >= 0) {
            "Crew optimistic expiry timestamp cannot be negative"
        }
        val expired =
            pending.values
                .filter {
                    nowMonotonicMs >= it.submittedAtMonotonicMs &&
                        nowMonotonicMs - it.submittedAtMonotonicMs >= pendingTimeoutMs
                }
                .map { CrewOptimisticRejection(it.request, CrewOptimisticRejectionReason.TIMED_OUT) }
        expired.forEach { pending.remove(it.request.id) }
        return expired
    }

    @Synchronized
    fun clearForSessionChange(): List<CrewOptimisticRejection> {
        val rejected =
            pending.values.map {
                CrewOptimisticRejection(
                    it.request,
                    CrewOptimisticRejectionReason.SESSION_CHANGED,
                )
            }
        pending.clear()
        return rejected
    }

    @Synchronized
    fun pendingRequests(): List<CrewActionRequest> =
        pending.values.map(PendingAction::request)
}
