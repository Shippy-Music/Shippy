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

    private fun createLegacyDatabase() {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { database ->
            database.version = LEGACY_SCHEMA_VERSION
            for (table in LEGACY_TABLES) {
                if (table == "canonical_track" || table == "canonical_track_candidate") continue
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
        }
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
