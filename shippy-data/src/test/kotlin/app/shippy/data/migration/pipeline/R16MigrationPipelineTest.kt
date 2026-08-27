/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPipelineTest.kt is part of Auxio.
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
package app.shippy.data.migration.pipeline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.migration.LegacyAssetVerifier
import app.shippy.data.migration.LegacyDatabaseReader
import app.shippy.data.migration.LegacyDownloadArtifactVerifier
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.orchestration.R16MigrationPhaseContext
import app.shippy.data.migration.orchestration.R16MigrationPhaseHandler
import app.shippy.data.migration.orchestration.R16MigrationPhaseResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MigrationPipelineTest {
    private lateinit var legacyFile: File
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        legacyFile = File.createTempFile("pipeline-legacy-${UUID.randomUUID()}-", ".db")
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
    fun `M1 retries the same page then resumes in stable reader order`() = runBlocking {
        createLegacyFixture()
        startAudit("m1")
        LegacyDatabaseReader.openReadOnly(legacyFile).use { reader ->
            val handler =
                factory(reader, pageSize = 1).handlers().handler(LegacyImportPhase.CANONICAL_TRACKS)
            val first = run(handler, LegacyImportPhase.CANONICAL_TRACKS, "m1")
            val retry = run(handler, LegacyImportPhase.CANONICAL_TRACKS, "m1")
            val second = run(handler, LegacyImportPhase.CANONICAL_TRACKS, "m1", first.lastStableKey)
            val terminal =
                run(handler, LegacyImportPhase.CANONICAL_TRACKS, "m1", second.lastStableKey)

            assertFalse(first.complete)
            assertEquals(first.lastStableKey, retry.lastStableKey)
            assertFalse(second.complete)
            assertTrue(terminal.complete)
            assertNull(terminal.lastStableKey)
            assertEquals(2, database.legacyImportDao().recordingCount())
            assertTrue(database.migrationAuditDao().get("m1")!!.targetCountsJson!!.contains("M1"))
        }
    }

    @Test
    fun `M4 checkpoint moves from playlists to memberships without skipping entries`() =
        runBlocking {
            createLegacyFixture()
            startAudit("m4")
            LegacyDatabaseReader.openReadOnly(legacyFile).use { reader ->
                val handlers = factory(reader, pageSize = 2).handlers()
                drain(
                    handlers.handler(LegacyImportPhase.CANONICAL_TRACKS),
                    LegacyImportPhase.CANONICAL_TRACKS,
                    "m4",
                )
                val handler = handlers.handler(LegacyImportPhase.USER_PLAYLISTS)
                val playlists = run(handler, LegacyImportPhase.USER_PLAYLISTS, "m4")
                val memberships =
                    run(handler, LegacyImportPhase.USER_PLAYLISTS, "m4", playlists.lastStableKey)
                val terminal =
                    run(handler, LegacyImportPhase.USER_PLAYLISTS, "m4", memberships.lastStableKey)

                assertFalse(playlists.complete)
                assertEquals(
                    R16PlaylistCheckpoint.Section.MEMBERSHIPS,
                    R16MigrationCheckpointCodec.decodePlaylist(playlists.lastStableKey!!).section,
                )
                assertFalse(memberships.complete)
                val membershipCursor =
                    R16MigrationCheckpointCodec.decodePlaylist(memberships.lastStableKey!!)
                assertEquals(R16PlaylistCheckpoint.Section.MEMBERSHIPS, membershipCursor.section)
                assertEquals("playlist-a", membershipCursor.playlistId)
                assertEquals("track-a", membershipCursor.trackId)
                assertTrue(terminal.complete)
                assertEquals(1, database.legacyImportDao().playlistCount())
                assertEquals(2, database.legacyImportDao().playlistEntryCount())
            }
        }

    @Test
    fun `M5 and M12 callback ports preserve wrapped resume keys`() = runBlocking {
        createLegacyFixture()
        startAudit("callbacks")
        val bridge = RecordingBridge()
        LegacyDatabaseReader.openReadOnly(legacyFile).use { reader ->
            val handlers = factory(reader, bridge = bridge).handlers()
            val m5 = handlers.handler(LegacyImportPhase.DEVICE_PLAYLISTS)
            val first = run(m5, LegacyImportPhase.DEVICE_PLAYLISTS, "callbacks")
            val resumed =
                run(m5, LegacyImportPhase.DEVICE_PLAYLISTS, "callbacks", first.lastStableKey)
            assertFalse(first.complete)
            assertTrue(resumed.complete)
            assertEquals(listOf(null, "origin-1"), bridge.deviceKeys)

            val m12 = handlers.handler(LegacyImportPhase.LOCAL_REINDEX)
            val reindexFirst = run(m12, LegacyImportPhase.LOCAL_REINDEX, "callbacks")
            val reindexResumed =
                run(m12, LegacyImportPhase.LOCAL_REINDEX, "callbacks", reindexFirst.lastStableKey)
            assertFalse(reindexFirst.complete)
            assertTrue(reindexResumed.complete)
            assertEquals(listOf(null, "source-1"), bridge.reindexKeys)
        }
    }

    @Test
    fun `checkpoint codec rejects unknown kinds versions and malformed shapes`() {
        assertRejected { R16MigrationCallbackPageResult(true, "stale") }
        assertRejected { R16MigrationCallbackPageResult(false, null) }
        val m2 = R16MigrationCheckpointCodec.encodePair("M2", "track-a", "candidate-a")
        assertRejected { R16MigrationCheckpointCodec.decodePair(m2, "M7") }
        assertRejected {
            R16MigrationCheckpointCodec.decodeSingle(JSONObject(m2).put("v", 99).toString(), "M2")
        }
        assertRejected { R16MigrationCheckpointCodec.decodeTriple(m2, "M2") }
        val membershipStart = R16MigrationCheckpointCodec.encodeMembershipStart()
        val cursor = R16MigrationCheckpointCodec.decodePlaylist(membershipStart)
        assertEquals(R16PlaylistCheckpoint.Section.MEMBERSHIPS, cursor.section)
        assertNull(cursor.playlistId)
        assertNull(cursor.trackId)
    }

    private fun factory(
        reader: LegacyDatabaseReader,
        pageSize: Int = 2,
        bridge: R16MusikrMigrationBridge = RecordingBridge(),
    ) =
        R16MigrationPipelineFactory(
            legacyReader = reader,
            database = database,
            assetVerifier = LegacyAssetVerifier { null },
            artifactVerifier = LegacyDownloadArtifactVerifier { null },
            musikrBridge = bridge,
            importedAtEpochMs = { 10_000L },
            pageSize = pageSize,
        )

    private suspend fun run(
        handler: R16MigrationPhaseHandler,
        phase: LegacyImportPhase,
        migrationId: String,
        lastStableKey: String? = null,
    ): R16MigrationPhaseResult =
        handler.run(
            R16MigrationPhaseContext(
                migrationId = migrationId,
                phase = phase,
                lastStableKey = lastStableKey,
                completedPhases = emptySet(),
                legacyDatabaseSha256 = null,
                backupSatisfied = true,
                cancellationRequested = { false },
            )
        )

    private suspend fun drain(
        handler: R16MigrationPhaseHandler,
        phase: LegacyImportPhase,
        migrationId: String,
    ) {
        var key: String? = null
        do {
            val result = run(handler, phase, migrationId, key)
            key = result.lastStableKey
            if (result.complete) return
        } while (true)
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

    private fun createLegacyFixture() {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { db ->
            db.execSQL("PRAGMA user_version = 10")
            db.execSQL(
                """
                CREATE TABLE canonical_track(
                    trackId TEXT NOT NULL, realm TEXT NOT NULL, title TEXT NOT NULL,
                    artists TEXT NOT NULL, album TEXT, durationMs INTEGER, versionLabel TEXT,
                    explicit INTEGER, live INTEGER NOT NULL, remix INTEGER NOT NULL, artwork TEXT
                )
                """
                    .trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE canonical_track_candidate(
                    trackId TEXT NOT NULL, candidateId TEXT NOT NULL, position INTEGER NOT NULL,
                    kind TEXT NOT NULL, sourceId TEXT NOT NULL, sourceItemId TEXT NOT NULL,
                    availability TEXT NOT NULL, locator TEXT, providerId TEXT, mimeType TEXT,
                    container TEXT, bitrateBps INTEGER, contentLength INTEGER
                )
                """
                    .trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE library_relationship(
                    trackId TEXT NOT NULL, liked INTEGER NOT NULL, downloaded INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE user_playlist(
                    playlistId TEXT NOT NULL, name TEXT NOT NULL, pinned INTEGER NOT NULL,
                    position INTEGER NOT NULL, artworkUri TEXT
                )
                """
                    .trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE playlist_membership(
                    trackId TEXT NOT NULL, playlistId TEXT NOT NULL, position INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            insert(
                db,
                "canonical_track",
                listOf(
                    "track-a",
                    "legacy",
                    "Alpha",
                    "5:First",
                    "Album",
                    120_000,
                    null,
                    0,
                    0,
                    0,
                    null,
                ),
            )
            insert(
                db,
                "canonical_track",
                listOf("track-b", "legacy", "Beta", "6:Artist", null, 180_000, null, 0, 0, 0, null),
            )
            insert(
                db,
                "canonical_track_candidate",
                listOf(
                    "track-a",
                    "candidate-a",
                    0,
                    "PROVIDER",
                    "provider",
                    "item-a",
                    "AVAILABLE",
                    "https://example.test/a",
                    "provider",
                    "audio/mpeg",
                    "mp3",
                    128_000,
                    10_000,
                ),
            )
            insert(
                db,
                "canonical_track_candidate",
                listOf(
                    "track-b",
                    "candidate-b",
                    0,
                    "PROVIDER",
                    "provider",
                    "item-b",
                    "AVAILABLE",
                    "https://example.test/b",
                    "provider",
                    "audio/mpeg",
                    "mp3",
                    128_000,
                    10_000,
                ),
            )
            insert(db, "library_relationship", listOf("track-a", 1, 0))
            insert(db, "library_relationship", listOf("track-b", 0, 0))
            insert(db, "user_playlist", listOf("playlist-a", "Mix", 1, 0, null))
            insert(db, "playlist_membership", listOf("track-b", "playlist-a", 0))
            insert(db, "playlist_membership", listOf("track-a", "playlist-a", 1))
        }
    }

    private fun insert(db: SQLiteDatabase, table: String, values: List<Any?>) {
        val placeholders = values.joinToString(",") { "?" }
        db.execSQL("INSERT INTO $table VALUES ($placeholders)", values.toTypedArray())
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected malformed checkpoint to be rejected")
        } catch (_: Exception) {
            // Expected.
        }
    }

    private fun List<R16MigrationPhaseHandler>.handler(phase: LegacyImportPhase) = single {
        it.phase == phase
    }

    private class RecordingBridge : R16MusikrMigrationBridge {
        val deviceKeys = mutableListOf<String?>()
        val reindexKeys = mutableListOf<String?>()
        private var deviceCall = 0
        private var reindexCall = 0

        override suspend fun importDevicePlaylistPage(
            request: R16MusikrDevicePlaylistPageRequest
        ): R16MigrationCallbackPageResult {
            deviceKeys += request.lastStableKey
            return if (deviceCall++ == 0) {
                R16MigrationCallbackPageResult(false, "origin-1")
            } else {
                R16MigrationCallbackPageResult(true, null)
            }
        }

        override suspend fun reindexLocalPage(
            request: R16MusikrLocalReindexPageRequest
        ): R16MigrationCallbackPageResult {
            reindexKeys += request.lastStableKey
            return if (reindexCall++ == 0) {
                R16MigrationCallbackPageResult(false, "source-1")
            } else {
                R16MigrationCallbackPageResult(true, null)
            }
        }
    }
}
