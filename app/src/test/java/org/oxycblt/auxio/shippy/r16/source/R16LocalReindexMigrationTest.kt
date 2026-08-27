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
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.ingest.R16AssetWriteMode
import app.shippy.data.ingest.R16ExistingSource
import app.shippy.data.ingest.R16IdentityCandidate
import app.shippy.data.ingest.R16IngestionCommand
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.R16IngestionSession
import app.shippy.data.ingest.R16ManagedAsset
import app.shippy.data.ingest.R16ObservedAsset
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
import app.shippy.sources.local.LocalMediaSnapshotDescriptor
import app.shippy.sources.local.LocalMediaSnapshotPage
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `M5 prerequisite page owns its cursor and restarts on snapshot fingerprint change`() =
        runBlocking {
            val engine = FakeLocalMediaEngine((0 until 4).map(::observation))
            val store = FakeIngestionStore(failOnceAt = null)
            val audit = FakeAudit()
            val migration =
                R16LocalReindexMigration(
                    engine,
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
                    ),
                    audit,
                    pageSize = 2,
                )

            val first = migration.runPage("m5", pageSize = 2, persistAudit = false)

            assertFalse(first.complete)
            assertNull(audit.current)
            engine.observations = (0 until 5).map(::observation)
            val restarted = migration.runPage("m5", first, pageSize = 2, persistAudit = false)

            assertNotEquals(first.snapshotFingerprint, restarted.snapshotFingerprint)
            assertEquals(2L, restarted.processedCount)
            assertEquals(2, store.sources.size)
            assertNull(audit.current)
        }

    @Test
    fun `M12 converts only requested pages from a large catalog`() = runBlocking {
        val engine = BoundedFakeLocalMediaEngine(count = 50_000)
        val store = FakeIngestionStore(failOnceAt = null)
        val migration =
            R16LocalReindexMigration(
                engine,
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
                ),
                FakeAudit(),
                pageSize = 50,
            )

        val first = migration.runPage("large", pageSize = 50, persistAudit = false)
        val second = migration.runPage("large", first, pageSize = 50, persistAudit = false)

        assertEquals(50L, first.processedCount)
        assertEquals(100L, second.processedCount)
        assertEquals(listOf(0L to 50, 50L to 50), engine.pageCalls)
        assertEquals(2, engine.descriptorCalls)
        assertEquals(100, engine.convertedCount)
        assertEquals(0, engine.fullSnapshotCalls)
    }

    @Test
    fun `one scanner path reuses exact managed download and keeps same-name file separate`() =
        runBlocking {
            val managedAsset = managedDownloadAsset()
            val repository = FakeR16IngestionRepository(listOf(managedAsset))
            val migration =
                R16LocalReindexMigration(
                    localMediaEngine =
                        FakeLocalMediaEngine(
                            listOf(
                                scannerObservation(
                                    sourceItemId = "managed-001",
                                    location = MANAGED_DOWNLOAD_LOCATION,
                                    pathToken = MANAGED_DOWNLOAD_PATH,
                                    downloadJobId = DOWNLOAD_JOB_ID,
                                ),
                                scannerObservation(
                                    sourceItemId = "unrelated-002",
                                    location = "content://media/audio/other",
                                    pathToken = "primary/Music/song.flac",
                                    downloadJobId = null,
                                ),
                            )
                        ),
                    ingestor =
                        RecordingIngestor(
                            DataRecordingIngestionStore(repository),
                            recordingIdFactory = RecordingIdFactory { RECORDING_NEW },
                        ),
                    audit = FakeAudit(),
                    pageSize = 2,
                )

            val progress = migration.run("managed-download-rescan")

            assertTrue(progress.complete)
            assertEquals(2, repository.commands.size)
            val reused = repository.commands[0]
            assertEquals(RECORDING_MANAGED, reused.recordingId)
            assertNull(reused.newRecording)
            assertEquals(R16AssetWriteMode.REUSE_EXACT, reused.assetWriteMode)
            assertEquals(ASSET_MANAGED, reused.exactManagedAssetId)
            assertEquals(MediaAssetKind.LOCAL_FILE, reused.observation.asset?.kind)
            assertEquals(MediaAssetKind.SHIPPY_DOWNLOAD, managedAsset.evidence.kind)
            assertEquals(listOf(ASSET_MANAGED), repository.reusedAssetIds)

            val unrelated = repository.commands[1]
            assertEquals(RECORDING_NEW, unrelated.recordingId)
            assertNotEquals(RECORDING_MANAGED, unrelated.recordingId)
            assertTrue(unrelated.newRecording != null)
            assertEquals(R16AssetWriteMode.REGISTER, unrelated.assetWriteMode)
            assertNull(unrelated.exactManagedAssetId)
            assertEquals(setOf(RECORDING_MANAGED, RECORDING_NEW), repository.recordingIds)
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

    private class BoundedFakeLocalMediaEngine(private val count: Int) : LocalMediaEngine {
        private val descriptor = LocalMediaSnapshotDescriptor("large-catalog-v1", count.toLong())
        val pageCalls = mutableListOf<Pair<Long, Int>>()
        var descriptorCalls = 0
        var convertedCount = 0
        var fullSnapshotCalls = 0

        override val scanState = MutableStateFlow<LocalScanState>(LocalScanState.Idle)

        override fun observeChanges(): Flow<LocalMediaChange> = emptyFlow()

        override suspend fun scan(request: LocalScanRequest) = Unit

        override suspend fun snapshot(): List<SourceTrackObservation> {
            fullSnapshotCalls++
            error("M12 must not request a full local snapshot")
        }

        override suspend fun snapshotDescriptor(): LocalMediaSnapshotDescriptor {
            descriptorCalls++
            return descriptor
        }

        override suspend fun snapshotPage(
            offset: Long,
            limit: Int,
            expectedFingerprint: String?,
        ): LocalMediaSnapshotPage {
            check(expectedFingerprint == descriptor.fingerprint)
            pageCalls += offset to limit
            val end = minOf(count, Math.addExact(offset.toInt(), limit))
            val observations =
                (offset.toInt() until end).map {
                    convertedCount++
                    observation(it)
                }
            return LocalMediaSnapshotPage(descriptor, offset, observations)
        }

        override suspend fun open(asset: LocalAssetKey): LocalAssetHandle = error("unused")

        override suspend fun delete(asset: LocalAssetKey): LocalDeleteResult =
            LocalDeleteResult.Unsupported

        override suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult =
            TagWriteResult.Unsupported
    }

    private class FakeR16IngestionRepository(private val managedAssets: List<R16ManagedAsset>) :
        R16IngestionRepository {
        val commands = mutableListOf<R16IngestionCommand>()
        val recordingIds = linkedSetOf<RecordingId>()
        val reusedAssetIds = mutableListOf<MediaAssetId>()
        private val sourceLinks = linkedMapOf<SourceKey, RecordingId?>()

        override suspend fun <T> transaction(block: suspend R16IngestionSession.() -> T): T =
            block(
                object : R16IngestionSession {
                    override suspend fun exactSource(sourceKey: SourceKey): R16ExistingSource? =
                        if (sourceKey in sourceLinks) {
                            R16ExistingSource(sourceKey, sourceLinks[sourceKey])
                        } else {
                            null
                        }

                    override suspend fun managedAssetCandidates(
                        asset: R16ObservedAsset
                    ): List<R16ManagedAsset> = managedAssets

                    override suspend fun identityCandidates(
                        observation: app.shippy.data.ingest.R16SourceObservation,
                        features: MatchingFeatures,
                    ): List<R16IdentityCandidate> = emptyList()

                    override suspend fun persist(command: R16IngestionCommand) {
                        commands += command
                        command.recordingId?.let(recordingIds::add)
                        command.exactManagedAssetId?.let(reusedAssetIds::add)
                        sourceLinks[command.observation.sourceKey] = command.recordingId
                    }
                }
            )
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
        val RECORDING_MANAGED = RecordingId("00000000-0000-0000-0000-000000000101")
        val RECORDING_NEW = RecordingId("00000000-0000-0000-0000-000000000102")
        val ASSET_MANAGED = MediaAssetId("00000000-0000-0000-0000-000000000103")
        const val DOWNLOAD_JOB_ID = "download-job-1"
        const val MANAGED_DOWNLOAD_LOCATION = "content://downloads/owned/song.flac"
        const val MANAGED_DOWNLOAD_PATH = "primary/Shippy/song.flac"

        fun managedDownloadAsset() =
            R16ManagedAsset(
                assetId = ASSET_MANAGED,
                recordingId = RECORDING_MANAGED,
                evidence =
                    R16ObservedAsset(
                        kind = MediaAssetKind.SHIPPY_DOWNLOAD,
                        location = AssetLocation(MANAGED_DOWNLOAD_LOCATION),
                        locationType = "CONTENT_URI",
                        documentId = null,
                        mediaStoreId = null,
                        normalizedPathToken = MANAGED_DOWNLOAD_PATH,
                        downloadJobId = DOWNLOAD_JOB_ID,
                        lastModifiedEpochMs = 200,
                        technical =
                            AudioTechnicalMetadata(
                                mimeType = "audio/flac",
                                codec = "flac",
                                bitrateBps = 900_000,
                                sampleRateHz = 48_000,
                                channelCount = 2,
                                contentLength = 100,
                            ),
                        checksum = null,
                        fingerprintId = null,
                        verifiedAt = Instant.ofEpochMilli(200),
                    ),
                verified = true,
            )

        fun scannerObservation(
            sourceItemId: String,
            location: String,
            pathToken: String,
            downloadJobId: String?,
        ): SourceTrackObservation {
            val base = observation(0)
            val baseAsset = requireNotNull(base.asset)
            return base.copy(
                sourceKey =
                    SourceKey(ProviderId("local-file"), SourceItemType.LOCAL_FILE, sourceItemId),
                title = "Song",
                asset =
                    baseAsset.copy(
                        location = AssetLocation(location),
                        normalizedPathToken = pathToken,
                        downloadJobId = downloadJobId,
                        lastModifiedEpochMs = 200,
                        mediaStoreId = null,
                        technical = baseAsset.technical.copy(contentLength = 100),
                    ),
            )
        }

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
