/*
 * Copyright (c) 2026 Shippy contributors
 * CrewReactionReducerTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.reaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewReactionReducerTest {
    private val version = ProtocolVersion(1)
    private val session = CrewSessionId("session", version)
    private val otherSession = CrewSessionId("other", version)
    private val member = CrewMemberId("member", version)
    private val otherMember = CrewMemberId("other-member", version)
    private val members = setOf(member)
    private val reducer =
        CrewReactionReducer(
            CrewReactionPolicy(
                allowedEmoji = setOf("❤️", "🔥"),
                visibleDurationMs = 2_000,
                minimumIntervalMs = 250,
                maximumActive = 2,
            )
        )

    @Test
    fun `active member reaction is accepted with receiver-local expiry`() {
        val result =
            reducer.apply(CrewReactionState(), event("one"), member, session, members, 1_000)

        assertTrue(result is CrewReactionResult.Accepted)
        val active = (result as CrewReactionResult.Accepted).state.active.single()
        assertEquals(3_000L, active.expiresAtMonotonicMs)
    }

    @Test
    fun `wrong session forged sender inactive member and unsupported emoji are rejected`() {
        assertRejected(
            CrewReactionRejection.WRONG_SESSION,
            reducer.apply(
                CrewReactionState(),
                event("one", sessionId = otherSession),
                member,
                session,
                members,
                0,
            ),
        )
        assertRejected(
            CrewReactionRejection.SENDER_MISMATCH,
            reducer.apply(
                CrewReactionState(),
                event("forged"),
                otherMember,
                session,
                members,
                0,
            ),
        )
        assertRejected(
            CrewReactionRejection.INACTIVE_MEMBER,
            reducer.apply(
                CrewReactionState(),
                event("two", memberId = otherMember),
                otherMember,
                session,
                members,
                0,
            ),
        )
        assertRejected(
            CrewReactionRejection.UNSUPPORTED_EMOJI,
            reducer.apply(
                CrewReactionState(),
                event("three", emoji = "💣"),
                member,
                session,
                members,
                0,
            ),
        )
    }

    @Test
    fun `duplicates and reaction bursts are rejected`() {
        val first =
            (reducer.apply(CrewReactionState(), event("one"), member, session, members, 1_000)
                    as CrewReactionResult.Accepted)
                .state

        assertRejected(
            CrewReactionRejection.DUPLICATE,
            reducer.apply(first, event("one"), member, session, members, 1_300),
        )
        assertRejected(
            CrewReactionRejection.RATE_LIMITED,
            reducer.apply(first, event("two"), member, session, members, 1_100),
        )
        assertTrue(
            reducer.apply(first, event("two"), member, session, members, 1_250) is
                CrewReactionResult.Accepted
        )
    }

    @Test
    fun `expiry member removal and active bound prune transient state`() {
        var state =
            (reducer.apply(CrewReactionState(), event("one"), member, session, members, 0)
                    as CrewReactionResult.Accepted)
                .state
        state =
            (reducer.apply(state, event("two"), member, session, members, 250)
                    as CrewReactionResult.Accepted)
                .state
        state =
            (reducer.apply(state, event("three"), member, session, members, 500)
                    as CrewReactionResult.Accepted)
                .state
        assertEquals(listOf("two", "three"), state.active.map { it.event.id.value })

        assertTrue(reducer.prune(state, members, 2_500).active.isEmpty())
        assertTrue(reducer.prune(state, emptySet(), 500).lastAcceptedAtByMember.isEmpty())
    }

    private fun event(
        id: String,
        sessionId: CrewSessionId = session,
        memberId: CrewMemberId = member,
        emoji: String = "❤️",
    ) =
        CrewReactionEvent(
            sessionId = sessionId,
            memberId = memberId,
            id = CrewReactionId(id),
            emoji = emoji,
        )

    private fun assertRejected(
        reason: CrewReactionRejection,
        result: CrewReactionResult,
    ) {
        assertTrue(result is CrewReactionResult.Rejected)
        assertEquals(reason, (result as CrewReactionResult.Rejected).reason)
    }
}
