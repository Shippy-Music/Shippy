/*
 * Copyright (c) 2026 Auxio Project
 * CrewReactionCodecTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.reaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewReactionCodecTest {
    private val version = ProtocolVersion(1)
    private val event =
        CrewReactionEvent(
            CrewSessionId("session", version),
            CrewMemberId("member", version),
            CrewReactionId("id"),
            "👍",
        )

    @Test
    fun `round trips bounded reaction`() {
        val decoded = CrewReactionCodec.decode(CrewReactionCodec.encode(event))
        assertEquals(event, (decoded as CrewReactionDecodeResult.Decoded).event)
    }

    @Test
    fun `rejects corrupt and oversized payloads`() {
        assertTrue(CrewReactionCodec.decode(byteArrayOf(99)) is CrewReactionDecodeResult.Rejected)
        assertTrue(
            CrewReactionCodec.decode(ByteArray(CrewReactionCodec.MAX_PAYLOAD_BYTES + 1))
                is CrewReactionDecodeResult.Rejected
        )
        val malformedUtf8 =
            CrewReactionCodec.encode(event).also { it[it.lastIndex] = 0xff.toByte() }
        assertTrue(CrewReactionCodec.decode(malformedUtf8) is CrewReactionDecodeResult.Rejected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoder bounds every utf8 field`() {
        CrewReactionCodec.encode(event.copy(emoji = "x".repeat(33)))
    }
}
