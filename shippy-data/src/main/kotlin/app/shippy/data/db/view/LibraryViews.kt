/*
 * Copyright (c) 2026 Auxio Project
 * LibraryViews.kt is part of Auxio.
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
package app.shippy.data.db.view

import androidx.room.ColumnInfo
import androidx.room.DatabaseView

@DatabaseView(
    viewName = "library_song_view",
    value =
        """
        SELECT
            r.recording_id AS recording_id,
            r.canonical_title AS title,
            COALESCE((
                SELECT GROUP_CONCAT(credit_part, '')
                FROM (
                    SELECT credited_name || join_phrase AS credit_part
                    FROM recording_artist_credit
                    WHERE recording_id = r.recording_id
                    ORDER BY position
                )
            ), '') AS artist_display,
            rel.canonical_title AS release_title,
            art.location AS artwork_location,
            r.duration_ms AS duration_ms,
            COALESCE(lr.liked, 0) AS liked,
            EXISTS(
                SELECT 1 FROM media_asset local_asset
                WHERE local_asset.recording_id = r.recording_id
                  AND local_asset.asset_kind = 'LOCAL_FILE'
                  AND local_asset.asset_state = 'AVAILABLE'
            ) AS local_asset_exists,
            EXISTS(
                SELECT 1 FROM media_asset download_asset
                WHERE download_asset.recording_id = r.recording_id
                  AND download_asset.asset_kind = 'SHIPPY_DOWNLOAD'
                  AND download_asset.asset_state = 'AVAILABLE'
            ) AS download_asset_exists,
            (
                SELECT MAX(history.started_at_epoch_ms)
                FROM play_history history
                WHERE history.recording_id = r.recording_id
            ) AS last_played_at_epoch_ms,
            lr.first_added_at_epoch_ms AS date_added_epoch_ms,
            LOWER(r.canonical_title) AS title_sort_key,
            LOWER(COALESCE((
                SELECT credited_name
                FROM recording_artist_credit
                WHERE recording_id = r.recording_id
                ORDER BY position
                LIMIT 1
            ), '')) AS artist_sort_key
        FROM recording r
        LEFT JOIN library_recording lr ON lr.recording_id = r.recording_id
        LEFT JOIN release rel ON rel.release_id = r.preferred_release_id
        LEFT JOIN artwork_reference art ON art.artwork_id = r.preferred_artwork_id
        """,
)
data class LibrarySongRowView(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    val title: String,
    @ColumnInfo(name = "artist_display") val artistDisplay: String,
    @ColumnInfo(name = "release_title") val releaseTitle: String?,
    @ColumnInfo(name = "artwork_location") val artworkLocation: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val liked: Boolean,
    @ColumnInfo(name = "local_asset_exists") val localAssetExists: Boolean,
    @ColumnInfo(name = "download_asset_exists") val downloadAssetExists: Boolean,
    @ColumnInfo(name = "last_played_at_epoch_ms") val lastPlayedAtEpochMs: Long?,
    @ColumnInfo(name = "date_added_epoch_ms") val dateAddedEpochMs: Long?,
    @ColumnInfo(name = "title_sort_key") val titleSortKey: String,
    @ColumnInfo(name = "artist_sort_key") val artistSortKey: String,
)

@DatabaseView(
    viewName = "playlist_entry_view",
    value =
        """
        SELECT
            entry.playlist_entry_id AS playlist_entry_id,
            entry.playlist_id AS playlist_id,
            entry.order_key AS order_key,
            entry.recording_id AS recording_id,
            song.title AS title,
            song.artist_display AS artist_display,
            song.release_title AS release_title,
            song.artwork_location AS artwork_location,
            song.duration_ms AS duration_ms,
            song.liked AS liked,
            (song.local_asset_exists OR song.download_asset_exists) AS offline_available,
            COALESCE((
                SELECT job.state
                FROM download_job job
                WHERE job.recording_id = entry.recording_id
                ORDER BY job.created_at_epoch_ms DESC, job.job_id DESC
                LIMIT 1
            ), 'NONE') AS download_state,
            CASE
                WHEN song.local_asset_exists OR song.download_asset_exists THEN 'AVAILABLE'
                WHEN EXISTS(
                    SELECT 1 FROM source_reference source
                    WHERE source.recording_id = entry.recording_id
                      AND source.availability_state IN ('AVAILABLE', 'RESOLVABLE')
                ) THEN 'RESOLVABLE'
                ELSE 'UNKNOWN'
            END AS availability_summary
        FROM playlist_entry entry
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        """,
)
data class PlaylistEntryRowView(
    @ColumnInfo(name = "playlist_entry_id") val playlistEntryId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "order_key") val orderKey: Long,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    val title: String,
    @ColumnInfo(name = "artist_display") val artistDisplay: String,
    @ColumnInfo(name = "release_title") val releaseTitle: String?,
    @ColumnInfo(name = "artwork_location") val artworkLocation: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val liked: Boolean,
    @ColumnInfo(name = "offline_available") val offlineAvailable: Boolean,
    @ColumnInfo(name = "download_state") val downloadState: String,
    @ColumnInfo(name = "availability_summary") val availabilitySummary: String,
)
