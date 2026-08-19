/*
 * Copyright (c) 2026 Auxio Project
 * RecordingDao.kt is part of Auxio.
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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.ExternalIdentifierEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.ReleaseEntity
import app.shippy.data.db.entity.ReleaseTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class RecordingDao {
    @Query("SELECT * FROM recording WHERE recording_id = :recordingId")
    abstract suspend fun get(recordingId: String): RecordingEntity?

    @Query("SELECT * FROM recording WHERE recording_id = :recordingId")
    abstract fun observe(recordingId: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recording WHERE recording_id IN (:recordingIds)")
    abstract fun observeMany(recordingIds: Set<String>): Flow<List<RecordingEntity>>

    @Query(
        "SELECT * FROM recording_artist_credit WHERE recording_id = :recordingId ORDER BY position"
    )
    abstract suspend fun artistCredits(recordingId: String): List<RecordingArtistCreditEntity>

    @Query("SELECT * FROM release WHERE release_id = :releaseId")
    abstract suspend fun release(releaseId: String): ReleaseEntity?

    @Query(
        """
        SELECT DISTINCT recording.* FROM recording
        JOIN external_identifier identifier
          ON identifier.owner_type = 'RECORDING'
         AND identifier.owner_id = recording.recording_id
        WHERE identifier.scheme || ':' || identifier.value IN (:identifierKeys)
        ORDER BY recording.recording_id
        LIMIT :limit
        """
    )
    abstract suspend fun candidatesByExternalIdentifier(
        identifierKeys: Set<String>,
        limit: Int,
    ): List<RecordingEntity>

    @Query(
        """
        SELECT DISTINCT recording.* FROM recording
        JOIN media_asset asset ON asset.recording_id = recording.recording_id
        WHERE asset.fingerprint_id IN (:fingerprintIds)
        ORDER BY recording.recording_id
        LIMIT :limit
        """
    )
    abstract suspend fun candidatesByFingerprint(
        fingerprintIds: Set<String>,
        limit: Int,
    ): List<RecordingEntity>

    @Query(
        """
        SELECT * FROM recording
        WHERE canonical_title = :title COLLATE NOCASE
        ORDER BY recording_id
        LIMIT :limit
        """
    )
    abstract suspend fun candidatesByTitle(title: String, limit: Int): List<RecordingEntity>

    @Query(
        """
        SELECT * FROM external_identifier
        WHERE owner_type = 'RECORDING' AND owner_id = :recordingId
        ORDER BY scheme, value
        """
    )
    abstract suspend fun externalIdentifiers(recordingId: String): List<ExternalIdentifierEntity>

    @Upsert protected abstract suspend fun upsertRecording(entity: RecordingEntity)

    @Upsert protected abstract suspend fun upsertArtists(entities: List<ArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCredits(entities: List<RecordingArtistCreditEntity>)

    @Query("DELETE FROM recording_artist_credit WHERE recording_id = :recordingId")
    protected abstract suspend fun deleteCredits(recordingId: String)

    @Upsert abstract suspend fun upsertRelease(entity: ReleaseEntity)

    @Upsert abstract suspend fun upsertReleaseTracks(entities: List<ReleaseTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertExternalIdentifiers(entities: List<ExternalIdentifierEntity>)

    @Transaction
    open suspend fun upsertRecordingGraph(
        recording: RecordingEntity,
        artists: List<ArtistEntity>,
        credits: List<RecordingArtistCreditEntity>,
    ) {
        require(artists.isNotEmpty()) { "Recording graph requires at least one artist" }
        require(credits.isNotEmpty()) { "Recording graph requires at least one artist credit" }
        require(credits.all { it.recordingId == recording.recordingId }) {
            "Every artist credit must belong to the recording"
        }
        val artistIds = artists.map(ArtistEntity::artistId).toSet()
        require(credits.all { it.artistId in artistIds }) {
            "Every artist credit must reference an included artist"
        }
        require(credits.map(RecordingArtistCreditEntity::position).toSet().size == credits.size) {
            "Artist credit positions must be unique"
        }
        upsertArtists(artists)
        upsertRecording(recording)
        deleteCredits(recording.recordingId)
        insertCredits(credits)
    }
}
