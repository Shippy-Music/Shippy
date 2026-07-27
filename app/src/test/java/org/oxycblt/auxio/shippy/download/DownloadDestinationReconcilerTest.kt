/*
 * Copyright (c) 2026 Auxio Project
 * DownloadDestinationReconcilerTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload

class DownloadDestinationReconcilerTest {
    @Test
    fun `exact artifact URI and length remain managed while other audio stays unmanaged`() {
        val managed = available("managed", "track:managed", "content://tree/managed", 100)
        val unmanaged = document("content://tree/existing", "already-here.m4a", 100)

        val plan =
            DownloadReconciliationPlanner.plan(
                ready(document("content://tree/managed", "download.m4a", 100), unmanaged),
                listOf(managed),
            )

        assertTrue(plan.missingArtifactJobIds.isEmpty())
        assertEquals(listOf(unmanaged), plan.result.unmanagedDocuments)
        assertEquals(
            listOf(DownloadRelationshipRepair(TrackId("track:managed"), downloaded = true)),
            plan.relationshipRepairs,
        )
    }

    @Test
    fun `same filename does not substitute for a missing artifact`() {
        val missing = available("missing", "track:missing", "content://tree/original", 100)
        val sameNameDifferentUri = document("content://tree/copy", "download.m4a", 100)

        val plan = DownloadReconciliationPlanner.plan(ready(sameNameDifferentUri), listOf(missing))

        assertEquals(listOf(DownloadJobId("missing")), plan.missingArtifactJobIds)
        assertEquals(listOf(sameNameDifferentUri), plan.result.unmanagedDocuments)
        assertEquals(
            listOf(DownloadRelationshipRepair(TrackId("track:missing"), downloaded = false)),
            plan.relationshipRepairs,
        )
    }

    @Test
    fun `wrong length repairs artifact even when URI remains present`() {
        val corrupted = available("corrupted", "track:corrupted", "content://tree/song", 100)

        val plan =
            DownloadReconciliationPlanner.plan(
                ready(document("content://tree/song", "song.m4a", 99)),
                listOf(corrupted),
            )

        assertEquals(listOf(DownloadJobId("corrupted")), plan.result.repairedMissingArtifactJobIds)
        assertTrue(plan.result.unmanagedDocuments.isEmpty())
        assertFalse(plan.relationshipRepairs.single().downloaded)
    }

    @Test
    fun `revoked destination preserves durable projection until access returns`() {
        val available = available("saved", "track:saved", "content://tree/song", 100)

        val plan =
            DownloadReconciliationPlanner.plan(
                DownloadDestinationState.Unavailable(
                    destination = DownloadDestination("content://tree", "Downloads"),
                    reason = DownloadStorageFailure.PERMISSION_REVOKED,
                ),
                listOf(available),
            )

        assertTrue(plan.missingArtifactJobIds.isEmpty())
        assertTrue(plan.relationshipRepairs.isEmpty())
    }

    @Test
    fun `one exact artifact keeps duplicated track membership while broken sibling is repaired`() {
        val intact = available("intact", "track:shared", "content://tree/intact", 100)
        val missing = available("missing", "track:shared", "content://tree/missing", 100)

        val plan =
            DownloadReconciliationPlanner.plan(
                ready(document("content://tree/intact", "intact.m4a", 100)),
                listOf(intact, missing),
            )

        assertEquals(listOf(DownloadJobId("missing")), plan.missingArtifactJobIds)
        assertEquals(
            listOf(DownloadRelationshipRepair(TrackId("track:shared"), downloaded = true)),
            plan.relationshipRepairs,
        )
    }

    @Test
    fun `terminal job repairs stale downloaded relationship`() {
        val removed =
            available("removed", "track:removed", "content://tree/removed", 100).let {
                it.copy(job = it.job.copy(state = DownloadState.REMOVED, artifact = null))
            }

        val plan = DownloadReconciliationPlanner.plan(ready(), listOf(removed))

        assertEquals(
            listOf(DownloadRelationshipRepair(TrackId("track:removed"), downloaded = false)),
            plan.relationshipRepairs,
        )
    }

    private fun available(jobId: String, trackId: String, uri: String, length: Long) =
        PersistedDownload(
            job =
                DownloadJob(
                    id = DownloadJobId(jobId),
                    trackId = TrackId(trackId),
                    candidateId = CandidateId("candidate:$jobId"),
                    state = DownloadState.AVAILABLE,
                    bytesTransferred = length,
                    expectedBytes = length,
                    artifact = DownloadArtifact(uri, length, "audio/mp4", 1),
                ),
            track =
                Track(
                    id = TrackId(trackId),
                    realm = TrackRealm.PROVIDER,
                    title = "Track $trackId",
                    artists = listOf("Artist"),
                    candidates = emptyList(),
                ),
            pendingDocument = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun ready(vararg documents: StoredAudioDocument) =
        DownloadDestinationState.Ready(
            destination = DownloadDestination("content://tree", "Downloads"),
            existingAudio = documents.toList(),
        )

    private fun document(uri: String, name: String, length: Long) =
        StoredAudioDocument(
            contentUri = uri,
            displayName = name,
            mimeType = "audio/mp4",
            contentLength = length,
        )
}
