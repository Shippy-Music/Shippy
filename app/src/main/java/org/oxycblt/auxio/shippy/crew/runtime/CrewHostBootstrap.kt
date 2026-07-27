/*
 * Copyright (c) 2026 Auxio Project
 * CrewHostBootstrap.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import java.security.SecureRandom
import java.util.Base64
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteCodec
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

private const val DEFAULT_INVITE_LIFETIME_MS = 15 * 60 * 1000L
private const val MAX_INVITE_LIFETIME_MS = 24 * 60 * 60 * 1000L
private const val ID_TOKEN_BYTES = 16
private const val SECRET_TOKEN_BYTES = 32
private const val MIN_256_BIT_BASE64_URL_LENGTH = 43

/** Distinct bootstrap identities are requested separately so a test can prove their provenance. */
enum class CrewHostTokenPurpose {
    SESSION,
    INVITE,
    SECRET,
}

/**
 * Supplies URL-safe, unpadded opaque tokens. It must not derive them from device or user identity.
 */
fun interface CrewOpaqueTokenSource {
    fun nextToken(purpose: CrewHostTokenPurpose, byteCount: Int): String
}

object SecureCrewOpaqueTokenSource : CrewOpaqueTokenSource {
    private val random = SecureRandom()

    override fun nextToken(purpose: CrewHostTokenPurpose, byteCount: Int): String {
        require(byteCount > 0) { "Token byte count must be positive" }
        return ByteArray(byteCount)
            .also(random::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
    }
}

data class CrewHostBootstrap(
    val initialState: CrewState,
    val invite: CrewInvite,
    val inviteLink: String,
)

/**
 * Creates only the pure, canonical host state and invitation. Transport, QR rendering, and UI
 * construction remain outside this boundary.
 */
class CrewHostBootstrapFactory(
    private val inviteLifetimeMs: Long = DEFAULT_INVITE_LIFETIME_MS,
    private val tokenSource: CrewOpaqueTokenSource = SecureCrewOpaqueTokenSource,
) {
    init {
        require(inviteLifetimeMs in 1..MAX_INVITE_LIFETIME_MS) {
            "Invite lifetime must be positive and no more than 24 hours"
        }
    }

    fun create(
        localMember: CrewMember,
        nowEpochMs: Long,
        relayLocator: CrewRelayLocator? = null,
    ): CrewHostBootstrap {
        require(nowEpochMs >= 0) { "Host bootstrap time cannot be negative" }
        require(nowEpochMs <= Long.MAX_VALUE - inviteLifetimeMs) {
            "Host bootstrap expiry exceeds the supported epoch range"
        }

        val protocolVersion = localMember.id.protocolVersion
        val sessionToken = tokenSource.nextToken(CrewHostTokenPurpose.SESSION, ID_TOKEN_BYTES)
        val inviteToken = tokenSource.nextToken(CrewHostTokenPurpose.INVITE, ID_TOKEN_BYTES)
        val secretToken = tokenSource.nextToken(CrewHostTokenPurpose.SECRET, SECRET_TOKEN_BYTES)
        require(setOf(sessionToken, inviteToken, secretToken).size == 3) {
            "Host bootstrap tokens must be distinct"
        }

        val sessionId = CrewSessionId(sessionToken, protocolVersion)
        val invite =
            CrewInvite(
                protocolVersion = protocolVersion,
                sessionLocator = CrewSessionLocator(sessionToken),
                inviteId = CrewInviteId(inviteToken),
                secret =
                    CrewInviteSecret(secretToken).also {
                        require(secretToken.length >= MIN_256_BIT_BASE64_URL_LENGTH) {
                            "Invite secret must have at least 256-bit base64url capacity"
                        }
                    },
                issuedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + inviteLifetimeMs,
                relayLocator = relayLocator,
            )
        val state =
            CrewState(
                sessionId = sessionId,
                protocolVersion = protocolVersion,
                term = CoordinatorTerm(1),
                lastSequence = EventSequence(0),
                coordinatorMemberId = localMember.id,
                members = listOf(localMember),
            )
        val codec = CrewInviteCodec(setOf(protocolVersion), maxInviteLifetimeMs = inviteLifetimeMs)

        return CrewHostBootstrap(state, invite, codec.encode(invite))
    }
}
