/*
 * Copyright (c) 2026 Auxio Project
 * LibraryMembershipDao.kt is part of Auxio.
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
package app.shippy.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Maintains the rebuildable, title-ordered Library membership index. */
@Dao
internal abstract class LibraryMembershipDao {
    @Query(
        """
        UPDATE library_membership_index
        SET title_sort_key = (
            SELECT LOWER(r.canonical_title)
            FROM recording r
            WHERE r.recording_id = :recordingId
        )
        WHERE recording_id = :recordingId
        """
    )
    protected abstract fun updateIndexedTitle(recordingId: String)

    @Query(
        """
        INSERT INTO library_membership_index (recording_id, title_sort_key)
        SELECT r.recording_id, LOWER(r.canonical_title)
        FROM recording r
        WHERE r.recording_id = :recordingId
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
          )
        """
    )
    protected abstract fun insertIfMember(recordingId: String)

    @Query(
        """
        DELETE FROM library_membership_index
        WHERE recording_id = :recordingId
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
          )
        """
    )
    protected abstract fun deleteIfNotMember(recordingId: String)

    @Query("DELETE FROM library_membership_index") protected abstract fun clearInternal()

    @Query(
        """
        INSERT INTO library_membership_index (recording_id, title_sort_key)
        SELECT r.recording_id, LOWER(r.canonical_title)
        FROM recording r
        WHERE (
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
              )
        """
    )
    protected abstract fun insertAllMembers()

    @Transaction
    open fun refresh(recordingId: String) {
        updateIndexedTitle(recordingId)
        insertIfMember(recordingId)
        deleteIfNotMember(recordingId)
    }

    @Transaction
    open fun rebuild() {
        clearInternal()
        insertAllMembers()
    }
}
