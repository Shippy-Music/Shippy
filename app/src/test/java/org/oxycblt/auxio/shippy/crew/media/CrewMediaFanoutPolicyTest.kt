/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaFanoutPolicyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.QueueItemId

class CrewMediaFanoutPolicyTest {
    @Test
    fun `allows two supplier uploads but no third`() {
        val policy = CrewMediaFanoutPolicy()

        assertEquals(
            CrewMediaFanoutPolicy.Result.Acquired,
            policy.acquire(transfer("one", "target-one")),
        )
        assertEquals(
            CrewMediaFanoutPolicy.Result.Acquired,
            policy.acquire(transfer("two", "target-two")),
        )
        assertEquals(
            CrewMediaFanoutPolicy.Result.RetryLater,
            policy.acquire(transfer("three", "target-three")),
        )
        assertEquals(2, policy.activeCountForTest())
    }

    @Test
    fun `duplicate transfer is idempotent and target cannot receive two uploads`() {
        val policy = CrewMediaFanoutPolicy()
        val first = transfer("one", "target")

        assertEquals(CrewMediaFanoutPolicy.Result.Acquired, policy.acquire(first))
        assertEquals(CrewMediaFanoutPolicy.Result.Duplicate, policy.acquire(first))
        assertEquals(
            CrewMediaFanoutPolicy.Result.RetryLater,
            policy.acquire(transfer("two", "target")),
        )
        assertEquals(1, policy.activeCountForTest())
    }

    @Test
    fun `release and detached target free permits immediately`() {
        val policy = CrewMediaFanoutPolicy()
        val first = transfer("one", "target-one")
        val second = transfer("two", "target-two")

        policy.acquire(first)
        policy.acquire(second)
        policy.release(first)
        assertEquals(
            CrewMediaFanoutPolicy.Result.Acquired,
            policy.acquire(transfer("three", "target-three")),
        )
        policy.releaseForTarget(member("target-two"))
        assertEquals(
            CrewMediaFanoutPolicy.Result.Acquired,
            policy.acquire(transfer("four", "target-four")),
        )
    }

    @Test
    fun `temporary media contract is eight mebibytes`() {
        assertEquals(8L * 1024L * 1024L, CREW_MEDIA_MAX_OBJECT_BYTES)
    }

    private fun transfer(request: String, target: String) =
        CrewMediaTransferRef(
            sessionId = CrewSessionId("crew", ProtocolVersion(1)),
            requestId = CrewMediaRequestId(request),
            queueItemId = QueueItemId("queue-$request"),
            candidateId = CandidateId("candidate-$request"),
            targetMemberId = member(target),
            supplierMemberId = member("supplier"),
        )

    private fun member(value: String) = CrewMemberId(value, ProtocolVersion(1))
}
