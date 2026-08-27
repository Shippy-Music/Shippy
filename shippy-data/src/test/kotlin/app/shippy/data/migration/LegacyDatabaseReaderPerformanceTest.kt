/*
 * Copyright (c) 2026 Auxio Project
 * LegacyDatabaseReaderPerformanceTest.kt is part of Auxio.
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

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyDatabaseReaderPerformanceTest {
    private lateinit var legacyFile: File

    @Before
    fun setUp() {
        legacyFile = File.createTempFile("legacy-memberships-${UUID.randomUUID()}-", ".db")
        check(legacyFile.delete())
        createLargeMembershipTable()
    }

    @After
    fun tearDown() {
        legacyFile.delete()
    }

    @Test
    fun `large membership pages stream once and preserve ordinal order across resume`() {
        var queryCount = 0
        val rows =
            LegacyDatabaseReader.openReadOnlyForTesting(legacyFile) { queryCount++ }
                .use { reader ->
                    buildList {
                        var afterPlaylistId: String? = null
                        var afterPosition: Int? = null
                        var afterTrackId: String? = null
                        while (true) {
                            val page =
                                reader.playlistMemberships(
                                    afterPlaylistId = afterPlaylistId,
                                    afterPosition = afterPosition,
                                    afterTrackId = afterTrackId,
                                    limit = 200,
                                )
                            if (page.isEmpty()) break
                            addAll(page)
                            val last = page.last()
                            afterPlaylistId = last.playlistId
                            afterPosition = last.position
                            afterTrackId = last.trackId
                        }
                    }
                }

        assertEquals(10_000, rows.size)
        assertEquals(
            (0 until 10_000).map { "track-${it.toString().padStart(5, '0')}" },
            rows.map(LegacyPlaylistMembershipRow::trackId),
        )
        assertEquals(
            (0 until 10_000).map(Int::toLong),
            rows.map(LegacyPlaylistMembershipRow::orderOrdinal),
        )
        assertEquals(1, queryCount)

        var resumedQueryCount = 0
        LegacyDatabaseReader.openReadOnlyForTesting(legacyFile) { resumedQueryCount++ }
            .use { reader ->
                val resumed =
                    reader.playlistMemberships(
                        afterPlaylistId = "playlist-a",
                        afterPosition = 2_499,
                        afterTrackId = "track-04999",
                        limit = 37,
                    )
                assertEquals(37, resumed.size)
                assertEquals("track-05000", resumed.first().trackId)
                assertEquals(5_000L, resumed.first().orderOrdinal)
                assertEquals(
                    (5_000L until 5_037L).toList(),
                    resumed.map(LegacyPlaylistMembershipRow::orderOrdinal),
                )
            }
        assertEquals(1, resumedQueryCount)
    }

    private fun createLargeMembershipTable() {
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE playlist_membership(
                    trackId TEXT NOT NULL,
                    playlistId TEXT NOT NULL,
                    position INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            database.beginTransaction()
            try {
                for (index in 0 until 10_000) {
                    database.execSQL(
                        "INSERT INTO playlist_membership(trackId, playlistId, position) VALUES (?, ?, ?)",
                        arrayOf<Any?>(
                            "track-${index.toString().padStart(5, '0')}",
                            "playlist-a",
                            index / 2,
                        ),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }
}
