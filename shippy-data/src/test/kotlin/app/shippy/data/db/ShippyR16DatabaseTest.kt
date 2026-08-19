/*
 * Copyright (c) 2026 Auxio Project
 * ShippyR16DatabaseTest.kt is part of Auxio.
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
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShippyR16DatabaseTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `schema version one creates the identity and asset foundation`() {
        val sqlite = database.openHelper.writableDatabase
        val names = mutableSetOf<String>()
        sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        val views = mutableSetOf<String>()
        sqlite.query("SELECT name FROM sqlite_master WHERE type = 'view'").use { cursor ->
            while (cursor.moveToNext()) views += cursor.getString(0)
        }

        assertTrue(names.containsAll(EXPECTED_TABLES))
        assertEquals(EXPECTED_VIEWS, views)
        sqlite.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(ShippyR16Database.SCHEMA_VERSION, cursor.getInt(0))
        }
        sqlite.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `schema preserves duplicate occurrences and rejects identity constraint violations`() {
        val sqlite = database.openHelper.writableDatabase
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
                "recording-1",
                "Track",
                120_000L,
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
        sqlite.execSQL(
            """
            INSERT INTO playlist (
                playlist_id, name, pinned, library_order_key, artwork_override,
                display_sort_mode, display_sort_direction, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>("playlist-1", "Mix", false, 1L, null, "CUSTOM", "ASC", 1L, 1L),
        )
        sqlite.execSQL(
            "INSERT INTO playlist_entry VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("entry-1", "playlist-1", "recording-1", 1L, 1L),
        )
        sqlite.execSQL(
            "INSERT INTO playlist_entry VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("entry-2", "playlist-1", "recording-1", 2L, 1L),
        )
        sqlite.execSQL(
            """
            INSERT INTO source_reference (
                source_reference_id, recording_id, provider_id, item_type, source_item_id,
                original_url, availability_state, availability_checked_at_epoch_ms,
                availability_expires_at_epoch_ms, failure_kind, identity_status,
                raw_metadata_observation_id, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                "source-1",
                "recording-1",
                "provider",
                "RECORDING",
                "item-1",
                null,
                "AVAILABLE",
                1L,
                null,
                null,
                "USER_CONFIRMED",
                "observation-1",
                1L,
                1L,
            ),
        )

        sqlite.query("SELECT COUNT(*) FROM playlist_entry").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sqlite.execSQL(
                "INSERT INTO playlist_entry VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("entry-orphan", "playlist-1", "missing", 3L, 1L),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sqlite.execSQL(
                """
                INSERT INTO source_reference (
                    source_reference_id, recording_id, provider_id, item_type, source_item_id,
                    original_url, availability_state, availability_checked_at_epoch_ms,
                    availability_expires_at_epoch_ms, failure_kind, identity_status,
                    raw_metadata_observation_id, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
                arrayOf<Any?>(
                    "source-2",
                    "recording-1",
                    "provider",
                    "RECORDING",
                    "item-1",
                    null,
                    "AVAILABLE",
                    1L,
                    null,
                    null,
                    "USER_CONFIRMED",
                    "observation-2",
                    1L,
                    1L,
                ),
            )
        }
    }

    private companion object {
        val EXPECTED_TABLES =
            setOf(
                "recording",
                "artist",
                "recording_artist_credit",
                "release",
                "release_track",
                "source_reference",
                "metadata_observation",
                "external_identifier",
                "artwork_reference",
                "media_asset",
                "audio_fingerprint",
                "library_recording",
                "playlist",
                "playlist_entry",
                "library_layout_entry",
                "user_metadata_override",
                "canonical_field_provenance",
                "identity_decision",
                "identity_rejection",
                "entity_redirect",
                "merge_audit",
                "play_history",
                "playback_checkpoint",
                "playback_checkpoint_entry",
                "download_job",
                "lastfm_scrobble_outbox",
                "lyrics_cache",
                "saved_source_entity",
                "migration_audit",
                "recording_fts",
            )
        val EXPECTED_VIEWS = setOf("library_song_view", "playlist_entry_view")
    }
}
