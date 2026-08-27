/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryMembershipTriggers.kt is part of Auxio.
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

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keeps the rebuildable Library page index transactionally aligned with its source tables. */
internal object R16LibraryMembershipTriggers : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        install(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        install(db)
    }

    fun install(db: SupportSQLiteDatabase) {
        if (hasCurrentTriggers(db)) return
        TRIGGER_NAMES.forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS `$name`") }
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_recording_title`
            AFTER UPDATE OF `canonical_title` ON `recording`
            BEGIN
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_library_insert`
            AFTER INSERT ON `library_recording`
            BEGIN
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_library_update`
            AFTER UPDATE OF `first_added_at_epoch_ms` ON `library_recording`
            BEGIN
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_library_delete`
            AFTER DELETE ON `library_recording`
            BEGIN
                ${refreshStatements("OLD.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_asset_insert`
            AFTER INSERT ON `media_asset`
            BEGIN
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_asset_update`
            AFTER UPDATE OF `recording_id`, `asset_kind`, `asset_state` ON `media_asset`
            BEGIN
                ${refreshStatements("OLD.recording_id")}
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_asset_delete`
            AFTER DELETE ON `media_asset`
            BEGIN
                ${refreshStatements("OLD.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `library_membership_index` (`recording_id`, `title_sort_key`)
            SELECT r.recording_id, LOWER(r.canonical_title)
            FROM recording r
            WHERE EXISTS(
                SELECT 1 FROM playlist_entry playlist_entry
                WHERE playlist_entry.recording_id = r.recording_id
            )
              AND NOT EXISTS(
                  SELECT 1 FROM library_membership_index member
                  WHERE member.recording_id = r.recording_id
              )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_playlist_entry_insert`
            AFTER INSERT ON `playlist_entry`
            BEGIN
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_playlist_entry_update`
            AFTER UPDATE OF `playlist_id`, `recording_id` ON `playlist_entry`
            BEGIN
                ${refreshStatements("OLD.recording_id")}
                ${refreshStatements("NEW.recording_id")}
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_library_membership_playlist_entry_delete`
            AFTER DELETE ON `playlist_entry`
            BEGIN
                ${refreshStatements("OLD.recording_id")}
            END
            """
                .trimIndent()
        )
    }

    private fun refreshStatements(recordingIdExpression: String): String =
        """
        UPDATE `library_membership_index`
        SET `title_sort_key` = (
            SELECT LOWER(r.canonical_title)
            FROM recording r
            WHERE r.recording_id = $recordingIdExpression
        )
        WHERE `recording_id` = $recordingIdExpression;
        INSERT INTO `library_membership_index` (`recording_id`, `title_sort_key`)
        SELECT r.recording_id, LOWER(r.canonical_title)
        FROM recording r
        WHERE r.recording_id = $recordingIdExpression
          AND (
              EXISTS(
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
          )
          AND NOT EXISTS(
              SELECT 1 FROM library_membership_index member
              WHERE member.recording_id = r.recording_id
          );
        DELETE FROM `library_membership_index`
        WHERE recording_id = $recordingIdExpression
          AND NOT EXISTS(
              SELECT 1
              FROM recording r
              WHERE r.recording_id = library_membership_index.recording_id
                AND (
                    EXISTS(
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
                )
          );
        """
            .trimIndent()

    private val TRIGGER_NAMES =
        listOf(
            "trigger_library_membership_recording_title",
            "trigger_library_membership_library_insert",
            "trigger_library_membership_library_update",
            "trigger_library_membership_library_delete",
            "trigger_library_membership_asset_insert",
            "trigger_library_membership_asset_update",
            "trigger_library_membership_asset_delete",
            "trigger_library_membership_playlist_entry_insert",
            "trigger_library_membership_playlist_entry_update",
            "trigger_library_membership_playlist_entry_delete",
        )

    private fun hasCurrentTriggers(db: SupportSQLiteDatabase): Boolean {
        val quotedNames = TRIGGER_NAMES.joinToString(", ") { "'$it'" }
        return db.query(
                "SELECT name, sql FROM sqlite_master " +
                    "WHERE type = 'trigger' AND name IN ($quotedNames)"
            )
            .use { cursor ->
                val found = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val sql = cursor.getString(1) ?: return@use false
                    if ("INSERT OR REPLACE" in sql) return@use false
                    found += cursor.getString(0)
                }
                found == TRIGGER_NAMES.toSet()
            }
    }
}
