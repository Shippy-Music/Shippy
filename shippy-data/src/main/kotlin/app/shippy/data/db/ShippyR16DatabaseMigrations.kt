/*
 * Copyright (c) 2026 Auxio Project
 * ShippyR16DatabaseMigrations.kt is part of Auxio.
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

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit, data-preserving schema transitions for the R16 database. */
internal object ShippyR16DatabaseMigrations {
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild both sides of the playlist foreign key so existing entries survive
                // without leaving SQL defaults that differ from Room's v2 schema contract.
                db.execSQL(
                    """
                    CREATE TABLE `playlist_new` (
                        `playlist_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `library_order_key` INTEGER NOT NULL,
                        `artwork_override` TEXT,
                        `display_sort_mode` TEXT NOT NULL,
                        `display_sort_direction` TEXT NOT NULL,
                        `origin_kind` TEXT NOT NULL,
                        `origin_key` TEXT,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_new` (
                        `playlist_id`, `name`, `pinned`, `library_order_key`, `artwork_override`,
                        `display_sort_mode`, `display_sort_direction`, `origin_kind`, `origin_key`,
                        `created_at_epoch_ms`, `updated_at_epoch_ms`
                    )
                    SELECT
                        `playlist_id`, `name`, `pinned`, `library_order_key`, `artwork_override`,
                        `display_sort_mode`, `display_sort_direction`, 'USER', NULL,
                        `created_at_epoch_ms`, `updated_at_epoch_ms`
                    FROM `playlist`
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `playlist_entry_new` (
                        `playlist_entry_id` TEXT NOT NULL,
                        `playlist_id` TEXT NOT NULL,
                        `recording_id` TEXT NOT NULL,
                        `order_key` INTEGER NOT NULL,
                        `added_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_entry_id`)
                    )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_entry_new` (
                        `playlist_entry_id`, `playlist_id`, `recording_id`, `order_key`, `added_at_epoch_ms`
                    )
                    SELECT
                        `playlist_entry_id`, `playlist_id`, `recording_id`, `order_key`, `added_at_epoch_ms`
                    FROM `playlist_entry`
                    """
                        .trimIndent()
                )
                db.execSQL("DROP TABLE `playlist_entry`")
                db.execSQL("DROP TABLE `playlist`")
                db.execSQL("ALTER TABLE `playlist_new` RENAME TO `playlist`")
                db.execSQL(
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
                db.execSQL(
                    """
                    INSERT INTO `playlist_entry` (
                        `playlist_entry_id`, `playlist_id`, `recording_id`, `order_key`, `added_at_epoch_ms`
                    )
                    SELECT
                        `playlist_entry_id`, `playlist_id`, `recording_id`, `order_key`, `added_at_epoch_ms`
                    FROM `playlist_entry_new`
                    """
                        .trimIndent()
                )
                db.execSQL("DROP TABLE `playlist_entry_new`")
                db.execSQL(
                    "CREATE INDEX `index_playlist_library_order` ON `playlist` (`pinned` DESC, `library_order_key` ASC)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_playlist_origin` ON `playlist` (`origin_kind`, `origin_key`)"
                )
                db.execSQL(
                    "CREATE INDEX `index_playlist_entry_order` ON `playlist_entry` (`playlist_id`, `order_key`)"
                )
                db.execSQL(
                    "CREATE INDEX `index_playlist_entry_recording` ON `playlist_entry` (`recording_id`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE `library_membership_index` (
                        `recording_id` TEXT NOT NULL,
                        `title_sort_key` TEXT NOT NULL,
                        PRIMARY KEY(`recording_id`),
                        FOREIGN KEY(`recording_id`) REFERENCES `recording`(`recording_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX `index_library_membership_order`
                    ON `library_membership_index` (`title_sort_key`, `recording_id`)
                    """
                        .trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX `index_library_membership_recording` ON `library_membership_index` (`recording_id`)"
                )
                db.execSQL(
                    """
                    INSERT INTO `library_membership_index` (`recording_id`, `title_sort_key`)
                    SELECT r.recording_id, LOWER(r.canonical_title)
                    FROM recording r
                    WHERE EXISTS(
                              SELECT 1 FROM library_recording lr
                              WHERE lr.recording_id = r.recording_id
                                AND lr.first_added_at_epoch_ms IS NOT NULL
                          )
                       OR EXISTS(
                              SELECT 1 FROM media_asset local_asset
                              WHERE local_asset.recording_id = r.recording_id
                                AND local_asset.asset_kind = 'LOCAL_FILE'
                                AND local_asset.asset_state = 'AVAILABLE'
                          )
                       OR EXISTS(
                              SELECT 1 FROM media_asset download_asset
                              WHERE download_asset.recording_id = r.recording_id
                                AND download_asset.asset_kind = 'SHIPPY_DOWNLOAD'
                                AND download_asset.asset_state = 'AVAILABLE'
                          )
                       OR EXISTS(
                              SELECT 1 FROM playlist_entry playlist_entry
                              WHERE playlist_entry.recording_id = r.recording_id
                          )
                    """
                        .trimIndent()
                )
                R16LibraryMembershipTriggers.install(db)
            }
        }

    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `playlist_fts`
                    USING FTS4(
                        `playlist_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        tokenize=unicode61,
                        notindexed=`playlist_id`
                    )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_fts` (`playlist_id`, `name`)
                    SELECT `playlist_id`, `name` FROM `playlist`
                    """
                        .trimIndent()
                )
                R16PlaylistSearchTriggers.install(db)
            }
        }

    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_job` ADD COLUMN `requested_media_variant` TEXT")
                db.execSQL("ALTER TABLE `download_job` ADD COLUMN `destination_identity` TEXT")
                db.execSQL(
                    "ALTER TABLE `lastfm_scrobble_outbox` ADD COLUMN `account_id` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `play_history_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `play_history_new` (
                        `listening_session_id`, `recording_id`, `queue_entry_id`, `source_reference_id`,
                        `started_at_epoch_ms`, `ended_at_epoch_ms`, `active_listened_ms`, `last_position_ms`,
                        `completion_kind`, `chosen_by_user`,
                        `snapshot_title`, `snapshot_artist_display`, `snapshot_artwork_location`
                    )
                    SELECT
                        h.`listening_session_id`,
                        h.`recording_id`,
                        h.`queue_entry_id`,
                        h.`source_reference_id`,
                        h.`started_at_epoch_ms`,
                        h.`ended_at_epoch_ms`,
                        h.`active_listened_ms`,
                        h.`last_position_ms`,
                        h.`completion_kind`,
                        h.`chosen_by_user`,
                        COALESCE(s.`title`, r.`canonical_title`, 'Unknown') AS `snapshot_title`,
                        COALESCE(s.`artist_display`, 'Unknown Artist') AS `snapshot_artist_display`,
                        s.`artwork_location` AS `snapshot_artwork_location`
                    FROM `play_history` h
                    LEFT JOIN `recording` r ON r.`recording_id` = h.`recording_id`
                    LEFT JOIN `library_song_view` s ON s.`recording_id` = h.`recording_id`
                    """
                        .trimIndent()
                )
                db.execSQL("DROP TABLE `play_history`")
                db.execSQL("ALTER TABLE `play_history_new` RENAME TO `play_history`")
                db.execSQL(
                    "CREATE INDEX `index_play_history_recent` ON `play_history` (`started_at_epoch_ms` DESC)"
                )
                db.execSQL(
                    "CREATE INDEX `index_play_history_recording` ON `play_history` (`recording_id` ASC, `started_at_epoch_ms` DESC)"
                )
            }
        }

    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `play_history` ADD COLUMN `scrobble_disposition` TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'"
                )
            }
        }
}
