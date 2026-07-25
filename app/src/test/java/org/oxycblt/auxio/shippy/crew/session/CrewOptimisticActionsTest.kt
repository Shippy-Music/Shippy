/*
 * Copyright (c) 2026 Shippy contributors
 * CrewOptimisticActionsTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewOptimisticActionsTest {
    @Test
    fun `matching accepted event reconciles and removes pending intent`() {
        val fixture = Fixture()
        val tracker = CrewOptimisticActionTracker()
        val request = fixture.request("request", CrewAction.ShuffleChanged(true))

        assertEquals(
            CrewOptimisticSubmitResult.Pending(request),
            tracker.submit(request, 100),
        )
        val result = tracker.reconcile(fixture.event(request))

        assertTrue(result is CrewOptimisticReconcileResult.Accepted)
        assertTrue(tracker.pendingRequests().isEmpty())
    }

    @Test
    fun `same event id with a different action is a conflict`() {
        val fixture = Fixture()
        val tracker = CrewOptimisticActionTracker()
        val request = fixture.request("request", CrewAction.ShuffleChanged(true))
        tracker.submit(request, 100)

        val conflicting =
            fixture.event(
                request.copy(action = CrewAction.RepeatChanged(fixture.repeatMode)),
            )

        assertTrue(tracker.reconcile(conflicting) is CrewOptimisticReconcileResult.Conflict)
        assertTrue(tracker.pendingRequests().isEmpty())
    }

    @Test
    fun `duplicate submit preserves the original pending request`() {
        val fixture = Fixture()
        val tracker = CrewOptimisticActionTracker()
        val original = fixture.request("request", CrewAction.ShuffleChanged(true))
        val changed = fixture.request("request", CrewAction.ShuffleChanged(false))
        tracker.submit(original, 100)

        assertEquals(
            CrewOptimisticSubmitResult.AlreadyPending(original),
            tracker.submit(changed, 101),
        )
        assertEquals(listOf(original), tracker.pendingRequests())
    }

    @Test
    fun `timeout and session clear return explicit rejected intents`() {
        val fixture = Fixture()
        val tracker = CrewOptimisticActionTracker(pendingTimeoutMs = 1_000)
        val first = fixture.request("first", CrewAction.ShuffleChanged(true))
        val second = fixture.request("second", CrewAction.ShuffleChanged(false))
        tracker.submit(first, 100)
        tracker.submit(second, 500)

        assertEquals(
            listOf(CrewOptimisticRejection(first, CrewOptimisticRejectionReason.TIMED_OUT)),
            tracker.expire(1_100),
        )
        assertEquals(
            listOf(
                CrewOptimisticRejection(
                    second,
                    CrewOptimisticRejectionReason.SESSION_CHANGED,
                ),
            ),
            tracker.clearForSessionChange(),
        )
        assertTrue(tracker.pendingRequests().isEmpty())
    }

    private class Fixture {
        private val protocol = ProtocolVersion(1)
        private val sessionId = CrewSessionId("session", protocol)
        private val coordinatorId = CrewMemberId("coordinator", protocol)
        private val memberId = CrewMemberId("member", protocol)
        val repeatMode = org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode.ALL

        fun request(
            id: String,
            action: CrewAction,
        ) = CrewActionRequest(DurableEventId(id), memberId, 50, action)

        fun event(request: CrewActionRequest) =
            DurableCrewEvent(
                sessionId,
                protocol,
                CoordinatorTerm(1),
                EventSequence(1),
                request.id,
                coordinatorId,
                request.issuingMemberId,
                request.clientMonotonicTimestampMs,
                request.action,
            )
    }
}
