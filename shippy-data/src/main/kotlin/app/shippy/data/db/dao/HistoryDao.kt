/*
 * Copyright (c) 2026 Auxio Project
 * HistoryDao.kt is part of Auxio.
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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.home.R16HomeHistoryItem

@Dao
internal abstract class HistoryDao {
    @Upsert protected abstract suspend fun upsert(entity: PlayHistoryEntity)

    @Query(
        """
        SELECT * FROM play_history
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        LIMIT :limit
        """
    )
    abstract suspend fun recent(limit: Int): List<PlayHistoryEntity>

    @Query(
        """
        SELECT
            history.listening_session_id AS listening_session_id,
            history.recording_id AS recording_id,
            history.queue_entry_id AS queue_entry_id,
            history.started_at_epoch_ms AS started_at_epoch_ms,
            history.ended_at_epoch_ms AS ended_at_epoch_ms,
            COALESCE(song.title, history.snapshot_title, 'Unknown') AS title,
            COALESCE(song.artist_display, history.snapshot_artist_display, 'Unknown Artist') AS artist,
            song.release_title AS release_title,
            COALESCE(song.artwork_location, history.snapshot_artwork_location) AS artwork_location,
            CASE
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NOT NULL AND outbox.attempt_count > 0 THEN 'RETRYABLE_FAILURE'
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NOT NULL THEN 'PENDING'
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NULL THEN 'SENT'
                WHEN history.scrobble_disposition = 'NOT_AUTHORIZED' THEN 'NOT_AUTHORIZED'
                WHEN history.scrobble_disposition = 'INELIGIBLE_DURATION' THEN 'INELIGIBLE_DURATION'
                WHEN history.scrobble_disposition = 'INELIGIBLE_ACTIVE_TIME' THEN 'INELIGIBLE_ACTIVE_TIME'
                WHEN history.scrobble_disposition = 'METADATA_UNIDENTIFIED' THEN 'METADATA_UNIDENTIFIED'
                ELSE 'LEGACY_UNKNOWN'
            END AS scrobble_status
        FROM play_history history
        LEFT JOIN library_song_view song ON song.recording_id = history.recording_id
        LEFT JOIN lastfm_scrobble_outbox outbox ON outbox.listening_session_id = history.listening_session_id
        WHERE history.ended_at_epoch_ms IS NOT NULL
        ORDER BY history.started_at_epoch_ms DESC, history.listening_session_id DESC
        LIMIT :limit
        """
    )
    abstract suspend fun recentFinishedWithPresentation(limit: Int): List<R16HomeHistoryItem>

    @Query(
        """
        SELECT * FROM play_history
        WHERE recording_id = :recordingId
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        LIMIT :limit
        """
    )
    abstract suspend fun forRecording(recordingId: String, limit: Int): List<PlayHistoryEntity>

    @Query(
        """
        SELECT * FROM play_history
        WHERE recording_id = :recordingId
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        """
    )
    abstract suspend fun allForRecording(recordingId: String): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history WHERE listening_session_id = :listeningSessionId")
    abstract suspend fun session(listeningSessionId: String): PlayHistoryEntity?

    @Query(
        "UPDATE play_history SET recording_id = :newRecordingId WHERE listening_session_id = :listeningSessionId"
    )
    abstract suspend fun reassignHistorySessionRecordingId(
        listeningSessionId: String,
        newRecordingId: String,
    ): Int

    @Query(
        "UPDATE play_history SET recording_id = :newRecordingId WHERE recording_id = :oldRecordingId"
    )
    abstract suspend fun reassignHistoryForRecording(
        oldRecordingId: String,
        newRecordingId: String,
    ): Int

    @Query(
        """
        SELECT COALESCE(SUM(active_listened_ms), 0) FROM play_history
        WHERE recording_id = :recordingId
        """
    )
    abstract suspend fun totalActiveListenedMs(recordingId: String): Long

    @Query(
        """
        SELECT * FROM play_history
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        """
    )
    abstract fun page(): PagingSource<Int, PlayHistoryEntity>

    @Query(
        """
        SELECT
            history.listening_session_id AS listening_session_id,
            history.recording_id AS recording_id,
            history.queue_entry_id AS queue_entry_id,
            history.started_at_epoch_ms AS started_at_epoch_ms,
            history.ended_at_epoch_ms AS ended_at_epoch_ms,
            COALESCE(song.title, history.snapshot_title, 'Unknown') AS title,
            COALESCE(song.artist_display, history.snapshot_artist_display, 'Unknown Artist') AS artist,
            song.release_title AS release_title,
            COALESCE(song.artwork_location, history.snapshot_artwork_location) AS artwork_location,
            CASE
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NOT NULL AND outbox.attempt_count > 0 THEN 'RETRYABLE_FAILURE'
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NOT NULL THEN 'PENDING'
                WHEN history.scrobble_disposition = 'ENQUEUED' AND outbox.outbox_id IS NULL THEN 'SENT'
                WHEN history.scrobble_disposition = 'NOT_AUTHORIZED' THEN 'NOT_AUTHORIZED'
                WHEN history.scrobble_disposition = 'INELIGIBLE_DURATION' THEN 'INELIGIBLE_DURATION'
                WHEN history.scrobble_disposition = 'INELIGIBLE_ACTIVE_TIME' THEN 'INELIGIBLE_ACTIVE_TIME'
                WHEN history.scrobble_disposition = 'METADATA_UNIDENTIFIED' THEN 'METADATA_UNIDENTIFIED'
                ELSE 'LEGACY_UNKNOWN'
            END AS scrobble_status
        FROM play_history history
        LEFT JOIN library_song_view song ON song.recording_id = history.recording_id
        LEFT JOIN lastfm_scrobble_outbox outbox ON outbox.listening_session_id = history.listening_session_id
        ORDER BY history.started_at_epoch_ms DESC, history.listening_session_id DESC
        """
    )
    abstract fun historyWithPresentationPage(): PagingSource<Int, R16HomeHistoryItem>

    open suspend fun save(entity: PlayHistoryEntity) {
        require(entity.activeListenedMs >= 0) { "Active listening time cannot be negative" }
        require(entity.lastPositionMs >= 0) { "Last position cannot be negative" }
        require(entity.endedAtEpochMs == null || entity.endedAtEpochMs >= entity.startedAtEpochMs) {
            "Listening session cannot end before it starts"
        }
        require(entity.completionKind.isNotBlank()) { "Completion kind must not be blank" }
        upsert(entity)
    }
}
