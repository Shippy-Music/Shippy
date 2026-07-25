package org.oxycblt.auxio.shippy.crew.reaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewReactionCodecTest {
    private val version = ProtocolVersion(1)
    private val event = CrewReactionEvent(CrewSessionId("session", version), CrewMemberId("member", version), CrewReactionId("id"), "👍")

    @Test fun `round trips bounded reaction`() {
        val decoded = CrewReactionCodec.decode(CrewReactionCodec.encode(event))
        assertEquals(event, (decoded as CrewReactionDecodeResult.Decoded).event)
    }

    @Test fun `rejects corrupt and oversized payloads`() {
        assertTrue(CrewReactionCodec.decode(byteArrayOf(99)) is CrewReactionDecodeResult.Rejected)
        assertTrue(CrewReactionCodec.decode(ByteArray(CrewReactionCodec.MAX_PAYLOAD_BYTES + 1)) is CrewReactionDecodeResult.Rejected)
        val malformedUtf8 = CrewReactionCodec.encode(event).also { it[it.lastIndex] = 0xff.toByte() }
        assertTrue(CrewReactionCodec.decode(malformedUtf8) is CrewReactionDecodeResult.Rejected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoder bounds every utf8 field`() {
        CrewReactionCodec.encode(event.copy(emoji = "x".repeat(33)))
    }
}
