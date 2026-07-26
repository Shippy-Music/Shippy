/*
 * Copyright (c) 2026 Shippy contributors
 * PlayerActionsPresentationTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.lyrics.SyncedLyricLine
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics

class PlayerActionsPresentationTest {
    @Test
    fun `local tracks never show a Shippy download action`() {
        assertTrue(
            downloadPresentation(
                localTrack(),
                CandidateId("local:candidate"),
                null,
                setOf(ProviderId("jiosaavn")),
            ) is
                PlayerDownloadPresentation.Hidden
        )
    }

    @Test
    fun `local tracks show download for the resolved available Crew temporary candidate`() {
        val track = localTrack().copy(
            candidates = localTrack().candidates + TrackCandidate(
                id = CandidateId("crew:candidate"),
                trackId = TrackId("local:track"),
                kind = CandidateKind.CREW_TEMPORARY,
                sourceId = "crew-temporary",
                sourceItemId = "song",
                availability = CandidateAvailability.AVAILABLE,
                locator = "file:/private/crew-media",
            )
        )
        assertTrue(
            downloadPresentation(track, CandidateId("crew:candidate"), null, emptySet()) is
                PlayerDownloadPresentation.Ready
        )
    }

    @Test
    fun `provider tracks with a resolvable candidate show download`() {
        assertTrue(
            downloadPresentation(
                providerTrack(),
                CandidateId("provider:candidate"),
                null,
                setOf(ProviderId("jiosaavn")),
            ) is
                PlayerDownloadPresentation.Ready
        )
    }

    @Test
    fun `provider without download capability hides the download action`() {
        assertTrue(
            downloadPresentation(
                providerTrack(),
                CandidateId("provider:candidate"),
                null,
                emptySet(),
            ) is
                PlayerDownloadPresentation.Hidden
        )
    }

    @Test
    fun `download action never silently switches away from the resolved candidate`() {
        assertTrue(
            downloadPresentation(
                providerTrack(),
                CandidateId("other:candidate"),
                null,
                setOf(ProviderId("jiosaavn")),
            ) is
                PlayerDownloadPresentation.Hidden
        )
    }

    @Test
    fun `synced lyrics stay empty before the first timestamp`() {
        val lyrics =
            SyncedLyrics(
                lines =
                    listOf(
                        SyncedLyricLine(2_000, "First"),
                        SyncedLyricLine(4_000, "Second"),
                    ),
                plainText = "First\nSecond",
            )

        assertTrue(activeLyricIndex(lyrics, 1_999) == -1)
        assertTrue(activeLyricIndex(lyrics, 2_000) == 0)
        assertTrue(activeLyricIndex(lyrics, 4_500) == 1)
    }

    private fun localTrack() =
        Track(
            id = TrackId("local:track"),
            realm = TrackRealm.LOCAL,
            title = "Local",
            artists = listOf("Artist"),
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("local:candidate"),
                        trackId = TrackId("local:track"),
                        kind = CandidateKind.LOCAL,
                        sourceId = "local",
                        sourceItemId = "song",
                        availability = CandidateAvailability.AVAILABLE,
                    )
                ),
        )

    private fun providerTrack() =
        Track(
            id = TrackId("provider:track"),
            realm = TrackRealm.PROVIDER,
            title = "Provider",
            artists = listOf("Artist"),
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("provider:candidate"),
                        trackId = TrackId("provider:track"),
                        kind = CandidateKind.PROVIDER,
                        sourceId = "jiosaavn",
                        sourceItemId = "song",
                        availability = CandidateAvailability.RESOLVABLE,
                        providerId = ProviderId("jiosaavn"),
                    )
                ),
        )
}
