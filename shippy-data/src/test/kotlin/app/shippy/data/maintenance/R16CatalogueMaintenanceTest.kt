/*
 * Copyright (c) 2026 Auxio Project
 * R16CatalogueMaintenanceTest.kt is part of Auxio.
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
package app.shippy.data.maintenance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.RecordingDraft
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifier
import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.IdentityDecisionEntity
import app.shippy.data.db.entity.IdentityRejectionEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.ingest.R16AssetWriteMode
import app.shippy.data.ingest.R16IngestionCommand
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.R16SourceObservation
import app.shippy.data.ingest.RoomR16IngestionRepository
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16CatalogueMaintenanceTest {
    private lateinit var database: ShippyR16Database
    private lateinit var ingestion: R16IngestionRepository
    private lateinit var maintenance: R16CatalogueMaintenance

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
                .allowMainThreadQueries()
                .build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        ingestion = RoomR16IngestionRepository(database)
        maintenance = RoomR16CatalogueMaintenance(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `bounded collection removes expired transient graph but honors runtime protection`() =
        runBlocking {
            val now = Instant.parse("2026-08-19T00:00:00Z")
            val capturedAt = now.minus(Duration.ofDays(61))
            val expired = ingest(1, capturedAt)
            val protected = ingest(2, capturedAt)
            database.searchDao().refresh(expired.value)
            database.searchDao().refresh(protected.value)

            assertEquals(
                capturedAt.plus(Duration.ofDays(60)).toEpochMilli(),
                database.recordingDao().get(expired.value)?.retainedUntilEpochMs,
            )
            val result =
                maintenance.collectExpiredTransient(
                    R16CatalogueGcRequest(
                        now = now,
                        protections = R16CatalogueGcProtections(activeQueue = setOf(protected)),
                        limit = 2,
                    )
                )

            assertEquals(listOf(expired), result.deletedRecordingIds)
            assertEquals(1, result.skippedProtectedCount)
            assertNull(database.recordingDao().get(expired.value))
            assertNotNull(database.recordingDao().get(protected.value))
            assertEquals(1, tableCount("recording"))
            assertEquals(1, tableCount("source_reference"))
            assertEquals(1, tableCount("metadata_observation"))
            assertEquals(1, tableCount("external_identifier"))
            assertEquals(1, tableCount("recording_fts"))
            assertEquals(1, tableCount("artist"))
        }

    @Test
    fun `durable relationships decisions checkpoints history and assets prevent collection`() =
        runBlocking {
            val now = Instant.parse("2026-08-19T00:00:00Z")
            val capturedAt = now.minus(Duration.ofDays(61))
            val library = ingest(10, capturedAt)
            val playlist = ingest(11, capturedAt)
            val asset = ingest(12, capturedAt)
            val override = ingest(13, capturedAt)
            val checkpoint = ingest(14, capturedAt)
            val history = ingest(15, capturedAt)
            val decision = ingest(16, capturedAt)
            val rejection = ingest(17, capturedAt)
            val pending = ingest(18, capturedAt)
            database
                .libraryDao()
                .upsertRelationship(
                    LibraryRecordingEntity(
                        recordingId = library.value,
                        liked = true,
                        explicitlySaved = false,
                        userEdited = false,
                        manuallyIdentified = false,
                        firstAddedAtEpochMs = now.toEpochMilli(),
                        updatedAtEpochMs = now.toEpochMilli(),
                    )
                )
            database
                .playlistDao()
                .create(
                    PlaylistEntity(
                        playlistId = "playlist-1",
                        name = "Fixture",
                        pinned = false,
                        libraryOrderKey = 1,
                        artworkOverride = null,
                        displaySortMode = "MANUAL",
                        displaySortDirection = "ASCENDING",
                        originKind = "USER",
                        originKey = null,
                        createdAtEpochMs = now.toEpochMilli(),
                        updatedAtEpochMs = now.toEpochMilli(),
                    ),
                    listOf(
                        PlaylistEntryEntity(
                            playlistEntryId = "playlist-entry-1",
                            playlistId = "playlist-1",
                            recordingId = playlist.value,
                            orderKey = 1,
                            addedAtEpochMs = now.toEpochMilli(),
                        )
                    ),
                )
            exec(
                """
                INSERT INTO media_asset (
                    asset_id, recording_id, source_reference_id, asset_kind, asset_state,
                    location_type, location, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES ('asset-1', '${asset.value}', NULL, 'COMPLETE_CACHE', 'AVAILABLE',
                    'CACHE', 'internal://cache/asset-1', 1, 1)
                """
            )
            exec(
                """
                INSERT INTO user_metadata_override (
                    recording_id, field_name, value_json, updated_at_epoch_ms
                ) VALUES ('${override.value}', 'title', '"User title"', 1)
                """
            )
            database
                .playbackCheckpointDao()
                .replace(
                    PlaybackCheckpointEntity(
                        slot = "active",
                        checkpointVersion = 1,
                        sessionId = "session-1",
                        currentQueueEntryId = "queue-1",
                        positionMs = 0,
                        playingIntent = false,
                        repeatMode = "NONE",
                        shuffleEnabled = false,
                        shuffleSeed = null,
                        baseOrderJson = "[\"queue-1\"]",
                        traversalOrderJson = "[\"queue-1\"]",
                        updatedAtEpochMs = now.toEpochMilli(),
                        checksum = "fixture-checksum",
                    ),
                    listOf(
                        PlaybackCheckpointEntryEntity(
                            slot = "active",
                            queueEntryId = "queue-1",
                            position = 0,
                            recordingId = checkpoint.value,
                            originJson = null,
                            contributorId = null,
                            presentationFallbackJson = "{}",
                        )
                    ),
                )
            database
                .historyDao()
                .save(
                    PlayHistoryEntity(
                        listeningSessionId = "history-1",
                        recordingId = history.value,
                        queueEntryId = "queue-history",
                        sourceReferenceId = null,
                        startedAtEpochMs = capturedAt.toEpochMilli(),
                        endedAtEpochMs = capturedAt.plusSeconds(60).toEpochMilli(),
                        activeListenedMs = 60_000,
                        lastPositionMs = 60_000,
                        completionKind = "COMPLETED",
                        chosenByUser = true,
                    )
                )
            database
                .identityDao()
                .insertDecision(
                    IdentityDecisionEntity(
                        decisionId = "decision-1",
                        subjectType = "RECORDING",
                        subjectId = decision.value,
                        targetRecordingId = decision.value,
                        decisionKind = "MANUAL_LINK",
                        confidence = 1.0,
                        evidenceJson = "[]",
                        userConfirmed = true,
                        createdAtEpochMs = now.toEpochMilli(),
                    )
                )
            database
                .identityDao()
                .upsertRejection(
                    IdentityRejectionEntity(
                        subjectType = "RECORDING",
                        subjectId = rejection.value,
                        rejectedRecordingId = rejection.value,
                        reason = "user choice",
                        createdAtEpochMs = now.toEpochMilli(),
                    )
                )

            val result =
                maintenance.collectExpiredTransient(
                    R16CatalogueGcRequest(
                        now = now,
                        protections = R16CatalogueGcProtections(pendingWork = setOf(pending)),
                        limit = 50,
                    )
                )

            // 8 durable/protected recordings survive, while the expired transient recording with
            // history is collected
            assertEquals(listOf(history), result.deletedRecordingIds)
            assertEquals(1, result.skippedProtectedCount)
            assertEquals(8, tableCount("recording"))
            // Verify history row survived recording deletion
            assertEquals(1, tableCount("play_history"))
        }

    @Test
    fun `transient recording with play history is purged by GC while history survives with snapshots and presentation`() =
        runBlocking {
            val now = Instant.parse("2026-08-19T00:00:00Z")
            val capturedAt = now.minus(Duration.ofDays(65))
            val transientRecording = ingest(101, capturedAt)

            // Write play history with self-presenting snapshot metadata
            database
                .historyDao()
                .save(
                    PlayHistoryEntity(
                        listeningSessionId = "session-transient-1",
                        recordingId = transientRecording.value,
                        queueEntryId = "queue-1",
                        sourceReferenceId = null,
                        startedAtEpochMs = capturedAt.toEpochMilli(),
                        endedAtEpochMs = capturedAt.plusSeconds(180).toEpochMilli(),
                        activeListenedMs = 180_000,
                        lastPositionMs = 180_000,
                        completionKind = "NATURAL_END",
                        chosenByUser = true,
                        snapshotTitle = "Streamed Indie Song",
                        snapshotArtistDisplay = "Streamed Indie Artist",
                        snapshotArtworkLocation = "https://artwork.example.com/streamed.jpg",
                    )
                )

            // Prior to GC, presentation queries return metadata from recording or snapshot
            val beforeGc = database.historyDao().recentFinishedWithPresentation(10)
            assertEquals(1, beforeGc.size)
            assertEquals(transientRecording.value, beforeGc.first().recordingId)
            assertEquals("Fixture 101", beforeGc.first().title)

            // Run GC on expired transient recording
            val result = maintenance.collectExpiredTransient(R16CatalogueGcRequest(now = now))
            assertEquals(listOf(transientRecording), result.deletedRecordingIds)
            assertEquals(0, tableCount("recording"))

            // Verify play history survives with recordingId set to NULL
            val historyAfterGc = database.historyDao().recent(10)
            assertEquals(1, historyAfterGc.size)
            assertNull(historyAfterGc.first().recordingId)
            assertEquals("Streamed Indie Song", historyAfterGc.first().snapshotTitle)
            assertEquals("Streamed Indie Artist", historyAfterGc.first().snapshotArtistDisplay)
            assertEquals(
                "https://artwork.example.com/streamed.jpg",
                historyAfterGc.first().snapshotArtworkLocation,
            )

            // Verify history presentation queries still present the row using snapshots
            val presentationAfterGc = database.historyDao().recentFinishedWithPresentation(10)
            assertEquals(1, presentationAfterGc.size)
            assertNull(presentationAfterGc.first().recordingId)
            assertEquals("Streamed Indie Song", presentationAfterGc.first().title)
            assertEquals("Streamed Indie Artist", presentationAfterGc.first().artist)
            assertEquals(
                "https://artwork.example.com/streamed.jpg",
                presentationAfterGc.first().artworkLocation,
            )
        }

    @Test
    fun `actively used transient recordings in active queue retained cache or pending work survive GC even when expired`() =
        runBlocking {
            val now = Instant.parse("2026-08-19T00:00:00Z")
            val capturedAt = now.minus(Duration.ofDays(65))
            val playing = ingest(201, capturedAt)
            val cached = ingest(202, capturedAt)
            val pending = ingest(203, capturedAt)
            val unreferenced = ingest(204, capturedAt)

            val result =
                maintenance.collectExpiredTransient(
                    R16CatalogueGcRequest(
                        now = now,
                        protections =
                            R16CatalogueGcProtections(
                                activeQueue = setOf(playing),
                                retainedCache = setOf(cached),
                                pendingWork = setOf(pending),
                            ),
                    )
                )

            // Only unreferenced expired transient recording is collected; protected ones are
            // skipped
            assertEquals(listOf(unreferenced), result.deletedRecordingIds)
            assertEquals(3, result.skippedProtectedCount)
            assertEquals(3, tableCount("recording"))
        }

    private suspend fun ingest(index: Int, capturedAt: Instant): RecordingId {
        val suffix = index.toString().padStart(12, '0')
        val recordingId = RecordingId("00000000-0000-0000-0000-$suffix")
        val sourceKey = SourceKey(ProviderId("youtube"), SourceItemType.VIDEO, "video-$index")
        ingestion.transaction {
            persist(
                R16IngestionCommand(
                    observation =
                        R16SourceObservation(
                            sourceKey = sourceKey,
                            sourceKind = SourceKind.YOUTUBE,
                            title = "Fixture $index",
                            artistNames = listOf("Artist $index"),
                            releaseTitle = null,
                            durationMs = 180_000,
                            version = RecordingVersion(VersionKind.ORIGINAL),
                            explicitness = Explicitness.UNKNOWN,
                            artwork = emptyList(),
                            externalIdentifiers =
                                setOf(
                                    ExternalIdentifier(
                                        ExternalIdentifierKind.ISRC,
                                        "ISRC${index.toString().padStart(8, '0')}",
                                    )
                                ),
                            originalUrl = "https://www.youtube.com/watch?v=video-$index",
                            asset = null,
                            capturedAt = capturedAt,
                        ),
                    recordingId = recordingId,
                    newRecording =
                        RecordingDraft(
                            title = "Fixture $index",
                            primaryArtist = "Artist $index",
                            durationMs = 180_000,
                            version = RecordingVersion(VersionKind.ORIGINAL),
                        ),
                    resolution = "NEW_RECORDING",
                    identityEvidence = null,
                    assetWriteMode = R16AssetWriteMode.NONE,
                    exactManagedAssetId = null,
                    reviewCandidateIds = emptySet(),
                )
            )
        }
        return recordingId
    }

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql.trimIndent())
    }

    private fun tableCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
