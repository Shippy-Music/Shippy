/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSignalingTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.signaling

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceCandidate
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescription
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescriptionType

class CrewSignalingTest {
    @Test
    fun `all signaling payloads round trip`() {
        val messages =
            listOf(
                CrewSignalMessage.SessionDescription(
                    CrewSessionDescription(
                        CrewSessionDescriptionType.OFFER,
                        2,
                        "v=0\r\na=fingerprint:sha-256 00\r\n",
                    ),
                ),
                CrewSignalMessage.IceCandidate(
                    CrewIceCandidate(2, "data", 0, "candidate:1"),
                ),
                CrewSignalMessage.IceCandidate(
                    CrewIceCandidate(2, null, 0, "candidate:2"),
                ),
                CrewSignalMessage.EndOfCandidates(2),
                CrewSignalMessage.IceRestartRequested(3),
                CrewSignalMessage.Close(CrewSignalCloseReason.NORMAL),
            )

        messages.forEach { message ->
            assertEquals(
                CrewSignalDecodeResult.Accepted(message),
                CrewSignalMessageCodec.decode(CrewSignalMessageCodec.encode(message)),
            )
        }
    }

    @Test
    fun `codec rejects trailing bytes and unsupported format`() {
        val encoded =
            CrewSignalMessageCodec.encode(
                CrewSignalMessage.Close(CrewSignalCloseReason.PROTOCOL_ERROR),
            )

        assertEquals(
            CrewSignalDecodeResult.Rejected.MALFORMED,
            CrewSignalMessageCodec.decode(encoded + byteArrayOf(1)),
        )
        assertEquals(
            CrewSignalDecodeResult.Rejected.UNSUPPORTED_FORMAT,
            CrewSignalMessageCodec.decode(
                encoded.copyOf().also { it[0] = 99.toByte() },
            ),
        )
        assertEquals(
            CrewSignalDecodeResult.Rejected.MALFORMED,
            CrewSignalMessageCodec.decode(
                encoded.copyOf().also { it[2] = 99.toByte() },
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized SDP is rejected before transport`() {
        CrewSignalMessageCodec.encode(
            CrewSignalMessage.SessionDescription(
                CrewSessionDescription(
                    CrewSessionDescriptionType.OFFER,
                    0,
                    "x".repeat(1024 * 1024 + 1),
                ),
            ),
        )
    }
}
