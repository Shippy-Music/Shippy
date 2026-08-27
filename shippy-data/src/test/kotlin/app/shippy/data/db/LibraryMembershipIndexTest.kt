/*
 * Copyright (c) 2026 Auxio Project
 * LibraryMembershipIndexTest.kt is part of Auxio.
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
package app.shippy.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.entity.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryMembershipIndexTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .addCallback(R16LibraryMembershipTriggers)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `index follows membership asset and title changes and rebuilds`() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        insertRecording(sqlite, "recording-1", "Bravo")
        assertEquals(emptyList<String>(), indexedIds(sqlite))

        insertLibraryRelationship(sqlite, "recording-1", 1L)
        assertEquals(listOf("recording-1"), indexedIds(sqlite))
        assertEquals("bravo", indexedTitle(sqlite, "recording-1"))

        sqlite.execSQL(
            "UPDATE recording SET canonical_title = ? WHERE recording_id = ?",
            arrayOf<Any?>("Alpha", "recording-1"),
        )
        assertEquals("alpha", indexedTitle(sqlite, "recording-1"))

        sqlite.execSQL(
            "UPDATE library_recording SET first_added_at_epoch_ms = NULL WHERE recording_id = ?",
            arrayOf<Any?>("recording-1"),
        )
        assertEquals(emptyList<String>(), indexedIds(sqlite))

        database.assetDao().upsert(asset("asset-1", "recording-1", "LOCAL_FILE", "AVAILABLE"))
        assertEquals(listOf("recording-1"), indexedIds(sqlite))

        sqlite.execSQL(
            "UPDATE media_asset SET asset_state = 'VERIFYING' WHERE asset_id = ?",
            arrayOf<Any?>("asset-1"),
        )
        assertEquals(emptyList<String>(), indexedIds(sqlite))

        sqlite.execSQL(
            "UPDATE media_asset SET asset_state = 'AVAILABLE' WHERE asset_id = ?",
            arrayOf<Any?>("asset-1"),
        )
        sqlite.execSQL("DELETE FROM library_membership_index")
        database.libraryMembershipDao().rebuild()
        assertEquals(listOf("recording-1"), indexedIds(sqlite))

        sqlite.execSQL("DELETE FROM media_asset WHERE asset_id = 'asset-1'")
        assertTrue(indexedIds(sqlite).isEmpty())
    }

    @Test
    fun `playlist occurrences keep a transient recording in Library until the last one is gone`() =
        runBlocking {
            val sqlite = database.openHelper.writableDatabase
            insertRecording(sqlite, "recording-1", "First")
            insertRecording(sqlite, "recording-2", "Second")
            insertPlaylist(sqlite, "playlist-1")

            insertPlaylistEntry(sqlite, "entry-1", "playlist-1", "recording-1")
            assertEquals(listOf("recording-1"), indexedIds(sqlite))

            sqlite.execSQL(
                "UPDATE playlist_entry SET recording_id = ? WHERE playlist_entry_id = ?",
                arrayOf<Any?>("recording-2", "entry-1"),
            )
            assertEquals(listOf("recording-2"), indexedIds(sqlite))

            insertPlaylistEntry(sqlite, "entry-2", "playlist-1", "recording-2")
            sqlite.execSQL("DELETE FROM library_membership_index")
            database.libraryMembershipDao().rebuild()
            assertEquals(listOf("recording-2"), indexedIds(sqlite))

            sqlite.execSQL("DELETE FROM playlist_entry WHERE playlist_entry_id = 'entry-1'")
            assertEquals(listOf("recording-2"), indexedIds(sqlite))
            sqlite.execSQL("DELETE FROM playlist_entry WHERE playlist_entry_id = 'entry-2'")
            assertTrue(indexedIds(sqlite).isEmpty())
        }

    private fun insertRecording(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        recordingId: String,
        title: String,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO recording (
                recording_id, canonical_title, duration_ms, version_kind, version_label,
                explicitness, preferred_release_id, preferred_artwork_id, retention_kind,
                retained_until_epoch_ms, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                recordingId,
                title,
                180_000L,
                "ORIGINAL",
                null,
                "UNKNOWN",
                null,
                null,
                "DURABLE",
                null,
                1L,
                1L,
            ),
        )
    }

    private fun insertLibraryRelationship(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        recordingId: String,
        firstAddedAtEpochMs: Long?,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO library_recording (
                recording_id, liked, explicitly_saved, user_edited, manually_identified,
                first_added_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(recordingId, 0, 0, 0, 0, firstAddedAtEpochMs, 1L),
        )
    }

    private fun insertPlaylist(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        playlistId: String,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO playlist (
                playlist_id, name, pinned, library_order_key, artwork_override, display_sort_mode,
                display_sort_direction, origin_kind, origin_key, created_at_epoch_ms,
                updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                playlistId,
                "Playlist",
                0,
                1024L,
                null,
                "CUSTOM",
                "ASC",
                "USER",
                null,
                1L,
                1L,
            ),
        )
    }

    private fun insertPlaylistEntry(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        entryId: String,
        playlistId: String,
        recordingId: String,
    ) {
        sqlite.execSQL(
            "INSERT INTO playlist_entry VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(entryId, playlistId, recordingId, 1024L, 1L),
        )
    }

    private fun indexedIds(sqlite: androidx.sqlite.db.SupportSQLiteDatabase): List<String> =
        sqlite
            .query(
                "SELECT recording_id FROM library_membership_index " +
                    "ORDER BY title_sort_key, recording_id"
            )
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun indexedTitle(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        recordingId: String,
    ): String =
        sqlite
            .query(
                "SELECT title_sort_key FROM library_membership_index WHERE recording_id = ?",
                arrayOf(recordingId),
            )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun asset(assetId: String, recordingId: String, assetKind: String, assetState: String) =
        MediaAssetEntity(
            assetId = assetId,
            recordingId = recordingId,
            sourceReferenceId = null,
            assetKind = assetKind,
            assetState = assetState,
            locationType = "CONTENT_URI",
            location = "content://music/$assetId",
            documentId = null,
            mediaStoreId = null,
            displayName = "$assetId.flac",
            mimeType = "audio/flac",
            container = "flac",
            codec = "flac",
            bitrateBps = null,
            sampleRateHz = null,
            channelCount = null,
            contentLength = 1_000,
            contentChecksum = null,
            fingerprintId = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            lastVerifiedAtEpochMs = 1,
        )
}
