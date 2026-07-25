/*
 * Copyright (c) 2026 Shippy contributors
 * CrewReactionReducer.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.reaction

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId

@JvmInline
value class CrewReactionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Reaction ID cannot be blank" }
    }
}

data class CrewReactionEvent(
    val sessionId: CrewSessionId,
    val memberId: CrewMemberId,
    val id: CrewReactionId,
    val emoji: String,
) {
    init {
        require(emoji.isNotBlank()) { "Reaction emoji cannot be blank" }
    }
}

data class ActiveCrewReaction(
    val event: CrewReactionEvent,
    /** Local receiver monotonic expiry, never a remote wall-clock value. */
    val expiresAtMonotonicMs: Long,
)

data class CrewReactionState(
    val active: List<ActiveCrewReaction> = emptyList(),
    val lastAcceptedAtByMember: Map<CrewMemberId, Long> = emptyMap(),
)

data class CrewReactionPolicy(
    val allowedEmoji: Set<String>,
    val visibleDurationMs: Long,
    val minimumIntervalMs: Long,
    val maximumActive: Int,
) {
    init {
        require(allowedEmoji.isNotEmpty() && allowedEmoji.none(String::isBlank)) {
            "Reaction policy needs non-blank emoji"
        }
        require(visibleDurationMs > 0) { "Reaction duration must be positive" }
        require(minimumIntervalMs >= 0) { "Reaction interval cannot be negative" }
        require(maximumActive > 0) { "Maximum active reactions must be positive" }
    }
}

sealed interface CrewReactionResult {
    data class Accepted(val state: CrewReactionState) : CrewReactionResult

    data class Rejected(
        val state: CrewReactionState,
        val reason: CrewReactionRejection,
    ) : CrewReactionResult
}

enum class CrewReactionRejection {
    WRONG_SESSION,
    SENDER_MISMATCH,
    INACTIVE_MEMBER,
    UNSUPPORTED_EMOJI,
    DUPLICATE,
    RATE_LIMITED,
}

/**
 * Pure transient reaction reducer. Reactions never enter the durable Crew event sequence and use
 * receiver-local monotonic time for expiry and rate limiting.
 */
class CrewReactionReducer(private val policy: CrewReactionPolicy) {
    fun apply(
        state: CrewReactionState,
        event: CrewReactionEvent,
        authenticatedSenderId: CrewMemberId,
        activeSessionId: CrewSessionId,
        activeMemberIds: Set<CrewMemberId>,
        nowMonotonicMs: Long,
    ): CrewReactionResult {
        require(nowMonotonicMs >= 0) { "Reaction clock cannot be negative" }
        val current = prune(state, activeMemberIds, nowMonotonicMs)
        if (event.sessionId != activeSessionId) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.WRONG_SESSION)
        }
        if (event.memberId != authenticatedSenderId) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.SENDER_MISMATCH)
        }
        if (event.memberId !in activeMemberIds) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.INACTIVE_MEMBER)
        }
        if (event.emoji !in policy.allowedEmoji) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.UNSUPPORTED_EMOJI)
        }
        if (current.active.any { it.event.id == event.id }) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.DUPLICATE)
        }
        val lastAcceptedAt = current.lastAcceptedAtByMember[event.memberId]
        if (
            lastAcceptedAt != null &&
                nowMonotonicMs - lastAcceptedAt < policy.minimumIntervalMs
        ) {
            return CrewReactionResult.Rejected(current, CrewReactionRejection.RATE_LIMITED)
        }

        val accepted =
            ActiveCrewReaction(
                event,
                Math.addExact(nowMonotonicMs, policy.visibleDurationMs),
            )
        return CrewReactionResult.Accepted(
            current.copy(
                active = (current.active + accepted).takeLast(policy.maximumActive),
                lastAcceptedAtByMember =
                    current.lastAcceptedAtByMember + (event.memberId to nowMonotonicMs),
            )
        )
    }

    fun prune(
        state: CrewReactionState,
        activeMemberIds: Set<CrewMemberId>,
        nowMonotonicMs: Long,
    ): CrewReactionState {
        require(nowMonotonicMs >= 0) { "Reaction clock cannot be negative" }
        return CrewReactionState(
            active =
                state.active.filter {
                    it.expiresAtMonotonicMs > nowMonotonicMs &&
                        it.event.memberId in activeMemberIds
                },
            lastAcceptedAtByMember =
                state.lastAcceptedAtByMember.filterKeys(activeMemberIds::contains),
        )
    }
}
