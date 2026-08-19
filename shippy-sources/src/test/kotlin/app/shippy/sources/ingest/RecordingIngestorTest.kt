/*
 * Copyright (c) 2026 Auxio Project
 * RecordingIngestorTest.kt is part of Auxio.
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
package app.shippy.sources.ingest

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.MetadataNormalizer
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.sources.asset.ManagedAssetEvidence
import app.shippy.sources.asset.ManagedAssetMatch
import app.shippy.sources.asset.ManagedAssetRecord
import app.shippy.sources.asset.ManagedAssetRegistry
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingIngestorTest {
    @Test
    fun `repeated exact source reuses one recording`() = runSuspend {
        val store = FakeStore()
        val ingestor = RecordingIngestor(store, recordingIdFactory = fixedId(RECORDING_NEW))
        val observation = observation(sourceItemId = "track-1")

        val first = ingestor.ingest(observation)
        val second = ingestor.ingest(observation.copy(capturedAt = Instant.ofEpochMilli(2)))

        assertEquals(IngestionResolution.NEW_RECORDING, first.resolution)
        assertEquals(IngestionResolution.EXACT_SOURCE, second.resolution)
        assertEquals(RECORDING_NEW, second.recordingId)
        assertEquals(1, store.sources.size)
    }

    @Test
    fun `exact managed asset wins before metadata similarity`() = runSuspend {
        val managed =
            ManagedAssetRecord(
                assetId = ASSET_MANAGED,
                recordingId = RECORDING_MANAGED,
                evidence = managedEvidence("content://downloads/owned"),
                verified = true,
            )
        val store = FakeStore(managedRecords = listOf(managed))
        val ingestor = RecordingIngestor(store, recordingIdFactory = fixedId(RECORDING_NEW))

        val result =
            ingestor.ingest(
                observation(
                    sourceItemId = "local-1",
                    sourceKind = SourceKind.LOCAL_FILE,
                    asset = observedAsset("content://downloads/owned"),
                )
            )

        assertEquals(IngestionResolution.MANAGED_ASSET, result.resolution)
        assertEquals(RECORDING_MANAGED, result.recordingId)
        assertNull(store.writes.single().newRecording)
    }

    @Test
    fun `metadata ambiguity creates a separate recording and review`() = runSuspend {
        val store =
            FakeStore(
                candidates =
                    listOf(
                        candidate(RECORDING_A, "Song", "Artist"),
                        candidate(RECORDING_B, "Song", "Artist"),
                    )
            )
        val ingestor = RecordingIngestor(store, recordingIdFactory = fixedId(RECORDING_NEW))

        val result = ingestor.ingest(observation(sourceItemId = "provider-new"))

        assertEquals(IngestionResolution.NEW_RECORDING, result.resolution)
        assertEquals(RECORDING_NEW, result.recordingId)
        assertEquals(setOf(RECORDING_A, RECORDING_B), result.reviewCandidateIds)
    }

    @Test
    fun `material version mismatch is not linked or queued as probable`() = runSuspend {
        val store =
            FakeStore(
                candidates =
                    listOf(
                        candidate(
                            RECORDING_A,
                            "Song",
                            "Artist",
                            RecordingVersion(VersionKind.LIVE, "Live"),
                        )
                    )
            )
        val ingestor = RecordingIngestor(store, recordingIdFactory = fixedId(RECORDING_NEW))

        val result = ingestor.ingest(observation(sourceItemId = "studio-version"))

        assertEquals(IngestionResolution.NEW_RECORDING, result.resolution)
        assertEquals(emptySet<RecordingId>(), result.reviewCandidateIds)
    }

    private class FakeStore(
        managedRecords: List<ManagedAssetRecord> = emptyList(),
        private val candidates: List<IngestionIdentityCandidate> = emptyList(),
    ) : RecordingIngestionStore, RecordingIngestionTransaction {
        val sources = linkedMapOf<SourceKey, RecordingId?>()
        val writes = mutableListOf<RecordingIngestionWrite>()
        private val registry = ManagedAssetRegistry(managedRecords)

        override suspend fun <T> transaction(
            block: suspend RecordingIngestionTransaction.() -> T
        ): T = block(this)

        override suspend fun exactSource(sourceKey: SourceKey): ExistingSourceLink? =
            if (sourceKey in sources) ExistingSourceLink(sourceKey, sources[sourceKey]) else null

        override suspend fun classifyManagedAsset(asset: ManagedAssetEvidence): ManagedAssetMatch =
            registry.classify(asset)

        override suspend fun identityCandidates(
            features: MatchingFeatures
        ): List<IngestionIdentityCandidate> = candidates

        override suspend fun persist(write: RecordingIngestionWrite) {
            writes += write
            sources[write.observation.sourceKey] = write.recordingId
        }
    }

    private companion object {
        val RECORDING_A = RecordingId("00000000-0000-0000-0000-000000000001")
        val RECORDING_B = RecordingId("00000000-0000-0000-0000-000000000002")
        val RECORDING_NEW = RecordingId("00000000-0000-0000-0000-000000000003")
        val RECORDING_MANAGED = RecordingId("00000000-0000-0000-0000-000000000004")
        val ASSET_MANAGED = MediaAssetId("00000000-0000-0000-0000-000000000005")

        fun fixedId(id: RecordingId) = RecordingIdFactory { id }

        fun observation(
            sourceItemId: String,
            sourceKind: SourceKind = SourceKind.YOUTUBE_MUSIC,
            asset: ObservedMediaAsset? = null,
        ) =
            SourceTrackObservation(
                sourceKey =
                    SourceKey(
                        ProviderId(sourceKind.name.lowercase()),
                        SourceItemType.RECORDING,
                        sourceItemId,
                    ),
                sourceKind = sourceKind,
                title = "Song",
                artistNames = listOf("Artist"),
                releaseTitle = "Release",
                durationMs = 180_000,
                version = RecordingVersion(VersionKind.ORIGINAL),
                explicitness = Explicitness.UNKNOWN,
                artwork = emptyList(),
                externalIdentifiers = emptySet(),
                originalUrl = null,
                asset = asset,
                capturedAt = Instant.ofEpochMilli(1),
            )

        fun observedAsset(location: String) =
            ObservedMediaAsset(
                kind = MediaAssetKind.LOCAL_FILE,
                location = AssetLocation(location),
                locationType = "CONTENT_URI",
                documentId = null,
                mediaStoreId = null,
                normalizedPathToken = "downloads/owned",
                downloadJobId = null,
                lastModifiedEpochMs = 10,
                technical = AudioTechnicalMetadata("audio/mpeg", "mp3", 320_000, 44_100, 2, 100),
                checksum = null,
                fingerprint = null,
                verifiedAt = Instant.ofEpochMilli(1),
            )

        fun managedEvidence(location: String) =
            ManagedAssetEvidence(
                locationType = "CONTENT_URI",
                location = AssetLocation(location),
                normalizedPathToken = "downloads/owned",
                contentLength = 100,
                lastModifiedEpochMs = 10,
            )

        fun candidate(
            recordingId: RecordingId,
            title: String,
            artist: String,
            version: RecordingVersion = RecordingVersion(VersionKind.ORIGINAL),
        ) =
            IngestionIdentityCandidate(
                recordingId,
                MatchingFeatures(
                    normalizedTitle = MetadataNormalizer.comparisonKey(title),
                    normalizedPrimaryArtist = MetadataNormalizer.comparisonKey(artist),
                    normalizedArtistSet = setOfNotNull(MetadataNormalizer.comparisonKey(artist)),
                    normalizedRelease = MetadataNormalizer.comparisonKey("Release"),
                    durationMs = 180_000,
                    version = version,
                    explicitness = Explicitness.UNKNOWN,
                ),
            )
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
