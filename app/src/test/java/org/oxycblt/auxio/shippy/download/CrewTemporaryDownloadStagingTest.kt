/*
 * Copyright (c) 2026 Shippy contributors
 * CrewTemporaryDownloadStagingTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.download

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewTemporaryDownloadStagingTest {
    @Test
    fun `staging snapshots only the requested Crew candidate and retains its identity`() = runBlocking {
        val root = Files.createTempDirectory("crew-download-staging-test").toFile()
        try {
            val source = File(root, "crew-source").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val crew = TrackCandidate(
                id = CandidateId("crew:exact"),
                trackId = TrackId("track"),
                kind = CandidateKind.CREW_TEMPORARY,
                sourceId = "crew-temporary",
                sourceItemId = "original",
                availability = CandidateAvailability.AVAILABLE,
                locator = source.toURI().toString(),
                media = MediaDescriptor(contentLength = 3),
            )
            val local = TrackCandidate(
                id = CandidateId("local"),
                trackId = TrackId("track"),
                kind = CandidateKind.LOCAL,
                sourceId = "local",
                sourceItemId = "local",
                availability = CandidateAvailability.AVAILABLE,
            )
            val track =
                Track(
                    TrackId("track"),
                    TrackRealm.LOCAL,
                    "Track",
                    listOf("Artist"),
                    candidates = listOf(local, crew),
                )
            val staging = CrewTemporaryDownloadStaging(File(root, "private"))
            val staged = staging.stage(track, crew.id, DownloadJobId("job"))

            val stagedCrew = staged.candidates.single { it.id == crew.id }
            assertNotEquals(crew.locator, stagedCrew.locator)
            assertEquals(crew.copy(locator = stagedCrew.locator), stagedCrew)
            assertEquals(local, staged.candidates.single { it.id == local.id })
            assertTrue(File(java.net.URI(stagedCrew.locator)).readBytes().contentEquals(source.readBytes()))
            staging.cleanup(DownloadJobId("job"))
        } finally {
            root.deleteRecursively()
        }
    }
}
