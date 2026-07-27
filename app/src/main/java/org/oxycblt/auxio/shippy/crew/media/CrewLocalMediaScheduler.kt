/*
 * Copyright (c) 2026 Auxio Project
 * CrewLocalMediaScheduler.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.media

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItemId

data class CrewLocalMediaKey(val queueItemId: QueueItemId, val candidateId: CandidateId)

data class CrewLocalMediaDesired(val key: CrewLocalMediaKey, val supplier: CrewMemberId)

data class CrewLocalMediaPlan(val desired: List<CrewLocalMediaDesired>, val blockedCurrent: Boolean)

/** Exact-local, coordinator-centred planning only; no provider or availability inference. */
fun planLocalMedia(
    state: CrewState,
    localMemberId: CrewMemberId,
    pushPullEnabled: Boolean,
    available: Set<CrewLocalMediaKey> = emptySet(),
): CrewLocalMediaPlan {
    val current = state.playback.currentQueueItemId
    val start = state.queue.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it }
    var blocked = false
    val desired =
        state.queue.drop(start).take(3).mapNotNull { item ->
            val candidate =
                item.track.candidates.firstOrNull { it.kind == CandidateKind.LOCAL }
                    ?: return@mapNotNull null
            val key = CrewLocalMediaKey(item.id, candidate.id)
            if (key in available) return@mapNotNull null
            val contributor = item.contributorId ?: return@mapNotNull null
            val owner =
                state.members.firstOrNull { it.id.value == contributor }?.id
                    ?: return@mapNotNull null
            if (owner == localMemberId) return@mapNotNull null
            if (!pushPullEnabled) {
                if (item.id == current) blocked = true
                return@mapNotNull null
            }
            val supplier =
                if (localMemberId == state.coordinatorMemberId) owner else state.coordinatorMemberId
            CrewLocalMediaDesired(key, supplier)
        }
    return CrewLocalMediaPlan(desired, blocked)
}
