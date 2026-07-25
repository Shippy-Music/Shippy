/*
 * Copyright (c) 2026 Shippy contributors
 * CrewHostBootstrapTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteCodec
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteDecodeResult
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator

class CrewHostBootstrapTest {
    private val protocol = ProtocolVersion(7)
    private val localMember = CrewMember(CrewMemberId("host", protocol), "Host")

    @Test
    fun `creates exact canonical state and a decodable short lived link`() {
        val source = RecordingTokens("session_token_123", "invite_token_1234", "a".repeat(43))
        val relay = CrewRelayLocator("https://relay.shippy.example/crew")

        val result = CrewHostBootstrapFactory(tokenSource = source).create(localMember, 1_000, relay)

        assertEquals("session_token_123", result.initialState.sessionId.value)
        assertEquals(protocol, result.initialState.protocolVersion)
        assertEquals(CoordinatorTerm(1), result.initialState.term)
        assertEquals(EventSequence(0), result.initialState.lastSequence)
        assertEquals(localMember.id, result.initialState.coordinatorMemberId)
        assertEquals(listOf(localMember), result.initialState.members)
        assertTrue(result.initialState.queue.isEmpty())
        assertEquals(CrewPlaybackMode.IDLE, result.initialState.playback.mode)
        assertEquals("session_token_123", result.invite.sessionLocator.value)
        assertEquals("invite_token_1234", result.invite.inviteId.value)
        assertEquals(1_000, result.invite.issuedAtEpochMs)
        assertEquals(901_000, result.invite.expiresAtEpochMs)
        assertEquals(relay, result.invite.relayLocator)
        assertEquals(
            CrewInviteDecodeResult.Accepted(result.invite),
            CrewInviteCodec(setOf(protocol)).decode(result.inviteLink, 1_000),
        )
        assertFalse(result.inviteLink.contains(result.invite.secret.value))
    }

    @Test
    fun `requests separate tokens for session invite and secret`() {
        val source = RecordingTokens("session_token_123", "invite_token_1234", "b".repeat(43))

        CrewHostBootstrapFactory(tokenSource = source).create(localMember, 0)

        assertEquals(
            listOf(
                CrewHostTokenPurpose.SESSION to 16,
                CrewHostTokenPurpose.INVITE to 16,
                CrewHostTokenPurpose.SECRET to 32,
            ),
            source.requests,
        )
    }

    @Test
    fun `rejects lifetime outside the positive one day bound`() {
        assertInvalid { CrewHostBootstrapFactory(inviteLifetimeMs = 0) }
        assertInvalid { CrewHostBootstrapFactory(inviteLifetimeMs = 24 * 60 * 60 * 1000L + 1) }
    }

    @Test
    fun `rejects negative bootstrap time`() {
        assertInvalid {
            CrewHostBootstrapFactory(tokenSource = RecordingTokens("session_token_123", "invite_token_1234", "c".repeat(43)))
                .create(localMember, -1)
        }
        assertInvalid {
            CrewHostBootstrapFactory(tokenSource = RecordingTokens("session_token_123", "invite_token_1234", "c".repeat(43)))
                .create(localMember, Long.MAX_VALUE)
        }
    }

    @Test
    fun `rejects invalid or insufficiently strong source tokens without fallback`() {
        assertInvalid {
            CrewHostBootstrapFactory(tokenSource = RecordingTokens("bad token", "invite_token_1234", "d".repeat(43)))
                .create(localMember, 0)
        }
        assertInvalid {
            CrewHostBootstrapFactory(tokenSource = RecordingTokens("session_token_123", "invite_token_1234", "e".repeat(42)))
                .create(localMember, 0)
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private class RecordingTokens(vararg tokens: String) : CrewOpaqueTokenSource {
        private val values = tokens.iterator()
        val requests = mutableListOf<Pair<CrewHostTokenPurpose, Int>>()

        override fun nextToken(purpose: CrewHostTokenPurpose, byteCount: Int): String {
            requests += purpose to byteCount
            return values.next()
        }
    }
}
