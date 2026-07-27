/*
 * Copyright (c) 2026 Auxio Project
 * CrewLiveness.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.session

import org.oxycblt.auxio.shippy.crew.core.CrewElectionCheckpoint
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint

data class CrewReconnectPolicy(
    val memberGraceMs: Long = 30_000,
    val coordinatorGraceMs: Long = 15_000,
) {
    init {
        require(memberGraceMs in 5_000..120_000) { "Crew member reconnect grace is invalid" }
        require(coordinatorGraceMs in 5_000..120_000) {
            "Crew coordinator reconnect grace is invalid"
        }
    }
}

sealed interface CrewLivenessDecision {
    data class AwaitingReconnect(val memberId: CrewMemberId, val remainingMs: Long) :
        CrewLivenessDecision

    data class MemberRemovalEligible(val memberId: CrewMemberId) : CrewLivenessDecision

    data class ElectionEligible(
        val checkpoint: CrewElectionCheckpoint,
        val candidateMemberId: CrewMemberId,
        val connectedVoterIds: List<CrewMemberId>,
    ) : CrewLivenessDecision

    data class CoordinatorUnavailableWithoutQuorum(val coordinatorMemberId: CrewMemberId) :
        CrewLivenessDecision
}

/**
 * Monotonic reconnect/election policy with no network or coroutine dependency.
 *
 * A transport loss does not immediately mutate membership. The current coordinator may remove an
 * expired ordinary member. After coordinator loss, only a strict majority of the full checkpoint
 * can begin an ungraceful election; a two-member Crew therefore waits safely.
 */
class CrewLivenessTracker(
    initialState: CrewState,
    private val localMemberId: CrewMemberId,
    private val policy: CrewReconnectPolicy = CrewReconnectPolicy(),
) {
    private data class Presence(var connected: Boolean, var disconnectedAtMonotonicMs: Long?)

    private val presence =
        initialState.members
            .associateBy(CrewMember::id) {
                Presence(connected = it.id == localMemberId, disconnectedAtMonotonicMs = null)
            }
            .toMutableMap()

    init {
        require(initialState.members.any { it.id == localMemberId }) {
            "Crew liveness local member must be active"
        }
    }

    @Synchronized
    fun connected(memberId: CrewMemberId, nowMonotonicMs: Long) {
        require(nowMonotonicMs >= 0) { "Crew liveness time cannot be negative" }
        presence
            .getOrPut(memberId) { Presence(false, null) }
            .apply {
                connected = true
                disconnectedAtMonotonicMs = null
            }
    }

    @Synchronized
    fun disconnected(memberId: CrewMemberId, nowMonotonicMs: Long) {
        require(nowMonotonicMs >= 0) { "Crew liveness time cannot be negative" }
        if (memberId == localMemberId) return
        presence
            .getOrPut(memberId) { Presence(false, nowMonotonicMs) }
            .apply {
                if (connected || disconnectedAtMonotonicMs == null) {
                    disconnectedAtMonotonicMs = nowMonotonicMs
                }
                connected = false
            }
    }

    @Synchronized
    fun reconcile(state: CrewState) {
        val active = state.members.map(CrewMember::id).toSet()
        presence.keys.retainAll(active)
        active.forEach { memberId ->
            presence.putIfAbsent(memberId, Presence(memberId == localMemberId, null))
        }
    }

    @Synchronized
    fun evaluate(state: CrewState, nowMonotonicMs: Long): List<CrewLivenessDecision> {
        require(nowMonotonicMs >= 0) { "Crew liveness time cannot be negative" }
        reconcile(state)
        val coordinatorPresence = presence[state.coordinatorMemberId]
        val coordinatorDisconnectedAt = coordinatorPresence?.disconnectedAtMonotonicMs
        if (
            state.coordinatorMemberId != localMemberId &&
                coordinatorPresence?.connected == false &&
                coordinatorDisconnectedAt != null
        ) {
            val elapsed = elapsedSince(nowMonotonicMs, coordinatorDisconnectedAt)
            if (elapsed < policy.coordinatorGraceMs) {
                return listOf(
                    CrewLivenessDecision.AwaitingReconnect(
                        state.coordinatorMemberId,
                        policy.coordinatorGraceMs - elapsed,
                    )
                )
            }
            val connectedVoters =
                state.members
                    .map(CrewMember::id)
                    .filter { it != state.coordinatorMemberId && presence[it]?.connected == true }
                    .sortedBy(CrewMemberId::value)
            return if (connectedVoters.size > state.members.size / 2) {
                listOf(
                    CrewLivenessDecision.ElectionEligible(
                        checkpoint = state.toElectionCheckpoint(),
                        candidateMemberId = connectedVoters.first(),
                        connectedVoterIds = connectedVoters,
                    )
                )
            } else {
                listOf(
                    CrewLivenessDecision.CoordinatorUnavailableWithoutQuorum(
                        state.coordinatorMemberId
                    )
                )
            }
        }

        return state.members
            .asSequence()
            .map(CrewMember::id)
            .filter { it != localMemberId && it != state.coordinatorMemberId }
            .mapNotNull { memberId ->
                val memberPresence = presence[memberId] ?: return@mapNotNull null
                if (memberPresence.connected) return@mapNotNull null
                val disconnectedAt =
                    memberPresence.disconnectedAtMonotonicMs ?: return@mapNotNull null
                val elapsed = elapsedSince(nowMonotonicMs, disconnectedAt)
                if (elapsed < policy.memberGraceMs) {
                    CrewLivenessDecision.AwaitingReconnect(memberId, policy.memberGraceMs - elapsed)
                } else if (state.coordinatorMemberId == localMemberId) {
                    CrewLivenessDecision.MemberRemovalEligible(memberId)
                } else {
                    null
                }
            }
            .toList()
    }

    private fun elapsedSince(nowMonotonicMs: Long, thenMonotonicMs: Long): Long =
        if (nowMonotonicMs >= thenMonotonicMs) nowMonotonicMs - thenMonotonicMs else 0
}
