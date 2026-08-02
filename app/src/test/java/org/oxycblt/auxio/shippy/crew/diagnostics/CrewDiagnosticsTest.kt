/*
 * Copyright (c) 2026 Auxio Project
 * CrewDiagnosticsTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewDiagnosticsTest {
    @Test
    fun `snapshot retains bounded correlation fields without sensitive values`() {
        val recorder = CrewDiagnosticRecorder(elapsedRealtimeMs = { 123L }, maximumEvents = 2)

        recorder.begin(localMemberId = "member_0123456789", activeMemberCount = 2)
        recorder.update(
            term = 4L,
            sequence = 12L,
            route = CrewDiagnosticEvent.Route.LAN,
            readiness = CrewDiagnosticEvent.Readiness.READY,
        )
        recorder.record(
            CrewDiagnosticEvent.Kind.COMMAND_SUBMITTED,
            "play_requested",
            requestId = "request_abcdefghijkl",
            projectionVersion = 9L,
        )
        recorder.record(CrewDiagnosticEvent.Kind.PROJECTION_APPLIED, "player_projection")

        val snapshot = recorder.snapshot()

        assertEquals("23456789", snapshot.localMemberSuffix)
        assertEquals(2, snapshot.activeMemberCount)
        assertEquals(4L, snapshot.term)
        assertEquals(12L, snapshot.sequence)
        assertEquals(CrewDiagnosticEvent.Route.LAN, snapshot.route)
        assertEquals(CrewDiagnosticEvent.Readiness.READY, snapshot.readiness)
        assertEquals(2, snapshot.events.size)
        assertEquals("efghijkl", snapshot.events.first().requestIdSuffix)
        assertNull(snapshot.events.last().requestIdSuffix)
        assertTrue(snapshot.events.all { it.detail.length <= 96 })
        assertFalse(snapshot.toString().contains("member_0123456789"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recorder rejects path credential and URI-like diagnostics`() {
        val recorder = CrewDiagnosticRecorder(elapsedRealtimeMs = { 0L })

        recorder.record(CrewDiagnosticEvent.Kind.RETRYABLE_FAILURE, "content://private-track")
    }
}
