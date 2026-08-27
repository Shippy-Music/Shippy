/*
 * Copyright (c) 2026 Auxio Project
 * R16PerformanceFixtureTest.kt is part of Auxio.
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
package app.shippy.data.performance

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PerformanceFixtureTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database = newR16PerformanceDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `small profile has deterministic counts identities duplicates and history`() {
        val profile = R16PerformanceProfiles.hundred
        val fixture = R16PerformanceFixtureGenerator(database).seed(profile)
        val sqlite = database.openHelper.writableDatabase

        assertEquals(profile.recordingCount, count(sqlite, "SELECT COUNT(*) FROM recording"))
        assertEquals(profile.recordingCount, count(sqlite, "SELECT COUNT(*) FROM recording_fts"))
        assertEquals(profile.playlistCount, count(sqlite, "SELECT COUNT(*) FROM playlist"))
        assertEquals(
            profile.totalPlaylistEntryCount,
            count(sqlite, "SELECT COUNT(*) FROM playlist_entry"),
        )
        assertEquals(profile.totalAssetCount, count(sqlite, "SELECT COUNT(*) FROM media_asset"))
        assertEquals(profile.historyRowCount, count(sqlite, "SELECT COUNT(*) FROM play_history"))
        assertEquals(1, count(sqlite, "SELECT COUNT(*) FROM source_reference"))

        assertEquals("r16-recording-00000", firstString(sqlite, "recording_id"))
        assertEquals("r16-recording-00099", lastString(sqlite, "recording_id"))
        assertEquals(fixture.probeRecordingId, "r16-recording-00000")
        assertEquals("r16-playlist-duplicate-capable", fixture.duplicatePlaylistId)

        val duplicatePlaylistRows =
            count(
                sqlite,
                "SELECT COUNT(*) FROM playlist_entry WHERE playlist_id = '${fixture.duplicatePlaylistId}'",
            )
        val duplicatePlaylistRecordings =
            count(
                sqlite,
                """
                SELECT COUNT(DISTINCT recording_id)
                FROM playlist_entry
                WHERE playlist_id = '${fixture.duplicatePlaylistId}'
                """
                    .trimIndent(),
            )
        assertEquals(profile.largePlaylistEntryCount, duplicatePlaylistRows)
        assertTrue(duplicatePlaylistRecordings < duplicatePlaylistRows)

        assertEquals(
            profile.duplicateAssetCount,
            count(
                sqlite,
                """
                SELECT COUNT(*) FROM (
                    SELECT content_checksum FROM media_asset
                    GROUP BY content_checksum HAVING COUNT(*) > 1
                )
                """
                    .trimIndent(),
            ),
        )
    }

    @Test
    fun `reference profiles expose the required device dataset contracts without seeding them`() {
        val tenThousand = R16PerformanceProfiles.tenThousand
        val fiftyThousand = R16PerformanceProfiles.fiftyThousand

        assertEquals(10_000, tenThousand.recordingCount)
        assertEquals(50_000, fiftyThousand.recordingCount)
        assertEquals(1_000, tenThousand.playlistCount)
        assertEquals(1_000, fiftyThousand.playlistCount)
        assertEquals(10_000, tenThousand.largePlaylistEntryCount)
        assertEquals(10_000, fiftyThousand.largePlaylistEntryCount)
        assertEquals(20_000, tenThousand.historyRowCount)
        assertEquals(20_000, fiftyThousand.historyRowCount)
    }

    private fun count(sqlite: SupportSQLiteDatabase, sql: String): Int =
        sqlite.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Count query returned no row: $sql" }
            cursor.getInt(0)
        }

    private fun firstString(sqlite: SupportSQLiteDatabase, column: String): String =
        sqlite.query("SELECT $column FROM recording ORDER BY $column LIMIT 1").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun lastString(sqlite: SupportSQLiteDatabase, column: String): String =
        sqlite.query("SELECT $column FROM recording ORDER BY $column DESC LIMIT 1").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
