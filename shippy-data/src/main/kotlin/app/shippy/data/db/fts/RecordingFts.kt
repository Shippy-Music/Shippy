/*
 * Copyright (c) 2026 Auxio Project
 * RecordingFts.kt is part of Auxio.
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
package app.shippy.data.db.fts

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["recording_id"])
@Entity(tableName = "recording_fts")
data class RecordingFtsEntity(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    val title: String,
    @ColumnInfo(name = "artist_names") val artistNames: String,
    @ColumnInfo(name = "release_title") val releaseTitle: String,
    val aliases: String,
    @ColumnInfo(name = "source_titles") val sourceTitles: String,
    @ColumnInfo(name = "user_override_text") val userOverrideText: String,
)

@Dao
internal abstract class SearchDao {
    @Query(
        """
        SELECT recording_id FROM recording_fts
        WHERE recording_fts MATCH :matchQuery
        LIMIT :limit
        """
    )
    abstract suspend fun searchRecordingIds(matchQuery: String, limit: Int): List<String>

    @Query(
        """
        SELECT
            r.recording_id AS recording_id,
            r.canonical_title AS title,
            COALESCE((
                SELECT GROUP_CONCAT(credited_name, ' ')
                FROM recording_artist_credit credit
                WHERE credit.recording_id = r.recording_id
            ), '') AS artist_names,
            COALESCE(rel.canonical_title, '') AS release_title,
            COALESCE((
                SELECT GROUP_CONCAT(identifier.value, ' ')
                FROM external_identifier identifier
                WHERE identifier.owner_type = 'RECORDING'
                  AND identifier.owner_id = r.recording_id
            ), '') AS aliases,
            COALESCE((
                SELECT GROUP_CONCAT(observation.title, ' ')
                FROM source_reference source
                JOIN metadata_observation observation
                  ON observation.observation_id = source.raw_metadata_observation_id
                WHERE source.recording_id = r.recording_id
            ), '') AS source_titles,
            COALESCE((
                SELECT GROUP_CONCAT(metadata.value_json, ' ')
                FROM user_metadata_override metadata
                WHERE metadata.recording_id = r.recording_id
            ), '') AS user_override_text
        FROM recording r
        LEFT JOIN release rel ON rel.release_id = r.preferred_release_id
        WHERE r.recording_id = :recordingId
        """
    )
    protected abstract suspend fun document(recordingId: String): RecordingFtsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: RecordingFtsEntity)

    @Query("DELETE FROM recording_fts WHERE recording_id = :recordingId")
    protected abstract suspend fun delete(recordingId: String)

    @Transaction
    open suspend fun refresh(recordingId: String) {
        delete(recordingId)
        document(recordingId)?.let { insert(it) }
    }
}
