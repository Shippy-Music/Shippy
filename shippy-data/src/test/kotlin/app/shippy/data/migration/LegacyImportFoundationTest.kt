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
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
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
        legacyFile = File(context.cacheDir, "legacy-${UUID.randomUUID()}.db")
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
            assertEquals("Mix", checkNotNull(database.legacyImportDao().playlist(playlistA)).name)
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
            val importer =
                LegacyDownloadImporter(
                    database,
                    LegacyDownloadArtifactVerifier { row ->
                        if (row.artifactUri == null) {
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

            importer.importPage("migration-6", rows, 3_000)
            assertEquals(3L, database.legacyImportDao().downloadJobCount())
            assertEquals(1L, database.legacyImportDao().assetCount())
        }

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
                        table == "download_candidate"
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

    private suspend fun startAudit(migrationId: String) {
        database
            .migrationAuditDao()
            .start(
                MigrationAuditEntity(
                    migrationId = migrationId,
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
