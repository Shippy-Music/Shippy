/*
 * Copyright (c) 2026 Shippy contributors
 * CollectionDetailPresentationTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.download.DownloadState

class CollectionDetailPresentationTest {
    @Test
    fun `only Local uses the existing Auxio local-library surface`() {
        assertTrue(LibraryCollection.System(SystemCollectionKind.LOCAL).id.usesAuxioLocalSurface())
        assertFalse(LibraryCollection.System(SystemCollectionKind.LIKED).id.usesAuxioLocalSurface())
        assertFalse(LibraryCollectionId("playlist:road-trip").usesAuxioLocalSurface())
    }

    @Test
    fun `unresolved relationship IDs never become fake song rows`() {
        assertEquals(
            CollectionDetailMessage.METADATA_PENDING,
            ShippyCollectionDetailState.System("Liked", emptyList(), unresolvedTrackCount = 3).messageKind(),
        )
        assertEquals(
            CollectionDetailMessage.EMPTY,
            ShippyCollectionDetailState.System("Downloads", emptyList(), unresolvedTrackCount = 0).messageKind(),
        )
        assertEquals(CollectionDetailMessage.DELETED, ShippyCollectionDetailState.Missing.messageKind())
    }

    @Test
    fun `playlist state keeps persisted management metadata separate from tracks`() {
        val playlist =
            LibraryCollection.Playlist(
                id = LibraryCollectionId("playlist:road-trip"),
                displayName = "Road trip",
                isPinned = true,
            )

        val state = ShippyCollectionDetailState.Playlist(playlist, emptyList(), unresolvedTrackCount = 2)

        assertEquals("Road trip", state.title)
        assertTrue(state.playlist.isPinned)
        assertEquals(CollectionDetailMessage.METADATA_PENDING, state.messageKind())
    }

    @Test
    fun `persisted canonical metadata creates playable rows in collection order`() {
        val first = track("provider:first")
        val resolved =
            listOf(TrackId("provider:missing"), first.id)
                .resolveRows(listOf(first to DownloadState.AVAILABLE))

        assertEquals(listOf(first), resolved.rows.map { it.track })
        assertEquals(DownloadState.AVAILABLE, resolved.rows.single().downloadState)
        assertEquals(1, resolved.unresolvedCount)
    }

    @Test
    fun `saved provider track remains a playable row without a download`() {
        val saved = track("provider:saved")

        val resolved = listOf(saved.id).resolveRows(listOf(saved to null))

        assertEquals(saved, resolved.rows.single().track)
        assertEquals(null, resolved.rows.single().downloadState)
        assertEquals(0, resolved.unresolvedCount)
    }

    private fun track(id: String): Track {
        val trackId = TrackId(id)
        return Track(
            id = trackId,
            realm = TrackRealm.PROVIDER,
            title = "Saved track",
            artists = listOf("Artist"),
            candidates = listOf(
                TrackCandidate(CandidateId("candidate:$id"), trackId, CandidateKind.PROVIDER,
                    "provider", "item", CandidateAvailability.RESOLVABLE,
                    providerId = org.oxycblt.auxio.shippy.domain.ProviderId("provider")),
            ),
        )
    }
}
