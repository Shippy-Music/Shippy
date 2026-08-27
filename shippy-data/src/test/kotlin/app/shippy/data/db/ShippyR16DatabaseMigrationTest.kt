/*
 * Copyright (c) 2026 Auxio Project
 * ShippyR16DatabaseMigrationTest.kt is part of Auxio.
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
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.R16DataRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShippyR16DatabaseMigrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(ShippyR16Database.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(ShippyR16Database.DATABASE_NAME)
    }

    @Test
    fun `runtime migrates v1 playlist origins without losing entries`() {
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        val sqlite = seeded.openHelper.writableDatabase
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
                "recording-migration",
                "Migration track",
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
        sqlite.execSQL(
            """
            INSERT INTO playlist (
                playlist_id, name, pinned, library_order_key, artwork_override,
                display_sort_mode, display_sort_direction, origin_kind, origin_key,
                created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                "playlist-migration",
                "Migrated mix",
                false,
                1L,
                null,
                "CUSTOM",
                "ASC",
                "USER",
                null,
                1L,
                1L,
            ),
        )
        sqlite.execSQL(
            "INSERT INTO playlist_entry VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("entry-migration", "playlist-migration", "recording-migration", 1L, 1L),
        )
        seeded.close()

        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v1 ->
                v1.execSQL("PRAGMA foreign_keys = OFF")
                v1.execSQL("DROP TABLE `library_membership_index`")
                v1.execSQL("DROP TABLE `playlist_entry`")
                v1.execSQL("DROP TABLE `playlist`")
                v1.execSQL(
                    """
                    CREATE TABLE `playlist` (
                        `playlist_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `library_order_key` INTEGER NOT NULL,
                        `artwork_override` TEXT,
                        `display_sort_mode` TEXT NOT NULL,
                        `display_sort_direction` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """
                        .trimIndent()
                )
                v1.execSQL(
                    """
                    INSERT INTO `playlist` (
                        `playlist_id`, `name`, `pinned`, `library_order_key`, `artwork_override`,
                        `display_sort_mode`, `display_sort_direction`,
                        `created_at_epoch_ms`, `updated_at_epoch_ms`
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent(),
                    arrayOf<Any?>(
                        "playlist-migration",
                        "Migrated mix",
                        false,
                        1L,
                        null,
                        "CUSTOM",
                        "ASC",
                        1L,
                        1L,
                    ),
                )
                v1.execSQL(
                    """
                    CREATE TABLE `playlist_entry` (
                        `playlist_entry_id` TEXT NOT NULL,
                        `playlist_id` TEXT NOT NULL,
                        `recording_id` TEXT NOT NULL,
                        `order_key` INTEGER NOT NULL,
                        `added_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_entry_id`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`playlist_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """
                        .trimIndent()
                )
                v1.execSQL(
                    "INSERT INTO `playlist_entry` VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        "entry-migration",
                        "playlist-migration",
                        "recording-migration",
                        1L,
                        1L,
                    ),
                )
                v1.execSQL(
                    "CREATE INDEX `index_playlist_library_order` ON `playlist` (`pinned` DESC, `library_order_key` ASC)"
                )
                v1.execSQL(
                    "CREATE INDEX `index_playlist_entry_order` ON `playlist_entry` (`playlist_id`, `order_key`)"
                )
                v1.execSQL(
                    "CREATE INDEX `index_playlist_entry_recording` ON `playlist_entry` (`recording_id`)"
                )
                downgradeDownloadJobToV3(v1)
                v1.execSQL(
                    "UPDATE room_master_table SET identity_hash = '44e9cd72ef59268c22b636e7b74d7fdc' WHERE id = 42"
                )
                v1.execSQL("PRAGMA user_version = 1")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val migratedSqlite = migrated.openHelper.writableDatabase
            migratedSqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            migratedSqlite
                .query(
                    "SELECT origin_kind, origin_key FROM playlist WHERE playlist_id = 'playlist-migration'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("USER", cursor.getString(0))
                    assertNull(cursor.getString(1))
                }
            migratedSqlite.query("SELECT COUNT(*) FROM playlist_entry").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            migratedSqlite
                .query(
                    "SELECT COUNT(*) FROM library_membership_index " +
                        "WHERE recording_id = 'recording-migration'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            migratedSqlite.query("PRAGMA foreign_key_check").use { cursor ->
                assertTrue(!cursor.moveToFirst())
            }
            val indexNames = mutableSetOf<String>()
            migratedSqlite.query("PRAGMA index_list('playlist')").use { cursor ->
                while (cursor.moveToNext()) indexNames += cursor.getString(1)
            }
            assertTrue(indexNames.contains("index_playlist_origin"))
            migratedSqlite.query("SELECT playlist_id, name FROM playlist_fts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("playlist-migration", cursor.getString(0))
                assertEquals("Migrated mix", cursor.getString(1))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `runtime adds nullable download identity fields without changing legacy values`() {
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        val sqlite = seeded.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO recording (recording_id, canonical_title, version_kind, explicitness, " +
                "retention_kind, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                "('download-migration-recording', 'Legacy download', 'ORIGINAL', 'UNKNOWN', " +
                "'DURABLE', 1, 1)"
        )
        sqlite.execSQL(
            "INSERT INTO download_job " +
                "(job_id, recording_id, requested_source_reference_id, published_asset_id, state, " +
                "bytes_transferred, expected_bytes, failure_kind, retry_after_epoch_ms, " +
                "pending_location, display_fallback_json, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('download-migration-job', 'download-migration-recording', NULL, NULL, " +
                "'REQUESTED', 0, NULL, NULL, NULL, NULL, '{}', 1, 1)"
        )
        seeded.close()

        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v3 ->
                downgradeDownloadJobToV3(v3)
                v3.execSQL(
                    "UPDATE room_master_table SET identity_hash = " +
                        "'cfade1bae3244da8eeb6b3b50d2bbe72' WHERE id = 42"
                )
                v3.execSQL("PRAGMA user_version = 3")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val migratedSqlite = migrated.openHelper.writableDatabase
            migratedSqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            val columns = mutableSetOf<String>()
            migratedSqlite.query("PRAGMA table_info('download_job')").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertTrue(columns.contains("requested_media_variant"))
            assertTrue(columns.contains("destination_identity"))
            migratedSqlite
                .query(
                    "SELECT requested_media_variant, destination_identity, pending_location, " +
                        "state FROM download_job WHERE job_id = 'download-migration-job'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertNull(cursor.getString(2))
                    assertEquals("REQUESTED", cursor.getString(3))
                }

            val outboxColumns = mutableSetOf<String>()
            migratedSqlite.query("PRAGMA table_info('lastfm_scrobble_outbox')").use { cursor ->
                while (cursor.moveToNext()) outboxColumns += cursor.getString(1)
            }
            assertTrue(outboxColumns.contains("account_id"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `runtime migrates v3 lastfm scrobble outbox and populates default account id`() {
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        val sqlite = seeded.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO recording (recording_id, canonical_title, version_kind, explicitness, " +
                "retention_kind, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                "('lastfm-recording', 'Scrobble track', 'ORIGINAL', 'UNKNOWN', 'DURABLE', 1, 1)"
        )
        sqlite.execSQL(
            "INSERT INTO lastfm_scrobble_outbox " +
                "(outbox_id, account_id, listening_session_id, recording_id, artist, track, " +
                "album, duration_seconds, started_at_epoch_seconds, chosen_by_user, " +
                "queued_at_epoch_ms, attempt_count, last_attempt_at_epoch_ms) VALUES " +
                "('outbox-1', '', 'session-1', 'lastfm-recording', 'Artist', 'Track', " +
                "'Album', 200, 1000, 1, 1000, 0, NULL)"
        )
        seeded.close()

        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v3 ->
                downgradeDownloadJobToV3(v3)
                downgradeLastFmOutboxToV3(v3)
                v3.execSQL(
                    "UPDATE room_master_table SET identity_hash = " +
                        "'cfade1bae3244da8eeb6b3b50d2bbe72' WHERE id = 42"
                )
                v3.execSQL("PRAGMA user_version = 3")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val migratedSqlite = migrated.openHelper.writableDatabase
            migratedSqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            val outboxColumns = mutableSetOf<String>()
            migratedSqlite.query("PRAGMA table_info('lastfm_scrobble_outbox')").use { cursor ->
                while (cursor.moveToNext()) outboxColumns += cursor.getString(1)
            }
            assertTrue(outboxColumns.contains("account_id"))
            migratedSqlite
                .query(
                    "SELECT outbox_id, account_id, artist, track FROM lastfm_scrobble_outbox WHERE outbox_id = 'outbox-1'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("outbox-1", cursor.getString(0))
                    assertEquals("", cursor.getString(1))
                    assertEquals("Artist", cursor.getString(2))
                    assertEquals("Track", cursor.getString(3))
                }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `runtime migrates v2 playlist search index with trigger upkeep`() {
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        seeded.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO playlist (
                playlist_id, name, pinned, library_order_key, artwork_override,
                display_sort_mode, display_sort_direction, origin_kind, origin_key,
                created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                "playlist-v2-migration",
                "Original mix",
                false,
                1L,
                null,
                "CUSTOM",
                "ASC",
                "USER",
                null,
                1L,
                1L,
            ),
        )
        seeded.close()

        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v2 ->
                v2.execSQL("DROP TRIGGER IF EXISTS `trigger_playlist_fts_insert`")
                v2.execSQL("DROP TRIGGER IF EXISTS `trigger_playlist_fts_name_update`")
                v2.execSQL("DROP TRIGGER IF EXISTS `trigger_playlist_fts_delete`")
                v2.execSQL("DROP TABLE `playlist_fts`")
                downgradeDownloadJobToV3(v2)
                v2.execSQL(
                    "UPDATE room_master_table SET identity_hash = '2059fae1a74020a0376f2b18591ada69' WHERE id = 42"
                )
                v2.execSQL("PRAGMA user_version = 2")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            sqlite
                .query(
                    "SELECT playlist_id, name FROM playlist_fts WHERE playlist_id = 'playlist-v2-migration'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("playlist-v2-migration", cursor.getString(0))
                    assertEquals("Original mix", cursor.getString(1))
                }
            sqlite.execSQL(
                "UPDATE playlist SET name = 'Renamed mix' WHERE playlist_id = 'playlist-v2-migration'"
            )
            sqlite
                .query("SELECT playlist_id FROM playlist_fts WHERE playlist_fts MATCH 'Renamed*'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("playlist-v2-migration", cursor.getString(0))
                }
            sqlite
                .query("SELECT COUNT(*) FROM playlist_fts WHERE playlist_fts MATCH 'Original*'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            sqlite.execSQL("DELETE FROM playlist WHERE playlist_id = 'playlist-v2-migration'")
            sqlite.query("SELECT COUNT(*) FROM playlist_fts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun downgradeDownloadJobToV3(database: SQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = OFF")
        database.execSQL(
            """
            CREATE TABLE `download_job_v3` (
                `job_id` TEXT NOT NULL,
                `recording_id` TEXT NOT NULL,
                `requested_source_reference_id` TEXT,
                `published_asset_id` TEXT,
                `state` TEXT NOT NULL,
                `bytes_transferred` INTEGER NOT NULL,
                `expected_bytes` INTEGER,
                `failure_kind` TEXT,
                `retry_after_epoch_ms` INTEGER,
                `pending_location` TEXT,
                `display_fallback_json` TEXT NOT NULL,
                `created_at_epoch_ms` INTEGER NOT NULL,
                `updated_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`job_id`),
                FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`requested_source_reference_id`) REFERENCES `source_reference`(`source_reference_id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`published_asset_id`) REFERENCES `media_asset`(`asset_id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `download_job_v3` (
                `job_id`, `recording_id`, `requested_source_reference_id`,
                `published_asset_id`, `state`, `bytes_transferred`, `expected_bytes`,
                `failure_kind`, `retry_after_epoch_ms`, `pending_location`,
                `display_fallback_json`, `created_at_epoch_ms`, `updated_at_epoch_ms`
            )
            SELECT `job_id`, `recording_id`, `requested_source_reference_id`,
                `published_asset_id`, `state`, `bytes_transferred`, `expected_bytes`,
                `failure_kind`, `retry_after_epoch_ms`, `pending_location`,
                `display_fallback_json`, `created_at_epoch_ms`, `updated_at_epoch_ms`
            FROM `download_job`
            """
                .trimIndent()
        )
        database.execSQL("DROP TABLE `download_job`")
        database.execSQL("ALTER TABLE `download_job_v3` RENAME TO `download_job`")
        database.execSQL(
            "CREATE INDEX `index_download_job_recording` ON `download_job` " +
                "(`recording_id`, `created_at_epoch_ms`)"
        )
        database.execSQL(
            "CREATE INDEX `index_download_job_state` ON `download_job` " +
                "(`state`, `updated_at_epoch_ms`)"
        )
        database.execSQL(
            "CREATE INDEX `index_download_job_requested_source` ON `download_job` " +
                "(`requested_source_reference_id`)"
        )
        database.execSQL(
            "CREATE INDEX `index_download_job_published_asset` ON `download_job` " +
                "(`published_asset_id`)"
        )
        downgradeLastFmOutboxToV3(database)
        downgradePlayHistoryToV4(database)
    }

    private fun downgradeLastFmOutboxToV3(database: SQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = OFF")
        database.execSQL(
            """
            CREATE TABLE `lastfm_scrobble_outbox_v3` (
                `outbox_id` TEXT NOT NULL,
                `listening_session_id` TEXT NOT NULL,
                `recording_id` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `track` TEXT NOT NULL,
                `album` TEXT,
                `duration_seconds` INTEGER,
                `started_at_epoch_seconds` INTEGER NOT NULL,
                `chosen_by_user` INTEGER NOT NULL,
                `queued_at_epoch_ms` INTEGER NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `last_attempt_at_epoch_ms` INTEGER,
                PRIMARY KEY(`outbox_id`),
                FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `lastfm_scrobble_outbox_v3` (
                `outbox_id`, `listening_session_id`, `recording_id`, `artist`, `track`,
                `album`, `duration_seconds`, `started_at_epoch_seconds`, `chosen_by_user`,
                `queued_at_epoch_ms`, `attempt_count`, `last_attempt_at_epoch_ms`
            )
            SELECT `outbox_id`, `listening_session_id`, `recording_id`, `artist`, `track`,
                `album`, `duration_seconds`, `started_at_epoch_seconds`, `chosen_by_user`,
                `queued_at_epoch_ms`, `attempt_count`, `last_attempt_at_epoch_ms`
            FROM `lastfm_scrobble_outbox`
            """
                .trimIndent()
        )
        database.execSQL("DROP TABLE `lastfm_scrobble_outbox`")
        database.execSQL(
            "ALTER TABLE `lastfm_scrobble_outbox_v3` RENAME TO `lastfm_scrobble_outbox`"
        )
        database.execSQL(
            "CREATE INDEX `index_lastfm_outbox_fifo` ON `lastfm_scrobble_outbox` (`queued_at_epoch_ms`)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX `index_lastfm_outbox_listening_session` ON `lastfm_scrobble_outbox` (`listening_session_id`)"
        )
        database.execSQL(
            "CREATE INDEX `index_lastfm_outbox_recording` ON `lastfm_scrobble_outbox` (`recording_id`)"
        )
    }

    private fun downgradePlayHistoryToV4(database: SQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = OFF")
        database.execSQL(
            """
            CREATE TABLE `play_history_v4` (
                `listening_session_id` TEXT NOT NULL,
                `recording_id` TEXT NOT NULL,
                `queue_entry_id` TEXT NOT NULL,
                `source_reference_id` TEXT,
                `started_at_epoch_ms` INTEGER NOT NULL,
                `ended_at_epoch_ms` INTEGER,
                `active_listened_ms` INTEGER NOT NULL,
                `last_position_ms` INTEGER NOT NULL,
                `completion_kind` TEXT NOT NULL,
                `chosen_by_user` INTEGER NOT NULL,
                PRIMARY KEY(`listening_session_id`),
                FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """
                .trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `play_history_v4` (
                `listening_session_id`, `recording_id`, `queue_entry_id`, `source_reference_id`,
                `started_at_epoch_ms`, `ended_at_epoch_ms`, `active_listened_ms`, `last_position_ms`,
                `completion_kind`, `chosen_by_user`
            )
            SELECT `listening_session_id`, `recording_id`, `queue_entry_id`, `source_reference_id`,
                `started_at_epoch_ms`, `ended_at_epoch_ms`, `active_listened_ms`, `last_position_ms`,
                `completion_kind`, `chosen_by_user`
            FROM `play_history`
            """
                .trimIndent()
        )
        database.execSQL("DROP TABLE `play_history`")
        database.execSQL("ALTER TABLE `play_history_v4` RENAME TO `play_history`")
        database.execSQL(
            "CREATE INDEX `index_play_history_recent` ON `play_history` (`started_at_epoch_ms` DESC)"
        )
        database.execSQL(
            "CREATE INDEX `index_play_history_recording` ON `play_history` (`recording_id` ASC, `started_at_epoch_ms` DESC)"
        )
    }

    @Test
    fun `runtime migrates v4 play history adding snapshots and set null fk without losing records`() {
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        val sqlite = seeded.openHelper.writableDatabase
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
                "recording-play-history",
                "Transient Streamed Song",
                200_000L,
                "ORIGINAL",
                null,
                "UNKNOWN",
                null,
                null,
                "TRANSIENT",
                1_000_000L,
                1L,
                1L,
            ),
        )
        sqlite.execSQL(
            """
            INSERT INTO play_history (
                listening_session_id, recording_id, queue_entry_id, source_reference_id,
                started_at_epoch_ms, ended_at_epoch_ms, active_listened_ms, last_position_ms,
                completion_kind, chosen_by_user, snapshot_title, snapshot_artist_display, snapshot_artwork_location
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                "session-v4-1",
                "recording-play-history",
                "queue-v4-1",
                null,
                1000L,
                2000L,
                1000L,
                1000L,
                "NATURAL_END",
                1,
                null,
                null,
                null,
            ),
        )
        seeded.close()

        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v4 ->
                downgradePlayHistoryToV4(v4)
                v4.execSQL("PRAGMA user_version = 4")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val migratedSqlite = migrated.openHelper.writableDatabase
            migratedSqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            migratedSqlite
                .query(
                    "SELECT snapshot_title, snapshot_artist_display FROM play_history WHERE listening_session_id = 'session-v4-1'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Transient Streamed Song", cursor.getString(0))
                }

            // Verify ON DELETE SET NULL behavior
            migratedSqlite.execSQL("PRAGMA foreign_keys = ON")
            migratedSqlite.execSQL(
                "DELETE FROM recording WHERE recording_id = 'recording-play-history'"
            )
            migratedSqlite
                .query(
                    "SELECT recording_id, snapshot_title FROM play_history WHERE listening_session_id = 'session-v4-1'"
                )
                .use { cursor ->
                    assertTrue(
                        "Play history row must survive recording deletion",
                        cursor.moveToFirst(),
                    )
                    assertNull(
                        "recording_id must be set to null on recording deletion",
                        cursor.getString(0),
                    )
                    assertEquals("Transient Streamed Song", cursor.getString(1))
                }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `runtime migrates v5 play history adding scrobble_disposition column with legacy unknown default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(ShippyR16Database.DATABASE_NAME)

        // Seed v6 database using Room to create correct tables
        val seeded =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        val sqlite = seeded.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO `recording` (`recording_id`, `canonical_title`, `duration_ms`, `version_kind`, `explicitness`, `retention_kind`, `created_at_epoch_ms`, `updated_at_epoch_ms`) " +
                "VALUES ('recording-v5-1', 'Legacy Track', 180000, 'ORIGINAL', 'UNKNOWN', 'DURABLE', 1000, 1000)"
        )
        sqlite.execSQL(
            "INSERT INTO `play_history` (`listening_session_id`, `recording_id`, `queue_entry_id`, `started_at_epoch_ms`, `ended_at_epoch_ms`, `active_listened_ms`, `last_position_ms`, `completion_kind`, `chosen_by_user`, `scrobble_disposition`) " +
                "VALUES ('session-v5-1', 'recording-v5-1', 'queue-v5-1', 1000, 2000, 1000, 1000, 'NATURAL_END', 1, 'ENQUEUED')"
        )
        seeded.close()

        // Downgrade to v5 schema by dropping scrobble_disposition column
        SQLiteDatabase.openDatabase(
                context.getDatabasePath(ShippyR16Database.DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            .use { v5 ->
                v5.execSQL(
                    """
                    CREATE TABLE `play_history_v5` (
                        `listening_session_id` TEXT NOT NULL,
                        `recording_id` TEXT,
                        `queue_entry_id` TEXT NOT NULL,
                        `source_reference_id` TEXT,
                        `started_at_epoch_ms` INTEGER NOT NULL,
                        `ended_at_epoch_ms` INTEGER,
                        `active_listened_ms` INTEGER NOT NULL,
                        `last_position_ms` INTEGER NOT NULL,
                        `completion_kind` TEXT NOT NULL,
                        `chosen_by_user` INTEGER NOT NULL,
                        `snapshot_title` TEXT,
                        `snapshot_artist_display` TEXT,
                        `snapshot_artwork_location` TEXT,
                        PRIMARY KEY(`listening_session_id`),
                        FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """
                        .trimIndent()
                )
                v5.execSQL(
                    """
                    INSERT INTO `play_history_v5`
                    SELECT `listening_session_id`, `recording_id`, `queue_entry_id`, `source_reference_id`,
                           `started_at_epoch_ms`, `ended_at_epoch_ms`, `active_listened_ms`, `last_position_ms`,
                           `completion_kind`, `chosen_by_user`, `snapshot_title`, `snapshot_artist_display`,
                           `snapshot_artwork_location`
                    FROM `play_history`
                    """
                        .trimIndent()
                )
                v5.execSQL("DROP TABLE `play_history`")
                v5.execSQL("ALTER TABLE `play_history_v5` RENAME TO `play_history`")
                v5.execSQL(
                    "CREATE INDEX `index_play_history_recent` ON `play_history` (`started_at_epoch_ms` DESC)"
                )
                v5.execSQL(
                    "CREATE INDEX `index_play_history_recording` ON `play_history` (`recording_id` ASC, `started_at_epoch_ms` DESC)"
                )
                v5.execSQL("PRAGMA user_version = 5")
            }

        val runtime = R16DataRuntime.open(context)
        try {
            kotlinx.coroutines.runBlocking { runtime.ingestion.transaction { Unit } }
        } finally {
            runtime.close()
        }

        val migrated =
            Room.databaseBuilder(
                    context,
                    ShippyR16Database::class.java,
                    ShippyR16Database.DATABASE_NAME,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val migratedSqlite = migrated.openHelper.writableDatabase
            migratedSqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
            }
            migratedSqlite
                .query(
                    "SELECT scrobble_disposition FROM play_history WHERE listening_session_id = 'session-v5-1'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("LEGACY_UNKNOWN", cursor.getString(0))
                }
        } finally {
            migrated.close()
        }
    }
}
