/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistSearchTriggers.kt is part of Auxio.
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

/** Keeps the derived playlist-title FTS document aligned with every playlist write transaction. */
internal object R16PlaylistSearchTriggers : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = install(db)

    override fun onOpen(db: SupportSQLiteDatabase) = install(db)

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_playlist_fts_insert`
            AFTER INSERT ON `playlist`
            BEGIN
                INSERT INTO `playlist_fts` (`playlist_id`, `name`)
                VALUES (NEW.playlist_id, NEW.name);
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_playlist_fts_name_update`
            AFTER UPDATE OF `name` ON `playlist`
            BEGIN
                DELETE FROM `playlist_fts` WHERE playlist_id = OLD.playlist_id;
                INSERT INTO `playlist_fts` (`playlist_id`, `name`)
                VALUES (NEW.playlist_id, NEW.name);
            END
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `trigger_playlist_fts_delete`
            AFTER DELETE ON `playlist`
            BEGIN
                DELETE FROM `playlist_fts` WHERE playlist_id = OLD.playlist_id;
            END
            """
                .trimIndent()
        )
    }
}
