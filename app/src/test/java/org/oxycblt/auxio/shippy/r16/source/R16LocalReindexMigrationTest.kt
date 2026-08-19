/*
 * Copyright (c) 2026 Auxio Project
 * R16LocalReindexMigrationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.migration.R16LocalReindexAudit
import app.shippy.data.migration.R16LocalReindexCheckpoint
import app.shippy.sources.asset.ManagedAssetEvidence
import app.shippy.sources.asset.ManagedAssetMatch
import app.shippy.sources.ingest.ExistingSourceLink
import app.shippy.sources.ingest.IngestionIdentityCandidate
import app.shippy.sources.ingest.RecordingIdFactory
import app.shippy.sources.ingest.RecordingIngestionStore
import app.shippy.sources.ingest.RecordingIngestionTransaction
import app.shippy.sources.ingest.RecordingIngestionWrite
import app.shippy.sources.ingest.RecordingIngestor
import app.shippy.sources.local.LocalAssetHandle
import app.shippy.sources.local.LocalAssetKey
import app.shippy.sources.local.LocalDeleteResult
import app.shippy.sources.local.LocalMediaChange
import app.shippy.sources.local.LocalMediaEngine
import app.shippy.sources.local.LocalScanRequest
import app.shippy.sources.local.LocalScanState
import app.shippy.sources.local.TagPatch
import app.shippy.sources.local.TagWriteResult
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LocalReindexMigrationTest {
    @Test
    fun `M12 resumes complete pages and restarts safely when snapshot changes`() = runBlocking {
        val engine = FakeLocalMediaEngine((0 until 120).map(::observation))
        val store = FakeIngestionStore(failOnceAt = "track-075")
        val audit = FakeAudit()
        val ingestor =
            RecordingIngestor(
                store,
                recordingIdFactory =
                    RecordingIdFactory { value ->
                        RecordingId(
                            "00000000-0000-0000-0000-" +
                                value.sourceKey.sourceItemId
                                    .removePrefix("track-")
                                    .padStart(12, '0')
                        )
                    },
            )
        val migration = R16LocalReindexMigration(engine, ingestor, audit, pageSize = 50)

        assertTrue(runCatching { migration.run("migration-1") }.isFailure)
        assertEquals(50L, audit.current?.processedCount)
        assertFalse(requireNotNull(audit.current).complete)

        val resumed = migration.run("migration-1")

        assertTrue(resumed.complete)
        assertEquals(120L, resumed.processedCount)
        assertEquals(120L, resumed.linkedCount)
        assertEquals(120, store.sources.size)

        engine.observations = (0 until 121).map(::observation)
        val restarted = migration.run("migration-1")

        assertTrue(restarted.complete)
        assertEquals(121L, restarted.processedCount)
        assertEquals(121L, restarted.linkedCount)
        assertEquals(121, store.sources.size)
    }

    private class FakeAudit : R16LocalReindexAudit {
        var current: R16LocalReindexCheckpoint? = null

        override suspend fun load(migrationId: String): R16LocalReindexCheckpoint? = current

        override suspend fun save(migrationId: String, checkpoint: R16LocalReindexCheckpoint) {
            current = checkpoint
        }
    }

    private class FakeLocalMediaEngine(var observations: List<SourceTrackObservation>) :
        LocalMediaEngine {
        override val scanState = MutableStateFlow<LocalScanState>(LocalScanState.Idle)

        override fun observeChanges(): Flow<LocalMediaChange> = emptyFlow()

        override suspend fun scan(request: LocalScanRequest) = Unit

        override suspend fun snapshot(): List<SourceTrackObservation> = observations

        override suspend fun open(asset: LocalAssetKey): LocalAssetHandle = error("unused")

        override suspend fun delete(asset: LocalAssetKey): LocalDeleteResult =
            LocalDeleteResult.Unsupported

        override suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult =
            TagWriteResult.Unsupported
    }

    private class FakeIngestionStore(private var failOnceAt: String?) :
        RecordingIngestionStore, RecordingIngestionTransaction {
        val sources = linkedMapOf<SourceKey, RecordingId?>()

        override suspend fun <T> transaction(
            block: suspend RecordingIngestionTransaction.() -> T
        ): T = block(this)

        override suspend fun exactSource(sourceKey: SourceKey): ExistingSourceLink? =
            if (sourceKey in sources) ExistingSourceLink(sourceKey, sources[sourceKey]) else null

        override suspend fun classifyManagedAsset(asset: ManagedAssetEvidence): ManagedAssetMatch =
            ManagedAssetMatch.None

        override suspend fun identityCandidates(
            observation: SourceTrackObservation,
            features: MatchingFeatures,
        ): List<IngestionIdentityCandidate> = emptyList()

        override suspend fun persist(write: RecordingIngestionWrite) {
            if (write.observation.sourceKey.sourceItemId == failOnceAt) {
                failOnceAt = null
                error("simulated interruption")
            }
            sources[write.observation.sourceKey] = write.recordingId
        }
    }

    private companion object {
        fun observation(index: Int): SourceTrackObservation {
            val itemId = "track-${index.toString().padStart(3, '0')}"
            return SourceTrackObservation(
                sourceKey = SourceKey(ProviderId("local-file"), SourceItemType.LOCAL_FILE, itemId),
                sourceKind = SourceKind.LOCAL_FILE,
                title = "Song $index",
                artistNames = listOf("Artist"),
                releaseTitle = null,
                durationMs = 180_000,
                version = RecordingVersion(VersionKind.ORIGINAL),
                explicitness = Explicitness.UNKNOWN,
                artwork = emptyList(),
                externalIdentifiers = emptySet(),
                originalUrl = null,
                asset =
                    ObservedMediaAsset(
                        kind = MediaAssetKind.LOCAL_FILE,
                        location = AssetLocation("content://media/audio/$index"),
                        locationType = "CONTENT_URI",
                        documentId = null,
                        mediaStoreId = index.toLong(),
                        normalizedPathToken = "primary/Music/$itemId.flac",
                        downloadJobId = null,
                        lastModifiedEpochMs = 1,
                        technical =
                            AudioTechnicalMetadata(
                                mimeType = "audio/flac",
                                codec = "flac",
                                bitrateBps = null,
                                sampleRateHz = null,
                                channelCount = null,
                                contentLength = index.toLong(),
                            ),
                        checksum = null,
                        fingerprint = null,
                        verifiedAt = Instant.ofEpochMilli(1),
                    ),
                capturedAt = Instant.ofEpochMilli(1),
            )
        }
    }
}
