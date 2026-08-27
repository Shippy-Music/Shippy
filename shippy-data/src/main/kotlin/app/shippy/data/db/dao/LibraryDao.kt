/*
 * Copyright (c) 2026 Auxio Project
 * LibraryDao.kt is part of Auxio.
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
import androidx.room.Upsert
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM library_recording WHERE recording_id = :recordingId")
    fun observeRelationship(recordingId: String): Flow<LibraryRecordingEntity?>

    @Query("SELECT * FROM library_recording WHERE recording_id = :recordingId")
    suspend fun relationship(recordingId: String): LibraryRecordingEntity?

    @Upsert suspend fun upsertRelationship(entity: LibraryRecordingEntity)

    @Query(
        """
        UPDATE library_recording SET liked = :liked, updated_at_epoch_ms = :updatedAtEpochMs
        WHERE recording_id = :recordingId
        """
    )
    suspend fun updateLiked(recordingId: String, liked: Boolean, updatedAtEpochMs: Long): Int

    @Query(
        """
        SELECT * FROM library_layout_entry
        ORDER BY pinned DESC, order_key, target_type, target_id
        """
    )
    fun observeLayout(): Flow<List<LibraryLayoutEntryEntity>>

    @Query(
        """
        SELECT * FROM library_layout_entry
        WHERE target_type = :targetType AND target_id = :targetId
        LIMIT 1
        """
    )
    suspend fun layoutEntry(targetType: String, targetId: String): LibraryLayoutEntryEntity?

    @Query("SELECT MAX(order_key) FROM library_layout_entry WHERE target_type = :targetType")
    suspend fun maxLayoutOrderKey(targetType: String): Long?

    @Query(
        """
        SELECT * FROM library_layout_entry
        WHERE target_type = :targetType
        ORDER BY order_key, target_id
        """
    )
    suspend fun layoutEntries(targetType: String): List<LibraryLayoutEntryEntity>

    @Upsert suspend fun upsertLayout(entries: List<LibraryLayoutEntryEntity>)

    @Query(
        """
        DELETE FROM library_layout_entry
        WHERE target_type = :targetType AND target_id = :targetId
        """
    )
    suspend fun deleteLayout(targetType: String, targetId: String): Int

    @Upsert suspend fun upsertOverride(entity: app.shippy.data.db.entity.UserMetadataOverrideEntity)

    @Query("DELETE FROM library_recording WHERE recording_id = :recordingId")
    suspend fun deleteRelationship(recordingId: String): Int

    @Query(
        """
        DELETE FROM user_metadata_override
        WHERE recording_id = :recordingId AND field_name = :fieldName
        """
    )
    suspend fun deleteOverride(recordingId: String, fieldName: String): Int

    @Query(
        "UPDATE user_metadata_override SET recording_id = :newRecordingId WHERE recording_id = :oldRecordingId AND field_name = :fieldName"
    )
    suspend fun reassignOverride(
        oldRecordingId: String,
        newRecordingId: String,
        fieldName: String,
    ): Int

    @Query("DELETE FROM user_metadata_override WHERE recording_id = :recordingId")
    suspend fun deleteAllOverrides(recordingId: String): Int

    @Query("SELECT * FROM user_metadata_override WHERE recording_id = :recordingId")
    suspend fun overridesFor(
        recordingId: String
    ): List<app.shippy.data.db.entity.UserMetadataOverrideEntity>

    @Query("SELECT * FROM user_metadata_override WHERE recording_id = :recordingId")
    fun observeOverrides(
        recordingId: String
    ): Flow<List<app.shippy.data.db.entity.UserMetadataOverrideEntity>>
}
