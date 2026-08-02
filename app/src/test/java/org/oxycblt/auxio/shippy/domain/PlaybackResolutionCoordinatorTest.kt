/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackResolutionCoordinatorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.media.CrewMediaChunkDescriptor
import org.oxycblt.auxio.shippy.crew.media.CrewMediaDigest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaRequestId
import org.oxycblt.auxio.shippy.crew.media.CrewMediaTransferRef
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadJob
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.download.withVerifiedDownloadCandidate
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class PlaybackResolutionCoordinatorTest {
    private val provider = FakeProvider()
    private val coordinator =
        PlaybackResolutionCoordinator(PlaybackResolver(), ProviderRegistry(setOf(provider)))

    @Test
    fun `provider resolution becomes canonical resolved playback`() = runBlocking {
        val item = queueItem("one")

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            )

        assertTrue(result is PlaybackPreparation.Ready)
        val ready = (result as PlaybackPreparation.Ready).value
        assertEquals(item.id, ready.playback.queueItemId)
        assertEquals("https://media.test/track.m4a", ready.playback.uri)
        assertEquals(mapOf("Referer" to "https://provider.test"), ready.playback.headers)
    }

    @Test
    fun `duplicate tracks retain different queue item identity`() = runBlocking {
        val first = queueItem("first")
        val second = first.copy(id = QueueItemId("second"))
        val policy = ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false)

        val firstReady = coordinator.prepare(first, policy) as PlaybackPreparation.Ready
        val secondReady = coordinator.prepare(second, policy) as PlaybackPreparation.Ready

        assertNotEquals(
            firstReady.value.playback.queueItemId,
            secondReady.value.playback.queueItemId,
        )
        assertEquals(first.track.id, second.track.id)
        assertEquals(
            firstReady.value.playback.mediaObjectKey,
            secondReady.value.playback.mediaObjectKey,
        )
        assertTrue(firstReady.value.playback.cacheEligible)
    }

    @Test
    fun `exact local queue item bypasses provider resolution and retains identity`() = runBlocking {
        val callsBefore = provider.resolveCalls
        val localTrackId = TrackId("local:umas123e4567-e89b-12d3-a456-426614174000")
        val localCandidate =
            TrackCandidate(
                id = CandidateId("local:umas123e4567-e89b-12d3-a456-426614174000"),
                trackId = localTrackId,
                kind = CandidateKind.LOCAL,
                sourceId = "device-local",
                sourceItemId = "umas123e4567-e89b-12d3-a456-426614174000",
                availability = CandidateAvailability.AVAILABLE,
                locator = "content://device/music/exact-song",
            )
        val item =
            QueueItem(
                id = QueueItemId("playlist-occurrence-2"),
                track =
                    Track(
                        id = localTrackId,
                        realm = TrackRealm.LOCAL,
                        title = "Exact local song",
                        artists = listOf("Artist"),
                        candidates = listOf(localCandidate),
                    ),
            )

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            )

        assertTrue(result is PlaybackPreparation.Ready)
        val playback = (result as PlaybackPreparation.Ready).value.playback
        assertEquals(item.id, playback.queueItemId)
        assertEquals(localCandidate.id, playback.candidateId)
        assertEquals(localCandidate.locator, playback.uri)
        assertEquals(callsBefore, provider.resolveCalls)
    }

    @Test
    fun `available verified download wins and preserves exact queue item`() = runBlocking {
        val item = queueItem("download-occurrence")
        val coordinator = coordinatorWithDownload { verifiedDownload(item) }
        val callsBefore = provider.resolveCalls

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            ) as PlaybackPreparation.Ready

        assertEquals(item.id, result.value.item.id)
        assertEquals(item.id, result.value.playback.queueItemId)
        assertEquals("content://shippy/download/verified", result.value.playback.uri)
        assertEquals(CandidateKind.DOWNLOAD, result.value.item.track.candidates.last().kind)
        assertEquals(callsBefore, provider.resolveCalls)
    }

    @Test
    fun `exact active Crew temporary media wins ahead of verified download without another player path`() =
        runBlocking {
            val item = queueItem("crew-occurrence")
            val file = crewTemporaryFile()
            try {
                val index = activeCrewIndex(item, file)
                val expected = requireNotNull(index.augmentActive(item)).track.candidates.last()
                val coordinator = coordinatorWithCrewTemporary(index, { verifiedDownload(item) })
                val callsBefore = provider.resolveCalls

                val result =
                    coordinator.prepare(
                        item,
                        ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
                    ) as PlaybackPreparation.Ready

                assertEquals(item.id, result.value.item.id)
                assertEquals(item.id, result.value.playback.queueItemId)
                assertEquals(expected.id, result.value.playback.candidateId)
                assertEquals(expected.locator, result.value.playback.uri)
                assertEquals(
                    CandidateKind.CREW_TEMPORARY,
                    result.value.item.track.candidates.first { it.id == expected.id }.kind,
                )
                assertEquals(callsBefore, provider.resolveCalls)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `ended Crew overlay leaves verified download behavior unchanged`() = runBlocking {
        val item = queueItem("ended-crew-occurrence")
        val file = crewTemporaryFile()
        try {
            val index = activeCrewIndex(item, file)
            index.endSession(CrewSessionId("playback-crew", ProtocolVersion(1)))
            val coordinator = coordinatorWithCrewTemporary(index, { verifiedDownload(item) })

            val result =
                coordinator.prepare(
                    item,
                    ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
                ) as PlaybackPreparation.Ready

            assertEquals(item.id, result.value.playback.queueItemId)
            assertEquals("content://shippy/download/verified", result.value.playback.uri)
            assertTrue(
                result.value.item.track.candidates.none { it.kind == CandidateKind.CREW_TEMPORARY }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `no active Crew overlay leaves provider behavior unchanged`() = runBlocking {
        val item = queueItem("inactive-crew-occurrence")
        val coordinator = coordinatorWithCrewTemporary(CrewTemporaryMediaIndex(), { null })
        val callsBefore = provider.resolveCalls

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            ) as PlaybackPreparation.Ready

        assertEquals(item.id, result.value.playback.queueItemId)
        assertEquals(CandidateId("provider:track"), result.value.playback.candidateId)
        assertEquals(callsBefore + 1, provider.resolveCalls)
    }

    @Test
    fun `only available matching verified download is synthesized`() {
        val item = queueItem("guarded-download")
        val originalCandidates = item.track.candidates
        val valid = verifiedDownload(item)
        val invalidDownloads =
            listOf(
                valid.copy(job = valid.job.copy(candidateId = CandidateId("provider:other"))),
                valid.copy(job = valid.job.copy(trackId = TrackId("provider:other"))),
                valid.copy(
                    job = valid.job.copy(artifact = valid.job.artifact!!.copy(contentLength = 0))
                ),
                valid.copy(job = valid.job.copy(state = DownloadState.VERIFYING, artifact = null)),
            )

        invalidDownloads.forEach { download ->
            val augmented = item.withVerifiedDownloadCandidate(download)
            assertEquals(item, augmented)
            assertEquals(originalCandidates, augmented.track.candidates)
        }
    }

    @Test
    fun `download repository failure falls back to provider`() = runBlocking {
        val item = queueItem("repository-failure")
        val coordinator = coordinatorWithDownload { error("download database unavailable") }
        val callsBefore = provider.resolveCalls

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            ) as PlaybackPreparation.Ready

        assertEquals(CandidateId("provider:track"), result.value.playback.candidateId)
        assertEquals(callsBefore + 1, provider.resolveCalls)
    }

    @Test
    fun `download candidate augmentation is idempotent`() {
        val item = queueItem("idempotent-download")
        val download = verifiedDownload(item)

        val once = item.withVerifiedDownloadCandidate(download)
        val twice = once.withVerifiedDownloadCandidate(download)

        assertEquals(once, twice)
        assertEquals(1, twice.track.candidates.count { it.kind == CandidateKind.DOWNLOAD })
    }

    private fun queueItem(queueId: String): QueueItem {
        val providerId = provider.descriptor.id
        val trackId = TrackId("provider:track")
        return QueueItem(
            id = QueueItemId(queueId),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.PROVIDER,
                    title = "Track",
                    artists = listOf("Artist"),
                    candidates =
                        listOf(
                            TrackCandidate(
                                id = CandidateId("provider:track"),
                                trackId = trackId,
                                kind = CandidateKind.PROVIDER,
                                sourceId = providerId.value,
                                sourceItemId = "track",
                                availability = CandidateAvailability.RESOLVABLE,
                                providerId = providerId,
                            )
                        ),
                ),
        )
    }

    private fun coordinatorWithDownload(
        latestDownloadForTrack: suspend (TrackId) -> PersistedDownload?
    ): PlaybackResolutionCoordinator =
        PlaybackResolutionCoordinator(
            PlaybackResolver(),
            ProviderRegistry(setOf(provider)),
            latestDownloadForTrack,
        )

    private fun coordinatorWithCrewTemporary(
        index: CrewTemporaryMediaIndex,
        latestDownloadForTrack: suspend (TrackId) -> PersistedDownload?,
    ): PlaybackResolutionCoordinator =
        PlaybackResolutionCoordinator(
            PlaybackResolver(),
            ProviderRegistry(setOf(provider)),
            latestDownloadForTrack,
            index::augmentActive,
        )

    private fun activeCrewIndex(item: QueueItem, file: File): CrewTemporaryMediaIndex {
        val session = CrewSessionId("playback-crew", ProtocolVersion(1))
        val bytes = file.readBytes()
        val manifest =
            CrewMediaManifest(
                transfer =
                    CrewMediaTransferRef(
                        session,
                        CrewMediaRequestId("playback-request"),
                        item.id,
                        CandidateId("provider:track"),
                        CrewMemberId("target", ProtocolVersion(1)),
                        CrewMemberId("supplier", ProtocolVersion(1)),
                    ),
                mimeType = "audio/mpeg",
                objectSizeBytes = bytes.size.toLong(),
                objectIntegrity = CrewMediaDigest.sha256(bytes),
                chunks = listOf(CrewMediaChunkDescriptor(0, bytes.size)),
            )
        return CrewTemporaryMediaIndex().also {
            it.beginSession(session)
            assertTrue(it.complete(manifest, file))
        }
    }

    private fun crewTemporaryFile(): File {
        val bytes = byteArrayOf(1, 2, 3, 4)
        return File.createTempFile("playback-crew", ".media").also { it.writeBytes(bytes) }
    }

    private fun verifiedDownload(item: QueueItem): PersistedDownload {
        val artifact =
            DownloadArtifact(
                contentUri = "content://shippy/download/verified",
                contentLength = 1024,
                mimeType = "audio/mpeg",
                verifiedAtEpochMs = 1,
            )
        return PersistedDownload(
            job =
                DownloadJob(
                    id = DownloadJobId("download-job"),
                    trackId = item.track.id,
                    candidateId = CandidateId("provider:track"),
                    state = DownloadState.AVAILABLE,
                    bytesTransferred = artifact.contentLength,
                    expectedBytes = artifact.contentLength,
                    artifact = artifact,
                ),
            track = item.track,
            pendingDocument = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
    }

    private class FakeProvider : MusicProvider {
        var resolveCalls = 0

        override val descriptor =
            ProviderDescriptor(
                id = ProviderId("provider"),
                displayName = "Provider",
                capabilities = setOf(ProviderCapability.STREAM),
            )

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> {
            resolveCalls++
            return ProviderResult.Success(
                ResolvedStream(
                    candidateId = candidate.id,
                    uri = "https://media.test/track.m4a",
                    mimeType = "audio/mp4",
                    headers = mapOf("Referer" to "https://provider.test"),
                )
            )
        }
    }
}
