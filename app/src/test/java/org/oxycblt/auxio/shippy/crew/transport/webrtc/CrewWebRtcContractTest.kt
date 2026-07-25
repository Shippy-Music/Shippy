/*
 * Copyright (c) 2026 Shippy contributors
 * CrewWebRtcContractTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.transport.webrtc

import org.junit.Assert.assertFalse
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewWebRtcContractTest {
    @Test
    fun `verified peer binding redacts the transcript`() {
        val transcript = ByteArray(32) { it.toByte() }
        val binding =
            CrewAuthenticatedPeerBinding(
                sessionId = CrewSessionId("session", ProtocolVersion(1)),
                remoteMemberId = CrewMemberId("member", ProtocolVersion(1)),
                verifiedTranscriptHash = transcript,
            )

        assertFalse(binding.toString().contains(transcript.contentToString()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verified peer binding rejects a protocol mismatch`() {
        CrewAuthenticatedPeerBinding(
            sessionId = CrewSessionId("session", ProtocolVersion(1)),
            remoteMemberId = CrewMemberId("member", ProtocolVersion(2)),
            verifiedTranscriptHash = ByteArray(32),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verified peer binding rejects a short transcript hash`() {
        CrewAuthenticatedPeerBinding(
            sessionId = CrewSessionId("session", ProtocolVersion(1)),
            remoteMemberId = CrewMemberId("member", ProtocolVersion(1)),
            verifiedTranscriptHash = ByteArray(16),
        )
    }
}
