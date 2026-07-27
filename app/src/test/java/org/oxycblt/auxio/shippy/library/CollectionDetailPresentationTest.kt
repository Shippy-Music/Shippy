/*
 * Copyright (c) 2026 Auxio Project
 * CollectionDetailPresentationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadJob
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload

class CollectionDetailPresentationTest {
    @Test
    fun `unresolved relationship IDs never become fake song rows`() {
        assertEquals(
            CollectionDetailMessage.METADATA_PENDING,
            ShippyCollectionDetailState.System("Liked", emptyList(), unresolvedTrackCount = 3)
                .messageKind(),
        )
        assertEquals(
            CollectionDetailMessage.EMPTY,
            ShippyCollectionDetailState.System("Downloads", emptyList(), unresolvedTrackCount = 0)
                .messageKind(),
        )
        assertEquals(
            CollectionDetailMessage.DELETED,
            ShippyCollectionDetailState.Missing.messageKind(),
        )
    }

    @Test
    fun `playlist state keeps persisted management metadata separate from tracks`() {
        val playlist =
            LibraryCollection.Playlist(
                id = LibraryCollectionId("playlist:road-trip"),
                displayName = "Road trip",
                isPinned = true,
            )

        val state =
            ShippyCollectionDetailState.Playlist(
                playlist = playlist,
                trackIds = emptyList(),
                rows = emptyList(),
                unresolvedTrackCount = 2,
            )

        assertEquals("Road trip", state.title)
        assertTrue(state.playlist.isPinned)
        assertEquals(CollectionDetailMessage.METADATA_PENDING, state.messageKind())
    }

    @Test
    fun `persisted canonical metadata creates playable rows in collection order`() {
        val first = track("provider:first")
        val resolved =
            listOf(TrackId("provider:missing"), first.id)
                .resolveRows(listOf(first to CollectionRowDownloadPresentation.Hidden))

        assertEquals(listOf(first), resolved.rows.map { it.track })
        assertEquals(CollectionRowDownloadPresentation.Hidden, resolved.rows.single().download)
        assertEquals(1, resolved.unresolvedCount)
    }

    @Test
    fun `persisted local metadata keeps its exact candidate in playlist order`() {
        val localId = TrackId("local:umas123e4567-e89b-12d3-a456-426614174000")
        val local =
            Track(
                id = localId,
                realm = TrackRealm.LOCAL,
                title = "Exact local song",
                artists = listOf("Artist"),
                candidates =
                    listOf(
                        TrackCandidate(
                            id = CandidateId(localId.value),
                            trackId = localId,
                            kind = CandidateKind.LOCAL,
                            sourceId = "device-local",
                            sourceItemId = "umas123e4567-e89b-12d3-a456-426614174000",
                            availability = CandidateAvailability.AVAILABLE,
                            locator = "content://device/music/exact-song",
                        )
                    ),
            )

        val resolved =
            listOf(TrackId("provider:other"), local.id)
                .resolveRows(listOf(local to CollectionRowDownloadPresentation.Hidden))

        assertEquals(listOf(local), resolved.rows.map { it.track })
        assertEquals(CandidateKind.LOCAL, resolved.rows.single().track.candidates.single().kind)
        assertEquals(
            "umas123e4567-e89b-12d3-a456-426614174000",
            resolved.rows.single().track.candidates.single().sourceItemId,
        )
        assertEquals(1, resolved.unresolvedCount)
    }

    @Test
    fun `saved provider track remains a playable row without a download`() {
        val saved = track("provider:saved")

        val resolved =
            listOf(saved.id).resolveRows(listOf(saved to CollectionRowDownloadPresentation.Hidden))

        assertEquals(saved, resolved.rows.single().track)
        assertEquals(CollectionRowDownloadPresentation.Hidden, resolved.rows.single().download)
        assertEquals(0, resolved.unresolvedCount)
    }

    @Test
    fun `download selects first available eligible provider candidate in track order`() {
        val track =
            track(
                id = "provider:ordered",
                candidates =
                    listOf(
                        candidate(
                            "unavailable",
                            CandidateAvailability.UNAVAILABLE,
                            ProviderId("downloadable"),
                        ),
                        candidate(
                            "not-downloadable",
                            CandidateAvailability.RESOLVABLE,
                            ProviderId("other"),
                        ),
                        candidate(
                            "first",
                            CandidateAvailability.AVAILABLE,
                            ProviderId("downloadable"),
                        ),
                        candidate(
                            "second",
                            CandidateAvailability.RESOLVABLE,
                            ProviderId("downloadable"),
                        ),
                    ),
            )

        val presentation =
            collectionRowDownloadPresentation(track, null, setOf(ProviderId("downloadable")))

        assertEquals(
            CollectionRowDownloadPresentation.Ready(CandidateId("candidate:first")),
            presentation,
        )
    }

    @Test
    fun `local and ineligible provider rows hide direct download`() {
        val local = track("local:exact").copy(realm = TrackRealm.LOCAL)
        val unavailable =
            track(
                id = "provider:unavailable",
                candidates =
                    listOf(
                        candidate(
                            "gone",
                            CandidateAvailability.UNAVAILABLE,
                            ProviderId("downloadable"),
                            TrackId("provider:unavailable"),
                        )
                    ),
            )

        assertEquals(
            CollectionRowDownloadPresentation.Hidden,
            collectionRowDownloadPresentation(local, null, setOf(ProviderId("downloadable"))),
        )
        assertEquals(
            CollectionRowDownloadPresentation.Hidden,
            collectionRowDownloadPresentation(unavailable, null, setOf(ProviderId("downloadable"))),
        )
    }

    @Test
    fun `download state maps to exact resumable retryable working and available identities`() {
        val track = track("provider:states")
        val candidateId = track.candidates.single().id
        val providerIds = setOf(ProviderId("provider"))

        assertEquals(
            CollectionRowDownloadPresentation.Paused(DownloadJobId("paused")),
            collectionRowDownloadPresentation(
                track,
                persisted(track, candidateId, "paused", DownloadState.PAUSED),
                providerIds,
            ),
        )
        assertEquals(
            CollectionRowDownloadPresentation.Retry(DownloadJobId("retry")),
            collectionRowDownloadPresentation(
                track,
                persisted(track, candidateId, "retry", DownloadState.FAILED_RETRYABLE),
                providerIds,
            ),
        )
        assertEquals(
            CollectionRowDownloadPresentation.Working(
                DownloadJobId("working"),
                DownloadState.TRANSFERRING,
            ),
            collectionRowDownloadPresentation(
                track,
                persisted(track, candidateId, "working", DownloadState.TRANSFERRING),
                providerIds,
            ),
        )
        assertEquals(
            CollectionRowDownloadPresentation.Available(DownloadJobId("available")),
            collectionRowDownloadPresentation(
                track,
                persisted(track, candidateId, "available", DownloadState.AVAILABLE),
                providerIds,
            ),
        )
    }

    @Test
    fun `terminal download returns the selected candidate to ready`() {
        val track = track("provider:terminal")

        assertEquals(
            CollectionRowDownloadPresentation.Ready(track.candidates.single().id),
            collectionRowDownloadPresentation(
                track,
                persisted(track, track.candidates.single().id, "removed", DownloadState.REMOVED),
                setOf(ProviderId("provider")),
            ),
        )
    }

    private fun track(id: String, candidates: List<TrackCandidate>? = null): Track {
        val trackId = TrackId(id)
        return Track(
            id = trackId,
            realm = TrackRealm.PROVIDER,
            title = "Saved track",
            artists = listOf("Artist"),
            candidates =
                candidates
                    ?: listOf(
                        candidate(
                            id.removePrefix("provider:"),
                            CandidateAvailability.RESOLVABLE,
                            ProviderId("provider"),
                            trackId,
                        )
                    ),
        )
    }

    private fun candidate(
        id: String,
        availability: CandidateAvailability,
        providerId: ProviderId,
        trackId: TrackId = TrackId("provider:ordered"),
    ) =
        TrackCandidate(
            id = CandidateId("candidate:$id"),
            trackId = trackId,
            kind = CandidateKind.PROVIDER,
            sourceId = providerId.value,
            sourceItemId = id,
            availability = availability,
            providerId = providerId,
        )

    private fun persisted(
        track: Track,
        candidateId: CandidateId,
        jobId: String,
        state: DownloadState,
    ): PersistedDownload {
        val artifact =
            if (state == DownloadState.AVAILABLE) {
                DownloadArtifact("content://downloads/$jobId", 1, "audio/mpeg", 1)
            } else {
                null
            }
        return PersistedDownload(
            job =
                DownloadJob(
                    DownloadJobId(jobId),
                    track.id,
                    candidateId,
                    state,
                    artifact = artifact,
                ),
            track = track,
            pendingDocument = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
    }
}
