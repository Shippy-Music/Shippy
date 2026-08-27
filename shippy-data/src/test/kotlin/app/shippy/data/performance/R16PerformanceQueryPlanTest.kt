/*
 * Copyright (c) 2026 Auxio Project
 * R16PerformanceQueryPlanTest.kt is part of Auxio.
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
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PerformanceQueryPlanTest {
    private lateinit var database: ShippyR16Database
    private lateinit var fixture: R16PerformanceFixture

    @Before
    fun setUp() {
        database = newR16PerformanceDatabase(ApplicationProvider.getApplicationContext<Context>())
        fixture = R16PerformanceFixtureGenerator(database).seed(R16PerformanceProfiles.hundred)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Library page plan has no unbounded catalogue scan or temp sort`() {
        val libraryPlan =
            explain(
                """
                SELECT song.*
                FROM library_song_view song
                JOIN library_membership_index member ON member.recording_id = song.recording_id
                ORDER BY member.title_sort_key, member.recording_id
                LIMIT 50
                """
                    .trimIndent()
            )
        assertUsesIndex(libraryPlan, "index_library_membership_order")
        assertUsesIndex(libraryPlan, "index_media_asset_recording_kind_state")
        assertUsesIndex(libraryPlan, "index_play_history_recording")
        assertNoUnindexedScan(libraryPlan, "local_asset")
        assertNoUnindexedScan(libraryPlan, "download_asset")
        assertNoUnindexedScan(libraryPlan, "history")
        assertNoFullScan(libraryPlan, "r")
        assertNoTempBTreeForOrder(libraryPlan)
    }

    @Test
    fun `playlist page plan uses bounded entry and availability lookups`() {
        val playlistPlan =
            explain(
                """
                SELECT * FROM playlist_entry_view
                WHERE playlist_id = ?
                ORDER BY order_key, playlist_entry_id
                LIMIT 50
                """
                    .trimIndent(),
                fixture.duplicatePlaylistId,
            )
        assertUsesIndex(playlistPlan, "index_playlist_entry_order")
        assertUsesIndex(playlistPlan, "index_media_asset_recording_kind_state")
        assertUsesIndex(playlistPlan, "index_download_job_recording")
        assertUsesIndex(playlistPlan, "index_source_reference_recording")
        assertNoFullScan(playlistPlan, "entry")
        assertNoUnindexedScan(playlistPlan, "job")
        assertNoUnindexedScan(playlistPlan, "source")
    }

    // Dynamic CASE ordering may legitimately report a temporary ORDER BY sort. These guards only
    // enforce playlist scoping and, for the filtered variant, FTS access.
    @Test
    fun `dynamic sorted playlist page keeps playlist predicate indexed`() {
        val sortedPlan =
            explain(
                """
                SELECT entry.*
                FROM playlist_entry_view entry
                JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
                JOIN library_song_view song ON song.recording_id = entry.recording_id
                WHERE entry.playlist_id = ?
                ORDER BY
                    CASE WHEN ?2 = 'RECENTLY_ADDED' AND ?3 = 'ASC'
                              THEN raw.added_at_epoch_ms END DESC,
                    CASE WHEN ?2 = 'RECENTLY_ADDED' AND ?3 = 'DESC'
                              THEN raw.added_at_epoch_ms END ASC,
                    CASE WHEN ?2 = 'TITLE' AND ?3 = 'ASC'
                              THEN song.title_sort_key END ASC,
                    CASE WHEN ?2 = 'TITLE' AND ?3 = 'DESC'
                              THEN song.title_sort_key END DESC,
                    entry.playlist_entry_id ASC
                LIMIT 50
                """
                    .trimIndent(),
                fixture.duplicatePlaylistId,
                "TITLE",
                "ASC",
            )
        assertUsesIndex(sortedPlan, "index_playlist_entry_order")
        assertNoUnindexedScan(sortedPlan, "entry")
    }

    @Test
    fun `filtered dynamic sorted playlist page keeps playlist predicate indexed`() {
        val filteredSortedPlan =
            explain(
                """
                SELECT entry.*
                FROM playlist_entry_view entry
                JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
                JOIN library_song_view song ON song.recording_id = entry.recording_id
                JOIN recording_fts search ON search.recording_id = entry.recording_id
                WHERE entry.playlist_id = ?1 AND recording_fts MATCH ?4
                ORDER BY
                    CASE WHEN ?2 = 'RECENTLY_ADDED' AND ?3 = 'ASC'
                              THEN raw.added_at_epoch_ms END DESC,
                    CASE WHEN ?2 = 'RECENTLY_ADDED' AND ?3 = 'DESC'
                              THEN raw.added_at_epoch_ms END ASC,
                    CASE WHEN ?2 = 'TITLE' AND ?3 = 'ASC'
                              THEN song.title_sort_key END ASC,
                    CASE WHEN ?2 = 'TITLE' AND ?3 = 'DESC'
                              THEN song.title_sort_key END DESC,
                    entry.playlist_entry_id ASC
                LIMIT 50
                """
                    .trimIndent(),
                fixture.duplicatePlaylistId,
                "TITLE",
                "ASC",
                "target*",
            )
        assertUsesIndex(filteredSortedPlan, "index_playlist_entry_order")
        assertNoUnindexedScan(filteredSortedPlan, "entry")
        assertTrue(
            "Expected FTS virtual-table access, got: ${filteredSortedPlan.joinToString(" | ")}",
            filteredSortedPlan.any { it.contains("VIRTUAL TABLE", ignoreCase = true) },
        )
    }

    @Test
    fun `local search uses the actual FTS virtual table`() {
        val plan =
            explain(
                """
                SELECT song.* FROM library_song_view song
                JOIN recording_fts search ON search.recording_id = song.recording_id
                WHERE recording_fts MATCH ?
                  AND (
                      song.date_added_epoch_ms IS NOT NULL
                      OR song.local_asset_exists
                      OR song.download_asset_exists
                  )
                ORDER BY song.title_sort_key, song.recording_id
                LIMIT 50
                """
                    .trimIndent(),
                "target*",
            )
        assertTrue(
            "Expected FTS virtual-table access, got: ${plan.joinToString(" | ")}",
            plan.any { it.contains("VIRTUAL TABLE", ignoreCase = true) },
        )
    }

    @Test
    fun `playback source lookups use recording predicates indexes`() {
        val assetPlan =
            explain(
                """
                SELECT * FROM media_asset
                WHERE recording_id = ? AND asset_state = 'AVAILABLE'
                ORDER BY asset_kind, asset_id
                """
                    .trimIndent(),
                fixture.probeRecordingId,
            )
        assertUsesIndex(assetPlan, "index_media_asset_recording_kind_state")
        assertNoUnindexedScan(assetPlan, "media_asset")

        val sourcePlan =
            explain(
                """
                SELECT * FROM source_reference
                WHERE recording_id = ?
                ORDER BY source_reference_id
                """
                    .trimIndent(),
                fixture.probeRecordingId,
            )
        assertUsesIndex(sourcePlan, "index_source_reference_recording")
        assertNoUnindexedScan(sourcePlan, "source_reference")
    }

    private fun explain(sql: String, vararg args: String): List<String> {
        val sqlite: SupportSQLiteDatabase = database.openHelper.writableDatabase
        return sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args)).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            buildList { while (cursor.moveToNext()) add(cursor.getString(detailColumn)) }
        }
    }

    private fun assertUsesIndex(plan: List<String>, indexName: String) {
        assertTrue(
            "Expected $indexName in query plan, got: ${plan.joinToString(" | ")}",
            plan.any { it.contains(indexName, ignoreCase = true) },
        )
    }

    private fun assertNoUnindexedScan(plan: List<String>, tableOrAlias: String) {
        val unindexedScans =
            plan.filter {
                it.contains("SCAN $tableOrAlias", ignoreCase = true) &&
                    !it.contains("USING", ignoreCase = true)
            }
        assertTrue(
            "Unexpected unindexed scan of $tableOrAlias: ${unindexedScans.joinToString(" | ")}",
            unindexedScans.isEmpty(),
        )
    }

    private fun assertNoFullScan(plan: List<String>, tableOrAlias: String) {
        val fullScans = plan.filter { it.contains("SCAN $tableOrAlias", ignoreCase = true) }
        assertTrue(
            "Unexpected full scan of $tableOrAlias: ${fullScans.joinToString(" | ")}",
            fullScans.isEmpty(),
        )
    }

    private fun assertNoTempBTreeForOrder(plan: List<String>) {
        val tempSorts =
            plan.filter { it.contains("USE TEMP B-TREE FOR ORDER BY", ignoreCase = true) }
        assertTrue(
            "Unexpected temporary ORDER BY sort: ${tempSorts.joinToString(" | ")}",
            tempSorts.isEmpty(),
        )
    }
}
