/*
 * Copyright (c) 2026 Auxio Project
 * SourceSelectionPolicy.kt is part of Auxio.
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
package app.shippy.core.source

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.SourceReferenceId

enum class ResolutionCandidateKind {
    CREW_REQUIRED_ASSET,
    PERMANENT_DOWNLOAD,
    LINKED_LOCAL_ASSET,
    COMPLETE_CACHE,
    PROVIDER,
    CREW_PEER,
}

data class PlaybackSourceCandidate(
    val stableKey: String,
    val kind: ResolutionCandidateKind,
    val sourceReferenceId: SourceReferenceId?,
    val mediaAssetId: MediaAssetId?,
    val providerId: ProviderId?,
    val availability: AvailabilityState,
    val identityVerified: Boolean,
    val assetVerified: Boolean,
    val enabled: Boolean,
    val meteredAllowed: Boolean,
    val userPreferred: Boolean,
    val lossless: Boolean,
    val providerPriority: Int,
    val startupCost: Int,
) {
    init {
        require(stableKey.isNotBlank()) { "Source candidate key cannot be blank" }
        require(providerPriority >= 0) { "Provider priority cannot be negative" }
        require(startupCost >= 0) { "Startup cost cannot be negative" }
    }
}

sealed interface SourceSelectionResult {
    data class Selected(val candidate: PlaybackSourceCandidate) : SourceSelectionResult

    data object Unavailable : SourceSelectionResult
}

class SourceSelectionPolicy {
    fun select(
        candidates: Collection<PlaybackSourceCandidate>,
        onMeteredNetwork: Boolean,
    ): SourceSelectionResult {
        val eligible = candidates.filter { it.isEligible(onMeteredNetwork) }
        if (eligible.isEmpty()) return SourceSelectionResult.Unavailable
        val crewRequired = eligible.any { it.kind == ResolutionCandidateKind.CREW_REQUIRED_ASSET }
        val selected =
            eligible.minWith(
                compareBy<PlaybackSourceCandidate> { it.rank(crewRequired) }
                    .thenByDescending { it.lossless }
                    .thenBy { it.startupCost }
                    .thenBy { it.providerPriority }
                    .thenBy { it.stableKey }
            )
        return SourceSelectionResult.Selected(selected)
    }

    private fun PlaybackSourceCandidate.isEligible(onMeteredNetwork: Boolean): Boolean {
        if (!enabled || (onMeteredNetwork && !meteredAllowed) || !identityVerified) return false
        if (
            availability != AvailabilityState.AVAILABLE &&
                availability != AvailabilityState.RESOLVABLE
        ) {
            return false
        }
        return when (kind) {
            ResolutionCandidateKind.CREW_REQUIRED_ASSET,
            ResolutionCandidateKind.PERMANENT_DOWNLOAD,
            ResolutionCandidateKind.LINKED_LOCAL_ASSET,
            ResolutionCandidateKind.COMPLETE_CACHE -> assetVerified
            ResolutionCandidateKind.PROVIDER,
            ResolutionCandidateKind.CREW_PEER -> true
        }
    }

    private fun PlaybackSourceCandidate.rank(crewRequired: Boolean): Int {
        if (userPreferred) return 0
        if (crewRequired && kind == ResolutionCandidateKind.CREW_REQUIRED_ASSET) return 1
        return when (kind) {
            ResolutionCandidateKind.CREW_REQUIRED_ASSET -> 2
            ResolutionCandidateKind.PERMANENT_DOWNLOAD -> 3
            ResolutionCandidateKind.LINKED_LOCAL_ASSET -> 4
            ResolutionCandidateKind.COMPLETE_CACHE -> 5
            ResolutionCandidateKind.PROVIDER -> 6
            ResolutionCandidateKind.CREW_PEER -> 7
        }
    }
}
