/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadPersistenceMappingTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.download.DownloadState

class DownloadPersistenceMappingTest {
    @Test
    fun `artist codec preserves punctuation unicode and line breaks`() {
        val artists = listOf("A.B", "Björk", "line\nbreak", "हिन्दी")

        assertEquals(artists, decodeArtists(encodeArtists(artists)))
    }

    @Test
    fun `stored job restores candidates in explicit position order`() {
        val stored =
            StoredDownloadJob(
                job =
                    DownloadJobEntity(
                        jobId = "job:1",
                        trackId = "track:1",
                        requestedCandidateId = "candidate:first",
                        trackRealm = "PROVIDER",
                        title = "Track",
                        artists = encodeArtists(listOf("Artist")),
                        album = "Album",
                        durationMs = 1000,
                        versionLabel = null,
                        explicit = null,
                        live = false,
                        remix = false,
                        artwork = null,
                        state = DownloadState.REQUESTED.name,
                        bytesTransferred = 0,
                        expectedBytes = null,
                        failureCode = null,
                        failureMessage = null,
                        artifactUri = null,
                        artifactLength = null,
                        artifactMimeType = null,
                        artifactVerifiedAtEpochMs = null,
                        pendingUri = null,
                        pendingDisplayName = null,
                        pendingMimeType = null,
                        createdAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    ),
                candidates =
                    listOf(
                        candidate("candidate:second", 1),
                        candidate("candidate:first", 0),
                    ),
            )

        val restored = stored.toDomain()

        assertEquals("candidate:first", restored.track.candidates[0].id.value)
        assertEquals("candidate:second", restored.track.candidates[1].id.value)
        assertEquals(DownloadState.REQUESTED, restored.job.state)
        assertTrue(restored.pendingDocument == null)
    }

    private fun candidate(id: String, position: Int) =
        DownloadCandidateEntity(
            jobId = "job:1",
            candidateId = id,
            position = position,
            kind = "PROVIDER",
            sourceId = "jiosaavn",
            sourceItemId = id,
            availability = "RESOLVABLE",
            locator = null,
            providerId = "jiosaavn",
            mimeType = "audio/mp4",
            container = "m4a",
            bitrateBps = 160000,
            contentLength = null,
        )
}
