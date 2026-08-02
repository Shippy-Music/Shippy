/*
 * Copyright (c) 2026 Auxio Project
 * CrewClockProtocolTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewClockProtocolTest {
    @Test
    fun `request and response round trip exactly`() {
        val request = CrewClockFrame.Request(7, 1_000)
        val response = CrewClockFrame.Response(7, 1_000, 1_020, 1_021)

        assertEquals(request, CrewClockCodec.decode(CrewClockCodec.encode(request)))
        assertEquals(response, CrewClockCodec.decode(CrewClockCodec.encode(response)))
    }

    @Test
    fun `malformed clock frames are rejected`() {
        assertTrue(runCatching { CrewClockCodec.decode(byteArrayOf(1, 2, 3)) }.isFailure)
        val unknown = CrewClockCodec.encode(CrewClockFrame.Request(1, 2)).also { it[1] = 99 }
        assertTrue(runCatching { CrewClockCodec.decode(unknown) }.isFailure)
    }

    @Test
    fun `clock estimate converts in both directions`() {
        val estimate = CrewClockEstimate(250.0, 5, 3)
        assertEquals(1_250, estimate.clientToCoordinator(1_000))
        assertEquals(1_000, estimate.coordinatorToClient(1_250))
    }
}
