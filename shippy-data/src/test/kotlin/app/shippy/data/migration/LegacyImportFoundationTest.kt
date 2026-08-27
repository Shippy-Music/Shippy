/*
 * Copyright (c) 2026 Auxio Project
 * LegacyImportFoundationTest.kt is part of Auxio.
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
package app.shippy.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.identity.RecordingId
import app.shippy.data.backup.R16PortableSettingsProvider
import app.shippy.data.backup.R16PortableSettingsSnapshot
import app.shippy.data.backup.R16SanitizedLastFmConfig
import app.shippy.data.backup.R16SanitizedLastFmConfigProvider
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.migration.pipeline.R16MigrationCallbackPageResult
import app.shippy.data.migration.pipeline.R16MusikrDevicePlaylistPageRequest
import app.shippy.data.migration.pipeline.R16MusikrLocalReindexPageRequest
import app.shippy.data.migration.pipeline.R16MusikrMigrationBridge
import app.shippy.data.migration.precutover.R16MigrationFolderCheck
import app.shippy.data.migration.precutover.R16MigrationFolderInspection
import app.shippy.data.migration.precutover.R16MigrationFolderInspectionProvider
import app.shippy.data.migration.precutover.R16MigrationPreCutoverComposition
import app.shippy.data.migration.precutover.R16MigrationPreCutoverOutcome
import app.shippy.data.migration.precutover.R16MigrationPreCutoverPhase
import app.shippy.data.migration.precutover.R16MigrationPreCutoverRuntime
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyImportFoundationTest {
    private lateinit var legacyFile: File
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        legacyFile = File.createTempFile("legacy-${UUID.randomUUID()}-", ".db")
        database =
            Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
        legacyFile.delete()
    }

    @Test
    fun `legacy IDs are deterministic RFC 4122 UUIDv5 values`() {
        val first = LegacyIdMapper.recording("track-1").value
        val repeated = LegacyIdMapper.recording("track-1").value
        val otherDomain = LegacyIdMapper.playlist("track-1").value

        assertEquals(first, repeated)
        assertNotEquals(first, otherDomain)
        assertEquals(5, UUID.fromString(first).version())
        assertEquals(2, UUID.fromString(first).variant())
    }

    @Test
    fun `legacy reader opens v10 read-only and pages stable track keys`() {
        createLegacyDatabase()

        LegacyDatabaseReader.openReadOnly(legacyFile).use { reader ->
            val schema = reader.schemaSnapshot()
            assertTrue(schema.compatible)
            assertEquals(13, schema.rowCounts.size)
            assertEquals(2L, schema.rowCounts.getValue("canonical_track"))

            val firstPage = reader.canonicalTracks(afterTrackId = null, limit = 1)
            val secondPage =
                reader.canonicalTracks(afterTrackId = firstPage.single().trackId, limit = 1)
            assertEquals("track-a", firstPage.single().trackId)
            assertEquals("track-b", secondPage.single().trackId)
            assertEquals("Album", secondPage.single().album)

            val firstCandidate =
                reader.canonicalCandidates(afterTrackId = null, afterCandidateId = null, limit = 1)
            val secondCandidate =
                reader.canonicalCandidates(
                    afterTrackId = firstCandidate.single().trackId,
                    afterCandidateId = firstCandidate.single().candidateId,
                    limit = 1,
                )
            assertEquals("candidate-a", firstCandidate.single().candidateId)
            assertEquals("track-b", secondCandidate.single().trackId)
            assertEquals("candidate-b", secondCandidate.single().candidateId)

            val relationships = reader.libraryRelationships(afterTrackId = null, limit = 2)
            assertEquals(listOf("track-a", "track-b"), relationships.map { it.trackId })
            assertTrue(relationships.first().liked)
            assertTrue(relationships.last().downloaded)

            val playlists =
                reader.userPlaylists(afterPosition = null, afterPlaylistId = null, limit = 2)
            assertEquals(listOf("playlist-a", "playlist-b"), playlists.map { it.playlistId })
            assertEquals(listOf(0L, 1L), playlists.map { it.orderOrdinal })

            val firstMembership =
                reader.playlistMemberships(
                    afterPlaylistId = null,
                    afterPosition = null,
                    afterTrackId = null,
                    limit = 1,
                )
            val secondMembership =
                reader.playlistMemberships(
                    afterPlaylistId = firstMembership.single().playlistId,
                    afterPosition = firstMembership.single().position,
                    afterTrackId = firstMembership.single().trackId,
                    limit = 1,
                )
            assertEquals("track-b", firstMembership.single().trackId)
            assertEquals("track-a", secondMembership.single().trackId)
            assertEquals(1L, secondMembership.single().orderOrdinal)

            val download = reader.downloadJobs(afterJobId = null, limit = 1).single()
            assertEquals("job-a", download.jobId)
            assertEquals("candidate-a", download.requestedCandidateId)
            assertEquals("provider-item", download.requestedSourceItemId)

            val lyrics =
                reader.lyrics(afterTrackId = null, afterFingerprint = null, limit = 1).single()
            assertEquals("track-a", lyrics.trackId)
            assertTrue(lyrics.plainLyrics?.contains("words") == true)

            val outbox =
                reader.lastFmOutbox(afterQueuedAtEpochMs = null, afterId = null, limit = 1).single()
            assertEquals("queue-item:queue-1", outbox.id)
            assertEquals("track-a", outbox.trackId)

            val checkpoint = checkNotNull(reader.playbackCheckpoint("active"))
            assertEquals("TRACK", checkpoint.repeatMode)
            assertEquals("queue-1", checkpoint.items.single().queueItemId)

            val crewCheckpoint = checkNotNull(reader.crewActiveCheckpoint())
            assertEquals(3, crewCheckpoint.protocolVersion)
            assertTrue(crewCheckpoint.payloadChecksumValid)

            val firstSaved = reader.savedProviderEntities(null, null, null, limit = 1).single()
            val secondSaved =
                reader
                    .savedProviderEntities(
                        firstSaved.providerId,
                        firstSaved.entityType,
                        firstSaved.sourceItemId,
                        limit = 1,
                    )
                    .single()
            assertEquals(
                listOf("album-a", "playlist-b"),
                listOf(firstSaved, secondSaved).map { it.sourceItemId },
            )
        }
    }

    @Test
    fun `import plan is ordered resumable and cancellable before cutover`() {
        assertEquals(LegacyImportPhase.PREFLIGHT, R15ToR16ImportPlan.nextAfter(null))
        assertEquals(
            LegacyImportPhase.CANONICAL_TRACKS,
            R15ToR16ImportPlan.nextAfter(LegacyImportPhase.PREFLIGHT),
        )
        assertEquals(null, R15ToR16ImportPlan.nextAfter(LegacyImportPhase.CUTOVER))
        assertTrue(R15ToR16ImportPlan.mayCancelSafelyBefore(LegacyImportPhase.VERIFY))
        assertFalse(R15ToR16ImportPlan.mayCancelSafelyBefore(LegacyImportPhase.CUTOVER))
        LegacyImportCheckpoint("migration-1", LegacyImportPhase.CANONICAL_TRACKS, "track-a")
    }

    @Test
    fun `M1 imports one ordered page with provenance search and audit checkpoint`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-1")
            val rows =
                listOf(
                    legacyTrack("track-a", "Alpha", "5:First6:Second", "Album"),
                    legacyTrack("track-b", "Beta", "6:Artist", null),
                )

            val result =
                LegacyCanonicalTrackImporter(database).importPage("migration-1", rows, 1_000)

            assertEquals(2, result.importedCount)
            assertEquals("track-b", result.lastTrackId)
            val recordingId = LegacyIdMapper.recording("track-a").value
            val recording = checkNotNull(database.recordingDao().get(recordingId))
            assertEquals("Alpha", recording.canonicalTitle)
            assertEquals(2, database.recordingDao().artistCredits(recordingId).size)
            assertEquals(
                "Album",
                database
                    .legacyImportDao()
                    .release(checkNotNull(recording.preferredReleaseId))
                    ?.canonicalTitle,
            )
            assertEquals(7, database.legacyImportDao().provenance(recordingId).size)
            assertEquals(listOf(recordingId), database.searchDao().searchRecordingIds("Alpha*", 10))
            val audit = checkNotNull(database.migrationAuditDao().get("migration-1"))
            assertTrue(checkNotNull(audit.targetCountsJson).contains("track-b"))
            LegacyCanonicalTrackImporter(database).importPage("migration-1", rows, 1_000)
            assertEquals(2L, database.legacyImportDao().recordingCount())
        }

    @Test
    fun `M1 page rolls back when audit evidence cannot advance`() =
        kotlinx.coroutines.runBlocking {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    LegacyCanonicalTrackImporter(database)
                        .importPage(
                            "missing-audit",
                            listOf(legacyTrack("track-a", "Alpha", "6:Artist", null)),
                            1_000,
                        )
                }
            }
            assertEquals(
                null,
                database.recordingDao().get(LegacyIdMapper.recording("track-a").value),
            )
            Unit
        }

    @Test
    fun `M2 imports exact sources verified assets and drops temporary Crew candidates`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-2")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-2",
                    listOf(legacyTrack("track-a", "Alpha", "6:Artist", null)),
                    1_000,
                )
            val rows =
                listOf(
                    legacyCandidate(
                        candidateId = "candidate-a",
                        kind = "PROVIDER",
                        sourceId = "provider",
                        sourceItemId = "provider-item",
                        providerId = "provider",
                        locator = "https://expired.example/audio",
                    ),
                    legacyCandidate(
                        candidateId = "candidate-b",
                        kind = "LOCAL",
                        sourceId = "device-local",
                        sourceItemId = "local-item",
                        locator = "content://legacy/local-item",
                        contentLength = 321,
                    ),
                    legacyCandidate(
                        candidateId = "candidate-c",
                        kind = "CREW_TEMPORARY",
                        sourceId = "crew",
                        sourceItemId = "crew-item",
                        locator = "file:///private/crew-temp",
                    ),
                )
            val verifier = LegacyAssetVerifier { row ->
                if (row.kind == "LOCAL") {
                    VerifiedLegacyAsset(
                        locationType = "CONTENT_URI",
                        location = "content://verified/local-item",
                        displayName = "local-item.mp3",
                        contentLength = 321,
                    )
                } else {
                    null
                }
            }
            val importer = LegacyCandidateImporter(database, verifier)

            val result = importer.importPage("migration-2", rows, 2_000)

            assertEquals(2, result.importedSourceCount)
            assertEquals(1, result.importedAssetCount)
            assertEquals("candidate-c", result.lastCandidateId)
            assertEquals(2L, database.legacyImportDao().sourceCount())
            assertEquals(1L, database.legacyImportDao().assetCount())
            val provider =
                checkNotNull(database.sourceDao().exact("provider", "RECORDING", "provider-item"))
            assertEquals(null, provider.originalUrl)
            val providerObservation =
                checkNotNull(
                    database
                        .legacyImportDao()
                        .observation(
                            LegacyIdMapper.candidateObservation("track-a", "candidate-a").value
                        )
                )
            assertFalse(checkNotNull(providerObservation.extrasJson).contains("expired.example"))
            val local =
                checkNotNull(database.sourceDao().exact("local-file", "LOCAL_FILE", "local-item"))
            val asset =
                checkNotNull(
                    database.assetDao().get(LegacyIdMapper.asset("track-a", "candidate-b").value)
                )
            assertEquals(local.sourceReferenceId, asset.sourceReferenceId)
            assertEquals("content://verified/local-item", asset.location)
            assertTrue(result.warnings.any { it.contains("temporary Crew") })

            importer.importPage("migration-2", rows, 2_000)
            assertEquals(2L, database.legacyImportDao().sourceCount())
            assertEquals(1L, database.legacyImportDao().assetCount())
        }

    @Test
    fun `M3 and M4 preserve Library ownership playlists and sparse order`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-3")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-3",
                    listOf(
                        legacyTrack("track-a", "Alpha", "6:Artist", null),
                        legacyTrack("track-b", "Beta", "6:Artist", null),
                    ),
                    1_000,
                )

            val libraryResult =
                LegacyLibraryRelationshipImporter(database)
                    .importPage(
                        "migration-3",
                        listOf(
                            LegacyLibraryRelationshipRow(
                                "track-a",
                                liked = true,
                                downloaded = false,
                            ),
                            LegacyLibraryRelationshipRow(
                                "track-b",
                                liked = false,
                                downloaded = true,
                            ),
                        ),
                        2_000,
                    )

            assertEquals(2, libraryResult.importedCount)
            assertEquals(1, libraryResult.pendingDownloadVerificationCount)
            val recordingA = LegacyIdMapper.recording("track-a").value
            val recordingB = LegacyIdMapper.recording("track-b").value
            assertTrue(
                checkNotNull(database.legacyImportDao().libraryRelationship(recordingA)).liked
            )
            assertEquals(
                "DURABLE",
                checkNotNull(database.recordingDao().get(recordingB)).retentionKind,
            )

            val importer = LegacyPlaylistImporter(database)
            val playlistRows =
                listOf(
                    LegacyUserPlaylistRow(
                        playlistId = "playlist-a",
                        name = "Mix",
                        pinned = true,
                        position = 0,
                        artworkUri = "content://art/mix",
                        orderOrdinal = 0,
                    ),
                    LegacyUserPlaylistRow(
                        playlistId = "playlist-b",
                        name = "  ",
                        pinned = false,
                        position = 0,
                        artworkUri = null,
                        orderOrdinal = 1,
                    ),
                )
            val playlistResult = importer.importPlaylistPage("migration-3", playlistRows, 3_000)
            val playlistA = LegacyIdMapper.playlist("playlist-a").value
            val playlistB = LegacyIdMapper.playlist("playlist-b").value
            assertEquals(2, playlistResult.importedCount)
            val importedPlaylistA = checkNotNull(database.legacyImportDao().playlist(playlistA))
            assertEquals("Mix", importedPlaylistA.name)
            assertEquals("LEGACY_SHIPPY", importedPlaylistA.originKind)
            assertEquals("playlist-a", importedPlaylistA.originKey)
            assertEquals(
                "Untitled",
                checkNotNull(database.legacyImportDao().playlist(playlistB)).name,
            )
            assertEquals(
                1_024L,
                checkNotNull(database.legacyImportDao().libraryLayout("PLAYLIST", playlistA))
                    .orderKey,
            )

            val membershipRows =
                listOf(
                    LegacyPlaylistMembershipRow("track-a", "playlist-a", 0, 0),
                    LegacyPlaylistMembershipRow("track-b", "playlist-a", 0, 1),
                    LegacyPlaylistMembershipRow("track-a", "playlist-b", 0, 0),
                )
            importer.importMembershipPage("migration-3", membershipRows, 4_000)
            assertEquals(
                listOf(recordingA, recordingB),
                database.playlistDao().entries(playlistA).map { it.recordingId },
            )
            assertEquals(
                listOf(1_024L, 2_048L),
                database.playlistDao().entries(playlistA).map { it.orderKey },
            )

            importer.importPlaylistPage("migration-3", playlistRows, 3_000)
            importer.importMembershipPage("migration-3", membershipRows, 4_000)
            assertEquals(2L, database.legacyImportDao().playlistCount())
            assertEquals(3L, database.legacyImportDao().playlistEntryCount())
        }

    @Test
    fun `M5 imports ordered duplicate Musikr occurrences idempotently and removes stale origins`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-5")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-5",
                    listOf(
                        legacyTrack("track-a", "Alpha", "6:Artist", null),
                        legacyTrack("track-b", "Beta", "6:Artist", null),
                    ),
                    1_000,
                )
            val recordingA = RecordingId(LegacyIdMapper.recording("track-a").value)
            val recordingB = RecordingId(LegacyIdMapper.recording("track-b").value)
            LegacyPlaylistImporter(database)
                .importPlaylistPage(
                    "migration-5",
                    listOf(
                        LegacyUserPlaylistRow(
                            playlistId = "musikr-legacy",
                            name = "M4-owned",
                            pinned = true,
                            position = 0,
                            artworkUri = null,
                            orderOrdinal = 0,
                        )
                    ),
                    1_500,
                )
            val importer = RoomR16DevicePlaylistImportRepository(database)
            val firstSnapshot = setOf("musikr-a", "musikr-b", "musikr-legacy")
            val firstPage =
                listOf(
                    R16DevicePlaylistImport(
                        originKey = "musikr-a",
                        name = "Device Mix",
                        orderedRecordingIds = listOf(recordingA, recordingB, recordingA),
                    ),
                    R16DevicePlaylistImport(
                        originKey = "musikr-b",
                        name = " ",
                        orderedRecordingIds = listOf(recordingB),
                    ),
                    R16DevicePlaylistImport(
                        originKey = "musikr-legacy",
                        name = "Device name must not replace M4",
                        orderedRecordingIds = emptyList(),
                    ),
                )

            val first = importer.importPage("migration-5", firstSnapshot, 0, firstPage, 2_000)
            val playlistA =
                checkNotNull(
                    database.legacyImportDao().playlistByOrigin("MUSIKR_DEVICE", "musikr-a")
                )
            assertEquals(2, first.importedPlaylistCount)
            assertEquals(1, first.reusedPlaylistCount)
            assertEquals(4, first.importedEntryCount)
            assertTrue(first.complete)
            assertEquals("MUSIKR_DEVICE", playlistA.originKind)
            assertEquals(
                listOf(recordingA.value, recordingB.value, recordingA.value),
                database.playlistDao().entries(playlistA.playlistId).map { it.recordingId },
            )
            assertEquals(
                listOf(1_024L, 2_048L, 3_072L),
                database.playlistDao().entries(playlistA.playlistId).map { it.orderKey },
            )
            val firstEntryAddedAt =
                database.playlistDao().entries(playlistA.playlistId).map { it.addedAtEpochMs }
            assertEquals("DURABLE", database.recordingDao().get(recordingA.value)?.retentionKind)
            assertEquals(
                null,
                database.legacyImportDao().playlistByOrigin("MUSIKR_DEVICE", "musikr-legacy"),
            )
            assertEquals(
                "M4-owned",
                database.legacyImportDao().playlistByOrigin("LEGACY_SHIPPY", "musikr-legacy")?.name,
            )

            val repeated = importer.importPage("migration-5", firstSnapshot, 0, firstPage, 3_000)
            assertEquals(0, repeated.importedPlaylistCount)
            assertEquals(3, repeated.reusedPlaylistCount)
            assertEquals(2L, database.legacyImportDao().playlistCountByOrigin("MUSIKR_DEVICE"))
            assertEquals(4L, database.legacyImportDao().playlistEntryCountByOrigin("MUSIKR_DEVICE"))
            assertEquals(
                firstEntryAddedAt,
                database.playlistDao().entries(playlistA.playlistId).map { it.addedAtEpochMs },
            )

            val retainedOnly = firstPage.take(1)
            importer.importPage("migration-5", setOf("musikr-a"), 0, retainedOnly, 4_000)
            assertEquals(1L, database.legacyImportDao().playlistCountByOrigin("MUSIKR_DEVICE"))
            assertEquals(
                null,
                database.legacyImportDao().playlistByOrigin("MUSIKR_DEVICE", "musikr-b"),
            )
            assertEquals(
                "M4-owned",
                database.legacyImportDao().playlistByOrigin("LEGACY_SHIPPY", "musikr-legacy")?.name,
            )
        }

    @Test
    fun `M6 verifies managed downloads reuses exact assets and repairs stale availability`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-6")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-6",
                    listOf(legacyTrack("track-a", "Alpha", "6:Artist", null)),
                    1_000,
                )
            LegacyCandidateImporter(database, LegacyAssetVerifier { null })
                .importPage(
                    "migration-6",
                    listOf(
                        legacyCandidate(
                            candidateId = "candidate-a",
                            kind = "PROVIDER",
                            sourceId = "provider",
                            sourceItemId = "provider-item",
                            providerId = "provider",
                        )
                    ),
                    2_000,
                )
            val rows =
                listOf(
                    legacyDownload("job-a", artifactUri = "content://legacy/download-a"),
                    legacyDownload("job-b", artifactUri = "content://legacy/download-b"),
                    legacyDownload("job-c", artifactUri = null),
                )
            var allowVerification = true
            val importer =
                LegacyDownloadImporter(
                    database,
                    LegacyDownloadArtifactVerifier { row ->
                        if (row.artifactUri == null || !allowVerification) {
                            null
                        } else {
                            VerifiedLegacyAsset(
                                locationType = "CONTENT_URI",
                                location = "content://verified/download",
                                displayName = "download.mp3",
                                contentLength = 321,
                                contentChecksum = "sha256:verified",
                            )
                        }
                    },
                )

            val result = importer.importPage("migration-6", rows, 3_000)

            assertEquals(3, result.importedCount)
            assertEquals(2, result.verifiedArtifactCount)
            assertEquals(3L, database.legacyImportDao().downloadJobCount())
            assertEquals(2L, database.downloadDao().availablePublishedCount())
            assertEquals(1L, database.legacyImportDao().assetCount())
            val jobA = checkNotNull(database.downloadDao().get(LegacyIdMapper.downloadJob("job-a")))
            val jobB = checkNotNull(database.downloadDao().get(LegacyIdMapper.downloadJob("job-b")))
            val jobC = checkNotNull(database.downloadDao().get(LegacyIdMapper.downloadJob("job-c")))
            assertEquals("AVAILABLE", jobA.state)
            assertEquals(jobA.publishedAssetId, jobB.publishedAssetId)
            assertEquals("FAILED_RETRYABLE", jobC.state)
            assertEquals("LEGACY_ARTIFACT_UNAVAILABLE", jobC.failureKind)
            assertEquals(null, jobA.pendingLocation)
            assertFalse(jobA.displayFallbackJson.contains("content://legacy"))

            val publishedAssetId = checkNotNull(jobA.publishedAssetId)
            allowVerification = false
            importer.importPage("migration-6", rows, 3_000)
            assertEquals(3L, database.legacyImportDao().downloadJobCount())
            assertEquals(1L, database.legacyImportDao().assetCount())
            assertEquals(2L, database.downloadDao().availablePublishedCount())
            val retriedJobA = checkNotNull(database.downloadDao().get(jobA.jobId))
            assertEquals("AVAILABLE", retriedJobA.state)
            assertEquals(publishedAssetId, retriedJobA.publishedAssetId)
        }

    @Test
    fun `M7 and M8 preserve valid lyrics and duplicate-safe Last fm FIFO`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-8")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-8",
                    listOf(legacyTrack("track-a", "Alpha", "6:Artist", null)),
                    1_000,
                )
            val validFingerprint = "alpha\u001Fartist\u001F\u001F120"
            val lyricsRows =
                listOf(
                    legacyLyrics(fingerprint = validFingerprint),
                    legacyLyrics(fingerprint = "bad-fingerprint"),
                )

            val lyricsResult =
                LegacyLyricsImporter(database).importPage("migration-8", lyricsRows, 2_000)

            assertEquals(1, lyricsResult.importedCount)
            assertEquals(1, lyricsResult.skippedCount)
            val recordingA = LegacyIdMapper.recording("track-a").value
            val lyrics =
                checkNotNull(database.lyricsDao().exact(recordingA, validFingerprint, "lrclib"))
            assertEquals(2_000L, lyrics.expiresAtEpochMs)

            val outboxRows =
                listOf(
                    legacyOutbox(
                        id = "queue-item:queue-a",
                        queuedAtEpochMs = 1_000,
                        trackId = "track-a",
                    ),
                    legacyOutbox(id = "queue-item:orphan", queuedAtEpochMs = 2_000, trackId = null),
                    legacyOutbox(
                        id = "queue-item:invalid",
                        queuedAtEpochMs = 3_000,
                        trackId = null,
                        artist = " ",
                    ),
                )
            val outboxImporter = LegacyLastFmOutboxImporter(database)
            val outboxResult = outboxImporter.importPage("migration-8", outboxRows, 3_000)

            assertEquals(2, outboxResult.acceptedCount)
            assertEquals(1, outboxResult.skippedCount)
            assertEquals(2, database.lastFmOutboxDao().count())
            val oldest = database.lastFmOutboxDao().oldest(10)
            assertEquals(recordingA, oldest.first().recordingId)
            assertEquals(
                LegacyIdMapper.lastFmRecording("queue-item:orphan").value,
                oldest.last().recordingId,
            )
            assertFalse(oldest.first().chosenByUser)

            outboxImporter.importPage("migration-8", outboxRows, 3_000)
            assertEquals(2, database.lastFmOutboxDao().count())
        }

    @Test
    fun `M9 and M10 preserve source-neutral queue intent and exact saved source keys`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-10")
            LegacyCanonicalTrackImporter(database)
                .importPage(
                    "migration-10",
                    listOf(legacyTrack("track-a", "Alpha", "6:Artist", null)),
                    1_000,
                )
            val checkpointRow =
                LegacyPlaybackCheckpointRow(
                    slot = "active",
                    positionMs = 45_000,
                    repeatMode = "TRACK",
                    heapIndex = 1,
                    shuffledMapping = "2,0,1",
                    items =
                        listOf(
                            legacyCheckpointItem(0, "queue-a", "track-a"),
                            legacyCheckpointItem(1, "queue-missing", "track-missing"),
                            legacyCheckpointItem(2, "queue-b", "track-a"),
                        ),
                )

            val checkpointImporter = LegacyPlaybackCheckpointImporter(database)
            val checkpointResult =
                checkpointImporter.importCheckpoint("migration-10", checkpointRow, 2_000)

            assertEquals(2, checkpointResult.importedEntryCount)
            assertEquals(1, checkpointResult.skippedEntryCount)
            val stored = checkNotNull(database.playbackCheckpointDao().load("active"))
            val queueA = LegacyIdMapper.queueEntry("queue-a").value
            val queueB = LegacyIdMapper.queueEntry("queue-b").value
            assertEquals(listOf(queueA, queueB), stored.entries.map { it.queueEntryId })
            assertEquals(queueA, stored.checkpoint.currentQueueEntryId)
            assertEquals(0L, stored.checkpoint.positionMs)
            assertEquals("ONE", stored.checkpoint.repeatMode)
            assertTrue(stored.checkpoint.shuffleEnabled)
            assertEquals(
                org.json.JSONArray(listOf(queueB, queueA)).toString(),
                stored.checkpoint.traversalOrderJson,
            )
            assertEquals(64, stored.checkpoint.checksum.length)
            assertEquals(1, stored.entries.map { it.recordingId }.distinct().size)

            val savedRows =
                listOf(
                    legacySavedSource(
                        providerId = "jiosaavn",
                        entityType = "ALBUM",
                        sourceItemId = "album-a",
                        artwork = "https://img.example/album.jpg",
                        originalUrl = "https://music.example/album-a",
                    ),
                    legacySavedSource(
                        providerId = "youtube",
                        entityType = "PLAYLIST",
                        sourceItemId = "playlist-b",
                        artwork = "content://legacy/artwork",
                        originalUrl = "file:///legacy/private",
                    ),
                    legacySavedSource(
                        providerId = "z-provider",
                        entityType = "ALBUM",
                        sourceItemId = "invalid-title",
                        title = " ",
                    ),
                )
            val savedImporter = LegacySavedSourceImporter(database)
            val savedResult = savedImporter.importPage("migration-10", savedRows, 3_000)

            assertEquals(2, savedResult.importedCount)
            assertEquals(1, savedResult.skippedCount)
            val saved = database.savedSourceDao().library(10)
            assertEquals(2, saved.size)
            assertEquals("album-a", saved.first().sourceItemId)
            assertEquals(null, saved.last().artworkUrl)
            assertEquals(null, saved.last().originalUrl)

            checkpointImporter.importCheckpoint("migration-10", checkpointRow, 2_000)
            savedImporter.importPage("migration-10", savedRows, 3_000)
            assertEquals(2, database.playbackCheckpointDao().load("active")?.entries?.size)
            assertEquals(2, database.savedSourceDao().library(10).size)
            val verification =
                MigrationVerifier(database)
                    .verify(
                        migrationId = "migration-10",
                        expected = MigrationExpectedCounts(0, 0, 0, 2, 0, 2, 0),
                        completedPhases =
                            LegacyImportPhase.entries
                                .filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }
                                .toSet(),
                    )
            assertTrue(verification.passed)
        }

    @Test
    fun `M11 expires incompatible Crew checkpoint without copying payload`() =
        kotlinx.coroutines.runBlocking {
            createLegacyDatabase()
            val legacyCheckpoint =
                LegacyDatabaseReader.openReadOnly(legacyFile).use { it.crewActiveCheckpoint() }
            startAudit("migration-11")

            val result =
                LegacyCrewCheckpointDispositionRecorder(database)
                    .record("migration-11", legacyCheckpoint)

            assertEquals(LegacyCrewCheckpointDisposition.EXPIRE_INCOMPATIBLE, result.disposition)
            assertEquals(3, result.legacyProtocolVersion)
            assertTrue(result.requiresLegacyLeaseExpiry)
            val audit = checkNotNull(database.migrationAuditDao().get("migration-11"))
            assertTrue(audit.targetCountsJson?.contains("EXPIRE_INCOMPATIBLE") == true)
            assertFalse(audit.targetCountsJson?.contains("session-legacy") == true)
        }

    @Test
    fun `M13 blocks missing phases then verifies counts and invariants`() =
        kotlinx.coroutines.runBlocking {
            startAudit("migration-13")
            val expected = MigrationExpectedCounts(0, 0, 0, 0, 0, 0, 0)
            val verifier = MigrationVerifier(database)

            val blocked =
                verifier.verify(
                    migrationId = "migration-13",
                    expected = expected,
                    completedPhases =
                        setOf(LegacyImportPhase.PREFLIGHT, LegacyImportPhase.CANONICAL_TRACKS),
                )
            assertFalse(blocked.passed)
            assertTrue(blocked.issues.any { it.code == "MISSING_PHASE_M5" })
            assertTrue(blocked.issues.any { it.code == "MISSING_PHASE_M12" })

            val verified =
                verifier.verify(
                    migrationId = "migration-13",
                    expected = expected,
                    completedPhases =
                        LegacyImportPhase.entries
                            .filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }
                            .toSet(),
                )
            assertTrue(verified.passed)
            assertEquals(0L, verified.foreignKeyViolations)
            assertEquals(0L, verified.unresolvedReferences)
            assertEquals(
                "READY_TO_SWITCH",
                database.migrationAuditDao().get("migration-13")?.status,
            )
        }

    @Test
    fun `owner shaped v10 fixture resumes bounded import then verifies idempotently`() =
        kotlinx.coroutines.runBlocking {
            createLegacyDatabase()
            val bootstrap = File(legacyFile.parentFile, "${legacyFile.name}.bootstrap.json")
            val recoveryArchive = File(legacyFile.parentFile, "${legacyFile.name}.recovery.zip")

            val firstComposition = ownerFixtureComposition(bootstrap, recoveryArchive)
            val first =
                R16MigrationPreCutoverRuntime.forTesting(
                    bootstrapFile = bootstrap,
                    orchestrator = firstComposition.orchestrator(),
                    composition = firstComposition,
                )
            val partial = first.run("owner-fixture")
            assertEquals(R16MigrationPreCutoverOutcome.IMPORTING, partial.outcome)
            assertEquals(R16MigrationPreCutoverPhase.CANONICAL_TRACKS, partial.state.currentPhase)
            assertTrue(partial.state.checkpointPresent)
            first.close()

            val resumedComposition = ownerFixtureComposition(bootstrap, recoveryArchive)
            val resumed =
                R16MigrationPreCutoverRuntime.forTesting(
                    bootstrapFile = bootstrap,
                    orchestrator = resumedComposition.orchestrator(),
                    composition = resumedComposition,
                )
            try {
                var result = resumed.run("owner-fixture")
                while (result.outcome == R16MigrationPreCutoverOutcome.IMPORTING) {
                    result = resumed.run("owner-fixture")
                }

                val audit = database.migrationAuditDao().get("owner-fixture")
                assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, result.outcome)
                assertEquals(
                    R16MigrationPreCutoverPhase.entries.size,
                    result.state.completedPhaseCount,
                )
                assertTrue(recoveryArchive.isFile)
                assertEquals("READY_TO_SWITCH", audit?.status)
                assertEquals(2L, database.legacyImportDao().recordingCount())
                assertEquals(2L, database.legacyImportDao().playlistCount())
                assertEquals(2L, database.legacyImportDao().playlistEntryCount())
                assertEquals(1L, database.legacyImportDao().downloadJobCount())
                assertEquals(1, database.lastFmOutboxDao().count())
                assertEquals(1, database.playbackCheckpointDao().load("active")?.entries?.size)

                val idempotent = resumed.run("owner-fixture")
                assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, idempotent.outcome)
                assertEquals(2L, database.legacyImportDao().playlistEntryCount())
                assertEquals(1, database.lastFmOutboxDao().count())
            } finally {
                resumed.close()
            }
        }

    private fun ownerFixtureComposition(
        bootstrap: File,
        recoveryArchive: File,
    ): R16MigrationPreCutoverComposition =
        R16MigrationPreCutoverComposition(
            bootstrap = R16MigrationBootstrapStore(bootstrap),
            database = database,
            legacyDatabase = legacyFile,
            snapshotDirectory =
                File(legacyFile.parentFile, "${legacyFile.name}.snapshots").also {
                    check(it.mkdirs() || it.isDirectory)
                },
            recoveryArchive = recoveryArchive,
            folderInspectionProvider =
                R16MigrationFolderInspectionProvider {
                    R16MigrationFolderInspection(
                        checks =
                            listOf(
                                R16MigrationFolderCheck(
                                    label = "legacy-db",
                                    exists = true,
                                    readable = true,
                                    writable = false,
                                    required = true,
                                )
                            )
                    )
                },
            assetVerifier = LegacyAssetVerifier { null },
            artifactVerifier = LegacyDownloadArtifactVerifier { null },
            musikrBridge = OwnerFixtureMusikrBridge(database),
            importedAtEpochMs = { 2_000L },
            nowEpochMs = { 2_000L },
            pageSize = 1,
            portableSettingsProvider =
                R16PortableSettingsProvider { R16PortableSettingsSnapshot(emptyMap()) },
            sanitizedLastFmConfigProvider =
                R16SanitizedLastFmConfigProvider { R16SanitizedLastFmConfig(username = null) },
        )

    private fun createLegacyDatabase() {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { database ->
            database.version = LEGACY_SCHEMA_VERSION
            for (table in LEGACY_TABLES) {
                if (
                    table == "canonical_track" ||
                        table == "canonical_track_candidate" ||
                        table == "library_relationship" ||
                        table == "user_playlist" ||
                        table == "playlist_membership" ||
                        table == "download_job" ||
                        table == "download_candidate" ||
                        table == "lyrics_cache" ||
                        table == "crew_active_checkpoint" ||
                        table == "lastfm_scrobble_outbox" ||
                        table == "playback_checkpoint" ||
                        table == "playback_checkpoint_item" ||
                        table == "saved_provider_entity"
                ) {
                    continue
                }
                database.execSQL("CREATE TABLE `$table` (`id` TEXT)")
            }
            database.execSQL(
                """
                CREATE TABLE canonical_track (
                    trackId TEXT NOT NULL PRIMARY KEY,
                    realm TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artists TEXT NOT NULL,
                    album TEXT,
                    durationMs INTEGER,
                    versionLabel TEXT,
                    explicit INTEGER,
                    live INTEGER NOT NULL,
                    remix INTEGER NOT NULL,
                    artwork TEXT
                )
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO canonical_track
                (trackId, realm, title, artists, album, durationMs, versionLabel,
                 explicit, live, remix, artwork)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
                arrayOf<Any?>(
                    "track-b",
                    "PROVIDER",
                    "Beta",
                    "Artist",
                    "Album",
                    120_000,
                    null,
                    1,
                    0,
                    0,
                    null,
                ),
            )
            database.execSQL(
                """
                INSERT INTO canonical_track
                (trackId, realm, title, artists, album, durationMs, versionLabel,
                 explicit, live, remix, artwork)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
                arrayOf<Any?>(
                    "track-a",
                    "LOCAL",
                    "Alpha",
                    "Artist",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                ),
            )
            database.execSQL(
                """
                CREATE TABLE canonical_track_candidate (
                    trackId TEXT NOT NULL,
                    candidateId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    sourceItemId TEXT NOT NULL,
                    availability TEXT NOT NULL,
                    locator TEXT,
                    providerId TEXT,
                    mimeType TEXT,
                    container TEXT,
                    bitrateBps INTEGER,
                    contentLength INTEGER,
                    PRIMARY KEY(trackId, candidateId)
                )
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO canonical_track_candidate
                (trackId, candidateId, position, kind, sourceId, sourceItemId,
                 availability, locator, providerId, mimeType, container, bitrateBps, contentLength)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
                arrayOf<Any?>(
                    "track-b",
                    "candidate-b",
                    0,
                    "PROVIDER",
                    "provider",
                    "item-b",
                    "RESOLVABLE",
                    "https://expired.example/b",
                    "provider",
                    "audio/mp4",
                    "m4a",
                    128_000,
                    null,
                ),
            )
            database.execSQL(
                """
                INSERT INTO canonical_track_candidate
                (trackId, candidateId, position, kind, sourceId, sourceItemId,
                 availability, locator, providerId, mimeType, container, bitrateBps, contentLength)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
                arrayOf<Any?>(
                    "track-a",
                    "candidate-a",
                    0,
                    "LOCAL",
                    "device-local",
                    "item-a",
                    "AVAILABLE",
                    "content://legacy/item-a",
                    null,
                    "audio/mpeg",
                    "mp3",
                    192_000,
                    321,
                ),
            )
            createLegacyLibraryTables(database)
            createLegacyDownloadTables(database)
            createLegacyIntegrationTables(database)
        }
    }

    private fun createLegacyLibraryTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE library_relationship (
                trackId TEXT NOT NULL PRIMARY KEY,
                liked INTEGER NOT NULL,
                downloaded INTEGER NOT NULL
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO library_relationship (trackId, liked, downloaded)
            VALUES ('track-b', 0, 1), ('track-a', 1, 0)
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE user_playlist (
                playlistId TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                pinned INTEGER NOT NULL,
                position INTEGER NOT NULL,
                artworkUri TEXT
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO user_playlist (playlistId, name, pinned, position, artworkUri)
            VALUES ('playlist-b', 'B', 0, 0, NULL), ('playlist-a', 'A', 1, 0, NULL)
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE playlist_membership (
                trackId TEXT NOT NULL,
                playlistId TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(trackId, playlistId)
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO playlist_membership (trackId, playlistId, position)
            VALUES ('track-a', 'playlist-a', 1), ('track-b', 'playlist-a', 0)
            """
                .trimIndent()
        )
    }

    private fun createLegacyDownloadTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE download_job (
                jobId TEXT NOT NULL PRIMARY KEY,
                trackId TEXT NOT NULL,
                requestedCandidateId TEXT NOT NULL,
                trackRealm TEXT NOT NULL,
                title TEXT NOT NULL,
                artists TEXT NOT NULL,
                album TEXT,
                durationMs INTEGER,
                state TEXT NOT NULL,
                bytesTransferred INTEGER NOT NULL,
                expectedBytes INTEGER,
                failureCode TEXT,
                failureMessage TEXT,
                artifactUri TEXT,
                artifactLength INTEGER,
                artifactMimeType TEXT,
                artifactVerifiedAtEpochMs INTEGER,
                pendingUri TEXT,
                pendingDisplayName TEXT,
                pendingMimeType TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO download_job
            (jobId, trackId, requestedCandidateId, trackRealm, title, artists, album,
             durationMs, state, bytesTransferred, expectedBytes, failureCode,
             failureMessage, artifactUri, artifactLength, artifactMimeType,
             artifactVerifiedAtEpochMs, pendingUri, pendingDisplayName, pendingMimeType,
             createdAtEpochMs, updatedAtEpochMs)
            VALUES ('job-a', 'track-a', 'candidate-a', 'PROVIDER', 'Alpha', 'Artist', NULL,
                    120000, 'AVAILABLE', 321, 321, NULL, NULL,
                    'content://legacy/download', 321, 'audio/mpeg', 1000,
                    NULL, NULL, NULL, 100, 1000)
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE download_candidate (
                jobId TEXT NOT NULL,
                candidateId TEXT NOT NULL,
                kind TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                sourceItemId TEXT NOT NULL,
                providerId TEXT,
                PRIMARY KEY(jobId, candidateId)
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO download_candidate
            (jobId, candidateId, kind, sourceId, sourceItemId, providerId)
            VALUES ('job-a', 'candidate-a', 'PROVIDER', 'provider', 'provider-item', 'provider')
            """
                .trimIndent()
        )
    }

    private fun createLegacyIntegrationTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE crew_active_checkpoint (
                slot TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                protocolVersion INTEGER NOT NULL,
                coordinatorTerm INTEGER NOT NULL,
                eventSequence INTEGER NOT NULL,
                snapshotPayload BLOB NOT NULL,
                payloadSha256 BLOB NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
                .trimIndent()
        )
        val crewPayload = byteArrayOf(1, 2, 3, 4)
        database.execSQL(
            """
            INSERT INTO crew_active_checkpoint
            (slot, sessionId, protocolVersion, coordinatorTerm, eventSequence,
             snapshotPayload, payloadSha256, updatedAtEpochMs)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any>(
                "active",
                "session-legacy",
                3,
                1,
                5,
                crewPayload,
                java.security.MessageDigest.getInstance("SHA-256").digest(crewPayload),
                1_000,
            ),
        )
        database.execSQL(
            """
            CREATE TABLE lyrics_cache (
                trackId TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                titleKey TEXT NOT NULL,
                artistsKey TEXT NOT NULL,
                albumKey TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                sourceId TEXT NOT NULL,
                recordId INTEGER NOT NULL,
                instrumental INTEGER NOT NULL,
                plainLyrics TEXT,
                syncedLyrics TEXT,
                cachedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(trackId, fingerprint)
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO lyrics_cache
            (trackId, fingerprint, titleKey, artistsKey, albumKey, durationSeconds,
             sourceId, recordId, instrumental, plainLyrics, syncedLyrics, cachedAtEpochMs)
            VALUES ('track-a', 'alpha' || char(31) || 'artist' || char(31) || '' || char(31) || '120',
                    'alpha', 'artist', '', 120, 'lrclib', 1, 0, 'the words', NULL, 1000)
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE playback_checkpoint (
                slot TEXT NOT NULL PRIMARY KEY,
                positionMs INTEGER NOT NULL,
                repeatMode TEXT NOT NULL,
                heapIndex INTEGER NOT NULL,
                shuffledMapping TEXT NOT NULL
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO playback_checkpoint
            (slot, positionMs, repeatMode, heapIndex, shuffledMapping)
            VALUES ('active', 15000, 'TRACK', 0, '')
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE playback_checkpoint_item (
                slot TEXT NOT NULL,
                heapPosition INTEGER NOT NULL,
                queueItemId TEXT NOT NULL,
                trackId TEXT NOT NULL,
                contextId TEXT,
                contributorId TEXT,
                PRIMARY KEY(slot, heapPosition)
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO playback_checkpoint_item (slot, heapPosition, queueItemId, trackId)
            VALUES ('active', 0, 'queue-1', 'track-a')
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE saved_provider_entity (
                providerId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                sourceItemId TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                artwork TEXT,
                originalUrl TEXT,
                pinned INTEGER NOT NULL,
                savedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(providerId, entityType, sourceItemId)
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO saved_provider_entity
            (providerId, entityType, sourceItemId, title, subtitle, artwork, originalUrl,
             pinned, savedAtEpochMs)
            VALUES
            ('jiosaavn', 'ALBUM', 'album-a', 'Album A', 'Artist',
             'https://img.example/a.jpg', 'https://music.example/a', 1, 100),
            ('youtube', 'PLAYLIST', 'playlist-b', 'Playlist B', NULL,
             NULL, 'https://music.example/b', 0, 200)
            """
                .trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE lastfm_scrobble_outbox (
                id TEXT NOT NULL PRIMARY KEY,
                artist TEXT NOT NULL,
                track TEXT NOT NULL,
                album TEXT,
                durationSeconds INTEGER,
                startedAtEpochSeconds INTEGER NOT NULL,
                queuedAtEpochMs INTEGER NOT NULL
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO lastfm_scrobble_outbox
            (id, artist, track, album, durationSeconds, startedAtEpochSeconds, queuedAtEpochMs)
            VALUES ('queue-item:queue-1', 'Artist', 'Alpha', NULL, 120, 100, 1000)
            """
                .trimIndent()
        )
    }

    private suspend fun startAudit(migrationId: String) {
        database
            .migrationAuditDao()
            .start(
                MigrationAuditEntity(
                    migrationId = migrationId,
                    sourceVersion = 10,
                    targetVersion = ShippyR16Database.SCHEMA_VERSION,
                    startedAtEpochMs = 1,
                    completedAtEpochMs = null,
                    sourceCountsJson = "{}",
                    targetCountsJson = null,
                    warningsJson = "[]",
                    checksum = null,
                    status = "IMPORTING",
                )
            )
    }

    private fun legacyTrack(trackId: String, title: String, artists: String, album: String?) =
        LegacyCanonicalTrackRow(
            trackId = trackId,
            realm = "PROVIDER",
            title = title,
            artists = artists,
            album = album,
            durationMs = 120_000,
            versionLabel = null,
            explicit = null,
            live = false,
            remix = false,
            artwork = null,
        )

    private fun legacyCandidate(
        candidateId: String,
        kind: String,
        sourceId: String,
        sourceItemId: String,
        providerId: String? = null,
        locator: String? = null,
        contentLength: Long? = null,
    ) =
        LegacyCanonicalCandidateRow(
            trackId = "track-a",
            candidateId = candidateId,
            position = 0,
            kind = kind,
            sourceId = sourceId,
            sourceItemId = sourceItemId,
            availability = "AVAILABLE",
            locator = locator,
            providerId = providerId,
            mimeType = "audio/mpeg",
            container = "mp3",
            bitrateBps = 192_000,
            contentLength = contentLength,
        )

    private fun legacyDownload(jobId: String, artifactUri: String?) =
        LegacyDownloadJobRow(
            jobId = jobId,
            trackId = "track-a",
            requestedCandidateId = "candidate-a",
            trackRealm = "PROVIDER",
            title = "Alpha",
            artists = "6:Artist",
            album = null,
            durationMs = 120_000,
            state = "AVAILABLE",
            bytesTransferred = 321,
            expectedBytes = 321,
            failureCode = null,
            failureMessage = null,
            artifactUri = artifactUri,
            artifactLength = artifactUri?.let { 321 },
            artifactMimeType = "audio/mpeg",
            artifactVerifiedAtEpochMs = artifactUri?.let { 2_000 },
            pendingUri = null,
            pendingDisplayName = null,
            pendingMimeType = null,
            createdAtEpochMs = 100,
            updatedAtEpochMs = 2_000,
            requestedKind = "PROVIDER",
            requestedSourceId = "provider",
            requestedSourceItemId = "provider-item",
            requestedProviderId = "provider",
        )

    private fun legacyLyrics(fingerprint: String) =
        LegacyLyricsRow(
            trackId = "track-a",
            fingerprint = fingerprint,
            titleKey = "alpha",
            artistsKey = "artist",
            albumKey = "",
            durationSeconds = 120,
            sourceId = "lrclib",
            recordId = 1,
            instrumental = false,
            plainLyrics = "the words",
            syncedLyrics = null,
            cachedAtEpochMs = 1_000,
        )

    private fun legacyOutbox(
        id: String,
        queuedAtEpochMs: Long,
        trackId: String?,
        artist: String = "Artist",
    ) =
        LegacyLastFmOutboxRow(
            id = id,
            artist = artist,
            track = "Alpha",
            album = null,
            durationSeconds = 120,
            startedAtEpochSeconds = 100,
            queuedAtEpochMs = queuedAtEpochMs,
            trackId = trackId,
        )

    private fun legacyCheckpointItem(heapPosition: Int, queueItemId: String, trackId: String) =
        LegacyPlaybackCheckpointItemRow(
            heapPosition = heapPosition,
            queueItemId = queueItemId,
            trackId = trackId,
            contextId = "playlist:legacy",
            contributorId = null,
        )

    private fun legacySavedSource(
        providerId: String,
        entityType: String,
        sourceItemId: String,
        title: String = "Saved title",
        artwork: String? = null,
        originalUrl: String? = null,
    ) =
        LegacySavedProviderEntityRow(
            providerId = providerId,
            entityType = entityType,
            sourceItemId = sourceItemId,
            title = title,
            subtitle = "Saved subtitle",
            artwork = artwork,
            originalUrl = originalUrl,
            pinned = providerId == "jiosaavn",
            savedAtEpochMs = 500,
        )

    private class OwnerFixtureMusikrBridge(database: ShippyR16Database) : R16MusikrMigrationBridge {
        private val devicePlaylists = RoomR16DevicePlaylistImportRepository(database)

        override suspend fun importDevicePlaylistPage(
            request: R16MusikrDevicePlaylistPageRequest
        ): R16MigrationCallbackPageResult {
            check(request.lastStableKey == null)
            val page =
                devicePlaylists.importPage(
                    migrationId = request.migrationId,
                    snapshotOriginKeys = emptySet(),
                    startOrdinal = 0,
                    playlists = emptyList(),
                    importedAtEpochMs = request.importedAtEpochMs,
                )
            return R16MigrationCallbackPageResult(page.complete, null)
        }

        override suspend fun reindexLocalPage(
            request: R16MusikrLocalReindexPageRequest
        ): R16MigrationCallbackPageResult = R16MigrationCallbackPageResult(true, null)
    }

    private companion object {
        val LEGACY_TABLES =
            setOf(
                "library_relationship",
                "user_playlist",
                "playlist_membership",
                "download_job",
                "download_candidate",
                "lyrics_cache",
                "crew_active_checkpoint",
                "canonical_track",
                "canonical_track_candidate",
                "lastfm_scrobble_outbox",
                "playback_checkpoint",
                "playback_checkpoint_item",
                "saved_provider_entity",
            )
    }
}
