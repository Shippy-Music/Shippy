/*
 * Copyright (c) 2026 Shippy contributors
 * CrewInviteCodecTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewInviteCodecTest {
    @Test
    fun `versioned invite round trips as a URL safe deep link`() {
        val invite = invite(relayLocator = CrewRelayLocator("https://relay.shippy.example/crew"))
        val codec = CrewInviteCodec(setOf(ProtocolVersion(1)))

        val encoded = codec.encode(invite)
        val decoded = codec.decode(encoded, nowEpochMs = 1_000)

        assertEquals(CrewInviteDecodeResult.Accepted(invite), decoded)
        assertFalse(encoded.contains('='))
        assertFalse(encoded.contains(invite.secret.value))
        assertFalse(invite.toString().contains(invite.secret.value))
    }

    @Test
    fun `payload corruption is rejected before protocol processing`() {
        val codec = CrewInviteCodec(setOf(ProtocolVersion(1)))
        val encoded = codec.encode(invite())
        val tamperedIndex = encoded.length / 2
        val replacement = if (encoded[tamperedIndex] == 'A') 'B' else 'A'
        val tampered =
            encoded.substring(0, tamperedIndex) + replacement + encoded.substring(tamperedIndex + 1)

        assertEquals(CrewInviteDecodeResult.Rejected.CORRUPTED, codec.decode(tampered, 1_000))
    }

    @Test
    fun `malformed scheme and payload are rejected`() {
        val codec = CrewInviteCodec(setOf(ProtocolVersion(1)))

        assertEquals(CrewInviteDecodeResult.Rejected.MALFORMED, codec.decode("https://crew/invite/x", 1_000))
        assertEquals(CrewInviteDecodeResult.Rejected.MALFORMED, codec.decode("shippy://crew/invite/A", 1_000))
    }

    @Test
    fun `expired invite is rejected at its exact expiry`() {
        val codec = CrewInviteCodec(setOf(ProtocolVersion(1)))
        val encoded = codec.encode(invite(expiresAtEpochMs = 5_000))

        assertEquals(CrewInviteDecodeResult.Rejected.EXPIRED, codec.decode(encoded, 5_000))
    }

    @Test
    fun `invite beyond the configured short lifetime is rejected`() {
        val codec =
            CrewInviteCodec(
                supportedProtocolVersions = setOf(ProtocolVersion(1)),
                maxInviteLifetimeMs = 60_000,
            )
        val encoded = codec.encode(invite(issuedAtEpochMs = 1_000, expiresAtEpochMs = 61_001))

        assertEquals(
            CrewInviteDecodeResult.Rejected.INVALID_LIFETIME,
            codec.decode(encoded, nowEpochMs = 1_000),
        )
    }

    @Test
    fun `invite issued beyond the configured clock skew is rejected`() {
        val codec =
            CrewInviteCodec(
                supportedProtocolVersions = setOf(ProtocolVersion(1)),
                clockSkewToleranceMs = 1_000,
            )
        val encoded =
            codec.encode(invite(issuedAtEpochMs = 2_001, expiresAtEpochMs = 10_000))

        assertEquals(
            CrewInviteDecodeResult.Rejected.INVALID_LIFETIME,
            codec.decode(encoded, nowEpochMs = 1_000),
        )
    }

    @Test
    fun `protocol version mismatch is rejected`() {
        val versionTwo = ProtocolVersion(2)
        val encoded = CrewInviteCodec(setOf(versionTwo)).encode(invite(protocolVersion = versionTwo))

        assertEquals(
            CrewInviteDecodeResult.Rejected.UNSUPPORTED_PROTOCOL,
            CrewInviteCodec(setOf(ProtocolVersion(1))).decode(encoded, 1_000),
        )
    }

    @Test
    fun `relay locator refuses a raw device address`() {
        assertInvalid { CrewRelayLocator("https://192.168.1.25:8443/crew") }
        assertInvalid { CrewRelayLocator("https://localhost/crew") }
        assertInvalid { CrewRelayLocator("https://shippy.local/crew") }
        assertInvalid { CrewRelayLocator("http://relay.shippy.example/crew") }
    }

    @Test
    fun `invite secret requires at least 128 bits of base64url capacity`() {
        assertInvalid { CrewInviteSecret("too_short") }
    }

    private fun invite(
        protocolVersion: ProtocolVersion = ProtocolVersion(1),
        issuedAtEpochMs: Long = 0,
        expiresAtEpochMs: Long = 10_000,
        relayLocator: CrewRelayLocator? = null,
    ) =
        CrewInvite(
            protocolVersion = protocolVersion,
            sessionLocator = CrewSessionLocator("session_LANsafe_123"),
            inviteId = CrewInviteId("invite_12345678"),
            secret = CrewInviteSecret("secret_abcdefghijklmnopqrstuvwxyz"),
            issuedAtEpochMs = issuedAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            relayLocator = relayLocator,
        )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
