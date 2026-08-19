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
import androidx.test.core.app.ApplicationProvider
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        legacyFile = File(context.cacheDir, "legacy-${UUID.randomUUID()}.db")
    }

    @After
    fun tearDown() {
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

    private fun createLegacyDatabase() {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { database ->
            database.version = LEGACY_SCHEMA_VERSION
            for (table in LEGACY_TABLES) {
                if (table == "canonical_track") continue
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
        }
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
