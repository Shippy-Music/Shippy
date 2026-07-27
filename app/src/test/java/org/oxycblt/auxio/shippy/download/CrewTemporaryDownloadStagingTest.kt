/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryDownloadStagingTest.kt is part of Auxio.
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
    fun `staging snapshots only the requested Crew candidate and retains its identity`() {
        runBlocking {
            val root = Files.createTempDirectory("crew-download-staging-test").toFile()
            try {
                val source = File(root, "crew-source").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val crew =
                    TrackCandidate(
                        id = CandidateId("crew:exact"),
                        trackId = TrackId("track"),
                        kind = CandidateKind.CREW_TEMPORARY,
                        sourceId = "crew-temporary",
                        sourceItemId = "original",
                        availability = CandidateAvailability.AVAILABLE,
                        locator = source.toURI().toString(),
                        media = MediaDescriptor(contentLength = 3),
                    )
                val local =
                    TrackCandidate(
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
                assertTrue(
                    File(java.net.URI(stagedCrew.locator))
                        .readBytes()
                        .contentEquals(source.readBytes())
                )
                staging.cleanup(DownloadJobId("job"))
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
