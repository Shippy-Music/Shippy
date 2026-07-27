/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackResolver.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

import javax.inject.Inject

data class ResolutionPolicy(val providerPriority: List<ProviderId>, val pushPullEnabled: Boolean)

sealed interface ResolutionResult {
    data class Selected(val candidate: TrackCandidate) : ResolutionResult

    data class Unavailable(val trackId: TrackId) : ResolutionResult
}

/**
 * Selects a playable candidate without performing network or filesystem I/O.
 *
 * Resolution order is the product contract. Exact local candidates participate only for tracks that
 * explicitly belong to the Local realm; they never silently replace a provider track.
 */
class PlaybackResolver @Inject constructor() {
    fun resolve(track: Track, policy: ResolutionPolicy): ResolutionResult {
        select(track, CandidateKind.CREW_TEMPORARY)?.let {
            return ResolutionResult.Selected(it)
        }

        if (track.realm == TrackRealm.LOCAL) {
            select(track, CandidateKind.LOCAL)?.let {
                return ResolutionResult.Selected(it)
            }
        }

        select(track, CandidateKind.DOWNLOAD)?.let {
            return ResolutionResult.Selected(it)
        }

        selectProvider(track, policy.providerPriority)?.let {
            return ResolutionResult.Selected(it)
        }

        if (policy.pushPullEnabled) {
            select(track, CandidateKind.CREW_PEER)?.let {
                return ResolutionResult.Selected(it)
            }
        }

        return ResolutionResult.Unavailable(track.id)
    }

    private fun select(track: Track, kind: CandidateKind): TrackCandidate? =
        track.candidates.firstOrNull { candidate -> candidate.kind == kind && candidate.isUsable() }

    private fun selectProvider(track: Track, providerPriority: List<ProviderId>): TrackCandidate? {
        if (providerPriority.isEmpty()) {
            return null
        }

        val rank = providerPriority.withIndex().associate { it.value to it.index }
        return track.candidates
            .asSequence()
            .filter { it.kind == CandidateKind.PROVIDER && it.isUsable() }
            .mapNotNull { candidate ->
                candidate.providerId?.let { rank[it] }?.let { priority -> priority to candidate }
            }
            .minByOrNull { it.first }
            ?.second
    }

    private fun TrackCandidate.isUsable() =
        availability == CandidateAvailability.AVAILABLE ||
            availability == CandidateAvailability.RESOLVABLE
}
