/*
 * Copyright (c) 2026 Auxio Project
 * CrewLocalAvailability.kt is part of Auxio.
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
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailability
import org.oxycblt.auxio.shippy.crew.preparation.QueueItemAvailabilitySummary
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackRealm

/** Path-free, bounded local capability policy. It deliberately never resolves a provider URL. */
internal object CrewLocalAvailabilityEvaluator {
    fun evaluate(
        item: QueueItem,
        localMemberId: CrewMemberId,
        temporary: Set<CrewLocalMediaKey>,
        downloads: Set<CrewActiveMediaSelector.DownloadKey>,
        enabledProviders: List<ProviderId>,
        otherMemberAvailability: Collection<CrewAvailability>,
    ): CrewAvailability {
        if (item.track.candidates.any { CrewLocalMediaKey(item.id, it.id) in temporary }) {
            return CrewAvailability.TEMPORARY_CACHE
        }
        val ownsExactLocal =
            item.track.realm == TrackRealm.LOCAL &&
                item.contributorId == localMemberId.value &&
                item.track.candidates.any(::isAvailableLocal)
        if (ownsExactLocal) return CrewAvailability.LOCAL_EXACT
        if (
            item.track.candidates.any {
                CrewActiveMediaSelector.DownloadKey(item.track.id, it.id) in downloads
            }
        ) {
            return CrewAvailability.DOWNLOAD
        }
        val providers =
            item.track.candidates.filter {
                it.kind == CandidateKind.PROVIDER &&
                    it.availability != CandidateAvailability.UNAVAILABLE
            }
        enabledProviders
            .firstOrNull { id -> providers.any { it.providerId == id } }
            ?.let { selected ->
                return if (selected == enabledProviders.firstOrNull()) {
                    CrewAvailability.PREFERRED_PROVIDER
                } else {
                    CrewAvailability.FALLBACK_PROVIDER
                }
            }
        return if (otherMemberAvailability.any { it.isEligibleSupplier }) CrewAvailability.PEER_ONLY
        else CrewAvailability.UNAVAILABLE
    }

    /** Canonical transfer identity; locators are intentionally not examined or returned. */
    fun candidateId(item: QueueItem, enabledProviders: List<ProviderId>): CandidateId? =
        item.track.candidates
            .takeIf { item.track.realm == TrackRealm.LOCAL }
            ?.firstOrNull(::isAvailableLocal)
            ?.id
            ?: item.track.candidates
                .firstOrNull {
                    it.kind == CandidateKind.PROVIDER &&
                        it.providerId in enabledProviders &&
                        it.availability != CandidateAvailability.UNAVAILABLE
                }
                ?.id
            ?: item.track.candidates
                .firstOrNull { it.availability != CandidateAvailability.UNAVAILABLE }
                ?.id

    private fun isAvailableLocal(candidate: TrackCandidate) =
        candidate.kind == CandidateKind.LOCAL &&
            candidate.availability == CandidateAvailability.AVAILABLE &&
            candidate.locator?.startsWith("content:", ignoreCase = true) == true &&
            candidate.media?.contentLength?.let { it in 1..CREW_MEDIA_MAX_OBJECT_BYTES } != false
}

/** Removes suppliers that this runtime cannot currently reach without changing shared truth. */
internal fun reachableCrewAvailability(
    summaries: Map<QueueItemId, QueueItemAvailabilitySummary>,
    localMemberId: CrewMemberId,
    attachedPeers: Set<CrewMemberId>,
    coolingSuppliers: Set<CrewMemberId>,
): Map<QueueItemId, QueueItemAvailabilitySummary> =
    summaries.mapValues { (_, summary) ->
        summary.copy(
            members =
                summary.members.filter {
                    it.memberId == localMemberId ||
                        (it.memberId in attachedPeers && it.memberId !in coolingSuppliers)
                }
        )
    }
