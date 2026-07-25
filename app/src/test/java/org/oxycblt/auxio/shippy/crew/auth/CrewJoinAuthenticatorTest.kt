/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinAuthenticatorTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

class CrewJoinAuthenticatorTest {
    @Test
    fun `both peers authenticate one transcript and bind the opposite member`() {
        val invite = invite()
        val sessionId = CrewSessionId("canonical-session", version)
        val initiatorMember = member("initiator")
        val responderMember = member("responder")
        val initiator =
            CrewJoinInitiator(
                invite,
                initiatorMember,
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(3),
            )
        val responder =
            CrewJoinResponder(
                invite,
                sessionId,
                responderMember,
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(4),
            )

        val hello = roundTrip(initiator.start()) as CrewJoinMessage.InitiatorHello
        val challengeResult = responder.accept(hello) as CrewResponderHelloResult.Accepted
        val challenge =
            roundTrip(challengeResult.challenge) as CrewJoinMessage.ResponderChallenge
        val challengeAccepted =
            initiator.accept(challenge) as CrewInitiatorChallengeResult.Accepted
        val response = roundTrip(challengeAccepted.response) as CrewJoinMessage.InitiatorProof
        val responderResult =
            responder.complete(response) as CrewResponderProofResult.Authenticated
        val finished =
            roundTrip(responderResult.response) as CrewJoinMessage.ResponderFinished
        val initiatorResult =
            initiator.complete(finished) as CrewInitiatorFinishedResult.Authenticated

        assertEquals(sessionId, initiatorResult.binding.sessionId)
        assertEquals(responderMember, initiatorResult.binding.remoteMemberId)
        assertEquals(sessionId, responderResult.binding.sessionId)
        assertEquals(initiatorMember, responderResult.binding.remoteMemberId)
        assertFalse(initiatorResult.binding.toString().contains(invite.secret.value))
    }

    @Test
    fun `wrong invitation secret cannot authenticate`() {
        val initiator =
            CrewJoinInitiator(
                invite(),
                member("initiator"),
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(3),
            )
        val responder =
            CrewJoinResponder(
                invite(secret = "different_secret_abcdefghijklmnop"),
                CrewSessionId("canonical-session", version),
                member("responder"),
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(4),
            )

        val challenge =
            (responder.accept(initiator.start()) as CrewResponderHelloResult.Accepted).challenge

        assertEquals(
            CrewInitiatorChallengeResult.Rejected(CrewJoinRejectionReason.PROOF_INVALID),
            initiator.accept(challenge),
        )
    }

    @Test
    fun `signaling fingerprint substitution invalidates the proof`() {
        val initiator =
            CrewJoinInitiator(
                invite(),
                member("initiator"),
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(3),
            )
        val responder =
            CrewJoinResponder(
                invite(),
                CrewSessionId("canonical-session", version),
                member("responder"),
                fingerprint(9),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(4),
            )

        val challenge =
            (responder.accept(initiator.start()) as CrewResponderHelloResult.Accepted).challenge

        assertEquals(
            CrewInitiatorChallengeResult.Rejected(CrewJoinRejectionReason.PROOF_INVALID),
            initiator.accept(challenge),
        )
    }

    @Test
    fun `responder rejects a different invite before proof generation`() {
        val responder =
            CrewJoinResponder(
                invite(),
                CrewSessionId("canonical-session", version),
                member("responder"),
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(4),
            )
        val hello =
            CrewJoinMessage.InitiatorHello(
                version,
                CrewSessionLocator("session_locator_123"),
                CrewInviteId("different_invite"),
                member("initiator"),
                CrewJoinNonce(ByteArray(32)),
            )

        assertEquals(
            CrewResponderHelloResult.Rejected(CrewJoinRejectionReason.INVITE_MISMATCH),
            responder.accept(hello),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `initiator hello cannot be replayed from one state machine`() {
        val initiator =
            CrewJoinInitiator(
                invite(),
                member("initiator"),
                fingerprint(1),
                fingerprint(2),
                nowEpochMs = 1_000,
                nonceSource = nonceSource(3),
            )

        initiator.start()
        initiator.start()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expired invitation cannot start a join authenticator`() {
        CrewJoinInitiator(
            invite(),
            member("initiator"),
            fingerprint(1),
            fingerprint(2),
            nowEpochMs = 10_000,
            nonceSource = nonceSource(3),
        )
    }

    @Test
    fun `codec rejects trailing and unknown format bytes`() {
        val message =
            CrewJoinMessage.InitiatorProof(CrewJoinProof(ByteArray(32)))
        val encoded = CrewJoinMessageCodec.encode(message)

        assertEquals(
            CrewJoinDecodeResult.Rejected.MALFORMED,
            CrewJoinMessageCodec.decode(encoded + byteArrayOf(1)),
        )
        assertEquals(
            CrewJoinDecodeResult.Rejected.UNSUPPORTED_FORMAT,
            CrewJoinMessageCodec.decode(encoded.copyOf().also { it[0] = 99.toByte() }),
        )
    }

    @Test
    fun `SDP parser accepts repeated identical fingerprints and rejects conflicts`() {
        val one = sdp(1)
        val sameTwice = one + one.substringAfter("v=0\r\n")
        val conflicting = one + sdp(2).substringAfter("v=0\r\n")

        assertEquals(
            CrewSdpFingerprint.fromSdp(one),
            CrewSdpFingerprint.fromSdp(sameTwice),
        )
        assertInvalid { CrewSdpFingerprint.fromSdp(conflicting) }
        assertTrue(CrewSdpFingerprint.fromSdp(one).toString().contains("redacted"))
    }

    private fun roundTrip(message: CrewJoinMessage): CrewJoinMessage {
        val result = CrewJoinMessageCodec.decode(CrewJoinMessageCodec.encode(message))
        return (result as CrewJoinDecodeResult.Accepted).message
    }

    private fun nonceSource(seed: Int) =
        CrewJoinNonceSource { CrewJoinNonce(ByteArray(32) { (seed + it).toByte() }) }

    private fun fingerprint(seed: Int) = CrewSdpFingerprint.fromSdp(sdp(seed))

    private fun sdp(seed: Int): String {
        val value =
            ByteArray(32) { (seed + it).toByte() }
                .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        return "v=0\r\na=fingerprint:sha-256 $value\r\n"
    }

    private fun invite(
        secret: String = "secret_abcdefghijklmnopqrstuvwxyz",
    ) =
        CrewInvite(
            protocolVersion = version,
            sessionLocator = CrewSessionLocator("session_locator_123"),
            inviteId = CrewInviteId("invite_12345678"),
            secret = CrewInviteSecret(secret),
            issuedAtEpochMs = 0,
            expiresAtEpochMs = 10_000,
        )

    private fun member(value: String) = CrewMemberId(value, version)

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private companion object {
        val version = ProtocolVersion(1)
    }
}
