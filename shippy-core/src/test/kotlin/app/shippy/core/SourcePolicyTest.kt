/*
 * Copyright (c) 2026 Auxio Project
 * SourcePolicyTest.kt is part of Auxio.
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
package app.shippy.core

import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.ExactSourceIndex
import app.shippy.core.identitymatch.ExactSourceLink
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.PlaybackSourceCandidate
import app.shippy.core.source.ResolutionCandidateKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceSelectionPolicy
import app.shippy.core.source.SourceSelectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {
    @Test
    fun `verified download wins while unlinked local asset is rejected`() {
        val local =
            candidate("local", ResolutionCandidateKind.LINKED_LOCAL_ASSET)
                .copy(identityVerified = false)
        val provider = candidate("provider", ResolutionCandidateKind.PROVIDER)
        val download = candidate("download", ResolutionCandidateKind.PERMANENT_DOWNLOAD)

        val result = SourceSelectionPolicy().select(listOf(local, provider, download), false)

        assertEquals(download, (result as SourceSelectionResult.Selected).candidate)
    }

    @Test
    fun `metered restriction falls through without changing identity`() {
        val provider =
            candidate("preferred", ResolutionCandidateKind.PROVIDER).copy(meteredAllowed = false)
        val crewPeer = candidate("peer", ResolutionCandidateKind.CREW_PEER)

        val result = SourceSelectionPolicy().select(listOf(provider, crewPeer), true)

        assertEquals(crewPeer, (result as SourceSelectionResult.Selected).candidate)
    }

    @Test
    fun `explicit source preference wins the contextual default`() {
        val crewAsset = candidate("crew-required", ResolutionCandidateKind.CREW_REQUIRED_ASSET)
        val pinnedProvider =
            candidate("pinned-provider", ResolutionCandidateKind.PROVIDER)
                .copy(userPreferred = true)

        val result = SourceSelectionPolicy().select(listOf(crewAsset, pinnedProvider), false)

        assertEquals(pinnedProvider, (result as SourceSelectionResult.Selected).candidate)
    }

    @Test
    fun `exact source link is idempotent and rejects silent reassignment`() {
        val source = SourceKey(ProviderId("jiosaavn"), SourceItemType.RECORDING, "song-1")
        val firstId = id(1)
        val secondId = id(2)

        val added = ExactSourceIndex().link(source, firstId) as ExactSourceLink.Added
        val unchanged = added.index.link(source, firstId)
        val conflict = added.index.link(source, secondId)

        assertTrue(unchanged is ExactSourceLink.Unchanged)
        assertTrue(conflict is ExactSourceLink.Conflict)
        assertEquals(firstId, conflict.index.links[source])
    }

    private fun candidate(key: String, kind: ResolutionCandidateKind) =
        PlaybackSourceCandidate(
            stableKey = key,
            kind = kind,
            sourceReferenceId = null,
            mediaAssetId = null,
            providerId = null,
            availability = AvailabilityState.AVAILABLE,
            identityVerified = true,
            assetVerified = true,
            enabled = true,
            meteredAllowed = true,
            userPreferred = false,
            lossless = false,
            providerPriority = 0,
            startupCost = 0,
        )

    private fun id(value: Int) =
        RecordingId("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
