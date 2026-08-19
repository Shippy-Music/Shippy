/*
 * Copyright (c) 2026 Auxio Project
 * RoomR16IngestionRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.MetadataNormalizer
import app.shippy.core.identitymatch.RecordingDraft
import app.shippy.core.music.ArtworkReference
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifier
import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.migration.R16LocalReindexCheckpoint
import app.shippy.data.migration.RoomR16LocalReindexAudit
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomR16IngestionRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var repository: R16IngestionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomR16IngestionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `local ingestion is idempotent queryable and rolls back location conflict`() = runBlocking {
        val first = command(RECORDING_ONE, "musikr-one", "content://media/audio/1")

        repository.transaction { persist(first) }
        repository.transaction {
            persist(
                first.copy(
                    observation = first.observation.copy(capturedAt = Instant.ofEpochMilli(2))
                )
            )
        }

        assertEquals(1, database.legacyImportDao().recordingCount())
        assertEquals(1, database.legacyImportDao().sourceCount())
        assertEquals(1, database.legacyImportDao().assetCount())
        assertEquals(1, database.sourceDao().observationCount())

        repository.transaction {
            assertEquals(RECORDING_ONE, exactSource(first.observation.sourceKey)?.recordingId)
            val managed = managedAssetCandidates(requireNotNull(first.observation.asset))
            assertEquals(RECORDING_ONE, managed.single().recordingId)
            assertEquals("primary/Music/song.flac", managed.single().evidence.normalizedPathToken)
            val candidates =
                identityCandidates(first.observation, first.observation.toMatchingFeatures())
            assertEquals(RECORDING_ONE, candidates.single().recordingId)
        }

        val conflict = command(RECORDING_TWO, "musikr-two", "content://media/audio/1")
        val failure = runCatching { repository.transaction { persist(conflict) } }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertEquals(1, database.legacyImportDao().recordingCount())
        assertEquals(1, database.legacyImportDao().sourceCount())
        assertEquals(1, database.legacyImportDao().assetCount())
    }

    @Test
    fun `managed download rescan preserves ownership and strong evidence`() = runBlocking {
        val original = command(RECORDING_ONE, "download-source", "content://downloads/owned")
        val downloaded =
            original.copy(
                observation =
                    original.observation.copy(
                        sourceKey =
                            SourceKey(
                                ProviderId("shippy-download"),
                                SourceItemType.RECORDING,
                                "download-source",
                            ),
                        sourceKind = SourceKind.SHIPPY_DOWNLOAD,
                        asset =
                            requireNotNull(original.observation.asset)
                                .copy(
                                    kind = MediaAssetKind.SHIPPY_DOWNLOAD,
                                    checksum = ContentChecksum("SHA-256", "verified-checksum"),
                                    downloadJobId = "download-job-1",
                                ),
                    )
            )
        repository.transaction { persist(downloaded) }
        val assetId =
            MediaAssetId(database.assetDao().forRecording(RECORDING_ONE.value).single().assetId)
        val rescanned =
            downloaded.observation.copy(
                sourceKey =
                    SourceKey(
                        ProviderId("local-file"),
                        SourceItemType.LOCAL_FILE,
                        "musikr-download",
                    ),
                sourceKind = SourceKind.LOCAL_FILE,
                asset =
                    requireNotNull(downloaded.observation.asset)
                        .copy(
                            kind = MediaAssetKind.LOCAL_FILE,
                            location = AssetLocation("content://media/audio/99"),
                            checksum = null,
                            downloadJobId = null,
                        ),
            )

        repository.transaction {
            persist(
                R16IngestionCommand(
                    observation = rescanned,
                    recordingId = RECORDING_ONE,
                    newRecording = null,
                    resolution = "MANAGED_ASSET",
                    identityEvidence = null,
                    assetWriteMode = R16AssetWriteMode.REUSE_EXACT,
                    exactManagedAssetId = assetId,
                    reviewCandidateIds = emptySet(),
                )
            )
        }

        val stored = database.assetDao().get(assetId.value)
        assertEquals("SHIPPY_DOWNLOAD", stored?.assetKind)
        assertEquals("SHA-256:verified-checksum", stored?.contentChecksum)
        assertEquals("download-job-1", stored?.downloadJobId)
        assertEquals("content://media/audio/99", stored?.location)
        assertEquals(1, database.legacyImportDao().assetCount())
        assertEquals(2, database.legacyImportDao().sourceCount())
    }

    @Test
    fun `local reindex audit preserves prior counts and resumes exact checkpoint`() = runBlocking {
        database
            .migrationAuditDao()
            .start(
                MigrationAuditEntity(
                    migrationId = "migration-1",
                    sourceVersion = 10,
                    targetVersion = 1,
                    startedAtEpochMs = 1,
                    completedAtEpochMs = null,
                    sourceCountsJson = "{}",
                    targetCountsJson = null,
                    warningsJson = "[]",
                    checksum = null,
                    status = "IMPORTING",
                )
            )
        database
            .migrationAuditDao()
            .updateProgress("migration-1", "{\"liked\":3}", "[]", "IMPORTING")
        val audit = RoomR16LocalReindexAudit(database)
        assertNull(audit.load("migration-1"))
        val checkpoint =
            R16LocalReindexCheckpoint(
                snapshotFingerprint = "fingerprint",
                snapshotCount = 2,
                processedCount = 1,
                linkedCount = 1,
                unresolvedCount = 0,
                lastSourceKey = "local-file/source-1",
                complete = false,
            )

        audit.save("migration-1", checkpoint)

        assertEquals(checkpoint, audit.load("migration-1"))
        val targetCounts =
            JSONObject(
                requireNotNull(
                    requireNotNull(database.migrationAuditDao().get("migration-1")).targetCountsJson
                )
            )
        assertEquals(3, targetCounts.getInt("liked"))
        assertEquals("M12", targetCounts.getJSONObject("checkpoint").getString("phase"))
    }

    private companion object {
        val RECORDING_ONE = RecordingId("00000000-0000-0000-0000-000000000001")
        val RECORDING_TWO = RecordingId("00000000-0000-0000-0000-000000000002")

        fun command(
            recordingId: RecordingId,
            sourceItemId: String,
            location: String,
        ): R16IngestionCommand {
            val version = RecordingVersion(VersionKind.ORIGINAL)
            val observation =
                R16SourceObservation(
                    sourceKey =
                        SourceKey(
                            ProviderId("local-file"),
                            SourceItemType.LOCAL_FILE,
                            sourceItemId,
                        ),
                    sourceKind = SourceKind.LOCAL_FILE,
                    title = "Song",
                    artistNames = listOf("Artist"),
                    releaseTitle = "Release",
                    durationMs = 180_000,
                    version = version,
                    explicitness = Explicitness.UNKNOWN,
                    artwork = listOf(ArtworkReference("content://art/1")),
                    externalIdentifiers =
                        setOf(
                            ExternalIdentifier(
                                ExternalIdentifierKind.MUSICBRAINZ_RECORDING,
                                "00000000-0000-0000-0000-000000000010",
                            )
                        ),
                    originalUrl = null,
                    asset =
                        R16ObservedAsset(
                            kind = MediaAssetKind.LOCAL_FILE,
                            location = AssetLocation(location),
                            locationType = "CONTENT_URI",
                            documentId = "document-1",
                            mediaStoreId = 1,
                            normalizedPathToken = "primary/Music/song.flac",
                            downloadJobId = null,
                            lastModifiedEpochMs = 100,
                            technical =
                                AudioTechnicalMetadata(
                                    mimeType = "audio/flac",
                                    codec = "flac",
                                    bitrateBps = 900_000,
                                    sampleRateHz = 48_000,
                                    channelCount = 2,
                                    contentLength = 42,
                                ),
                            checksum = null,
                            fingerprintId = "fingerprint-1",
                            verifiedAt = Instant.ofEpochMilli(1),
                        ),
                    capturedAt = Instant.ofEpochMilli(1),
                )
            return R16IngestionCommand(
                observation = observation,
                recordingId = recordingId,
                newRecording = RecordingDraft("Song", "Artist", 180_000, version),
                resolution = "NEW_RECORDING",
                identityEvidence = null,
                assetWriteMode = R16AssetWriteMode.REGISTER,
                exactManagedAssetId = null,
                reviewCandidateIds = emptySet(),
            )
        }

        fun R16SourceObservation.toMatchingFeatures() =
            MatchingFeatures(
                normalizedTitle = MetadataNormalizer.comparisonKey(title),
                normalizedPrimaryArtist = MetadataNormalizer.comparisonKey(artistNames.first()),
                normalizedArtistSet =
                    artistNames.mapNotNullTo(linkedSetOf(), MetadataNormalizer::comparisonKey),
                normalizedRelease = MetadataNormalizer.comparisonKey(releaseTitle),
                durationMs = durationMs,
                version = version,
                explicitness = explicitness,
                musicBrainzRecordingIds =
                    externalIdentifiers
                        .filter { it.kind == ExternalIdentifierKind.MUSICBRAINZ_RECORDING }
                        .mapTo(linkedSetOf()) { it.value },
                sourceKeys = setOf(sourceKey),
                fingerprintHashes = setOfNotNull(asset?.fingerprintId),
            )
    }
}
