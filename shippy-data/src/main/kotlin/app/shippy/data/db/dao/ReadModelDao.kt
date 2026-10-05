/*
 * Copyright (c) 2026 Auxio Project
 * ReadModelDao.kt is part of Auxio.
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
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.db.view.PlaylistSummaryRowView
import kotlinx.coroutines.flow.Flow

data class PlaylistEntryPlaybackSeedRow(
    @ColumnInfo(name = "playlist_entry_id") val playlistEntryId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "order_key") val orderKey: Long,
)

/**
 * Bounded Home projection. Pinning and order come only from library_layout_entry; a playlist
 * remains eligible only while its canonical row exists.
 */
data class HomePinnedPlaylistRow(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
)

@Dao
internal interface ReadModelDao {
    @Query("SELECT * FROM library_song_view WHERE recording_id = :recordingId")
    suspend fun librarySong(recordingId: String): LibrarySongRowView?

    @Query(
        """
        SELECT playlist.playlist_id AS playlist_id, playlist.name AS name
        FROM library_layout_entry AS layout
        INNER JOIN playlist ON playlist.playlist_id = layout.target_id
        WHERE layout.target_type = :playlistTargetType AND layout.pinned = 1
        ORDER BY layout.order_key, layout.target_id
        LIMIT :limit
        """
    )
    fun observePinnedPlaylistShortcuts(
        playlistTargetType: String,
        limit: Int,
    ): Flow<List<HomePinnedPlaylistRow>>

    @Query(
        """
        SELECT song.*
        FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        ORDER BY member.title_sort_key, member.recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun librarySongs(limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT song.recording_id
        FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    suspend fun librarySongRecordingIdsForPlayback(): List<String>

    @Query(
        """
        SELECT song.recording_id
        FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        JOIN recording_fts search ON search.recording_id = song.recording_id
        WHERE recording_fts MATCH :query
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    suspend fun searchLibraryRecordingIdsForPlayback(query: String): List<String>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM library_song_view song
            JOIN library_membership_index member ON member.recording_id = song.recording_id
            ORDER BY member.title_sort_key, member.recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasLibrarySongAt(offset: Int): Boolean

    @Query(
        """
        SELECT
            artist.artist_id AS artist_id,
            artist.canonical_name AS canonical_name,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM artist
        JOIN recording_artist_credit credit ON credit.artist_id = artist.artist_id
        JOIN library_membership_index member ON member.recording_id = credit.recording_id
        GROUP BY artist.artist_id, artist.canonical_name
        ORDER BY LOWER(artist.canonical_name), artist.artist_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun artistLibrarySummaries(limit: Int, offset: Int): List<ArtistLibrarySummaryRow>

    @Query(
        """
        SELECT EXISTS(
            SELECT artist_id
            FROM (
                SELECT artist.artist_id AS artist_id, artist.canonical_name AS canonical_name
                FROM artist
                JOIN recording_artist_credit credit ON credit.artist_id = artist.artist_id
                JOIN library_membership_index member ON member.recording_id = credit.recording_id
                GROUP BY artist.artist_id, artist.canonical_name
                ORDER BY LOWER(canonical_name), artist_id
                LIMIT 1 OFFSET :offset
            )
        )
        """
    )
    suspend fun hasArtistLibrarySummaryAt(offset: Int): Boolean

    @Query(
        """
        SELECT
            artist.artist_id AS artist_id,
            artist.canonical_name AS canonical_name,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM artist
        JOIN recording_artist_credit credit ON credit.artist_id = artist.artist_id
        JOIN library_membership_index member ON member.recording_id = credit.recording_id
        WHERE artist.artist_id = :artistId
        GROUP BY artist.artist_id, artist.canonical_name
        LIMIT 1
        """
    )
    suspend fun artistLibrarySummary(artistId: String): ArtistLibrarySummaryRow?

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN recording_artist_credit credit ON credit.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE credit.artist_id = :artistId
        ORDER BY member.title_sort_key, member.recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun artistSongs(artistId: String, limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT recording_id
            FROM (
                SELECT DISTINCT
                    song.recording_id,
                    member.title_sort_key,
                    member.recording_id AS order_recording_id
                FROM library_song_view song
                JOIN recording_artist_credit credit ON credit.recording_id = song.recording_id
                JOIN library_membership_index member ON member.recording_id = song.recording_id
                WHERE credit.artist_id = :artistId
                ORDER BY member.title_sort_key, order_recording_id
                LIMIT 1 OFFSET :offset
            )
        )
        """
    )
    suspend fun hasArtistSongAt(artistId: String, offset: Int): Boolean

    @Query(
        """
        SELECT DISTINCT member.recording_id
        FROM recording_artist_credit credit
        JOIN library_membership_index member ON member.recording_id = credit.recording_id
        WHERE credit.artist_id = :artistId
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    suspend fun artistRecordingIdsForPlayback(artistId: String): List<String>

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN recording_artist_credit credit ON credit.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE credit.artist_id = :artistId AND song.recording_id = :recordingId
        LIMIT 1
        """
    )
    suspend fun artistSong(artistId: String, recordingId: String): LibrarySongRowView?

    @Query(
        """
        SELECT
            artist.artist_id AS artist_id,
            artist.canonical_name AS canonical_name,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM artist
        JOIN recording_artist_credit credit ON credit.artist_id = artist.artist_id
        JOIN library_membership_index member ON member.recording_id = credit.recording_id
        GROUP BY artist.artist_id, artist.canonical_name
        ORDER BY LOWER(artist.canonical_name), artist.artist_id
        """
    )
    fun artistLibrarySummariesPage(): PagingSource<Int, ArtistLibrarySummaryRow>

    @Query(
        """
        SELECT
            artist.artist_id AS artist_id,
            artist.canonical_name AS canonical_name,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM artist
        JOIN recording_artist_credit credit ON credit.artist_id = artist.artist_id
        JOIN library_membership_index member ON member.recording_id = credit.recording_id
        WHERE EXISTS(
            SELECT 1
            FROM recording_artist_credit match_credit
            JOIN library_membership_index match_member
                ON match_member.recording_id = match_credit.recording_id
            JOIN recording_fts search ON search.recording_id = match_credit.recording_id
            WHERE match_credit.artist_id = artist.artist_id
              AND recording_fts MATCH :query
        )
        GROUP BY artist.artist_id, artist.canonical_name
        ORDER BY LOWER(artist.canonical_name), artist.artist_id
        """
    )
    fun searchArtistLibrarySummaries(query: String): PagingSource<Int, ArtistLibrarySummaryRow>

    @Query(
        """
        SELECT artist_id, canonical_name, 0 AS recording_count
        FROM artist
        WHERE 0
        """
    )
    fun emptyArtistLibrarySummariesPage(): PagingSource<Int, ArtistLibrarySummaryRow>

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN recording_artist_credit credit ON credit.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE credit.artist_id = :artistId
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    fun artistSongsPage(artistId: String): PagingSource<Int, LibrarySongRowView>

    @Query("SELECT * FROM library_song_view WHERE 0")
    fun emptyArtistSongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT
            rel.release_id AS release_id,
            rel.canonical_title AS canonical_title,
            COALESCE((
                SELECT GROUP_CONCAT(credit_part, '')
                FROM (
                    SELECT credited_name || join_phrase AS credit_part
                    FROM recording_artist_credit
                    WHERE recording_id = r.recording_id
                    ORDER BY position
                )
            ), '') AS artist_display,
            art.location AS artwork_location,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM release rel
        JOIN recording r ON r.preferred_release_id = rel.release_id
        JOIN library_membership_index member ON member.recording_id = r.recording_id
        LEFT JOIN artwork_reference art ON art.artwork_id = rel.artwork_id
        GROUP BY rel.release_id, rel.canonical_title
        ORDER BY LOWER(rel.canonical_title), rel.release_id
        """
    )
    fun albumLibrarySummariesPage():
        PagingSource<Int, app.shippy.data.db.view.AlbumLibrarySummaryRow>

    @Query(
        """
        SELECT release_id, canonical_title, '' AS artist_display, NULL AS artwork_location, 0 AS recording_count
        FROM release
        WHERE 0
        """
    )
    fun emptyAlbumLibrarySummariesPage():
        PagingSource<Int, app.shippy.data.db.view.AlbumLibrarySummaryRow>

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN recording r ON r.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE r.preferred_release_id = :releaseId
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    fun albumSongsPage(releaseId: String): PagingSource<Int, LibrarySongRowView>

    @Query("SELECT canonical_title FROM release WHERE release_id = :releaseId LIMIT 1")
    suspend fun releaseTitle(releaseId: String): String?

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN recording r ON r.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE r.preferred_release_id = :releaseId AND song.recording_id = :recordingId
        LIMIT 1
        """
    )
    suspend fun albumSong(releaseId: String, recordingId: String): LibrarySongRowView?

    @Query(
        """
        SELECT DISTINCT song.recording_id
        FROM library_song_view song
        JOIN recording r ON r.recording_id = song.recording_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE r.preferred_release_id = :releaseId
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    suspend fun albumRecordingIdsForPlayback(releaseId: String): List<String>

    @Query(
        """
        SELECT
            COALESCE(obs.genres_json, 'Unknown') AS genre,
            COUNT(DISTINCT member.recording_id) AS recording_count
        FROM metadata_observation obs
        JOIN source_reference src ON src.raw_metadata_observation_id = obs.observation_id
        JOIN recording r ON r.recording_id = src.recording_id
        JOIN library_membership_index member ON member.recording_id = r.recording_id
        WHERE obs.genres_json IS NOT NULL AND obs.genres_json != ''
        GROUP BY obs.genres_json
        ORDER BY LOWER(obs.genres_json)
        """
    )
    fun genreLibrarySummariesPage():
        PagingSource<Int, app.shippy.data.db.view.GenreLibrarySummaryRow>

    @Query(
        """
        SELECT '' AS genre, 0 AS recording_count
        FROM metadata_observation
        WHERE 0
        """
    )
    fun emptyGenreLibrarySummariesPage():
        PagingSource<Int, app.shippy.data.db.view.GenreLibrarySummaryRow>

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN source_reference src ON src.recording_id = song.recording_id
        JOIN metadata_observation obs ON obs.observation_id = src.raw_metadata_observation_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE obs.genres_json = :genre
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    fun genreSongsPage(genre: String): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT DISTINCT song.*
        FROM library_song_view song
        JOIN source_reference src ON src.recording_id = song.recording_id
        JOIN metadata_observation obs ON obs.observation_id = src.raw_metadata_observation_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE obs.genres_json = :genre AND song.recording_id = :recordingId
        LIMIT 1
        """
    )
    suspend fun genreSong(genre: String, recordingId: String): LibrarySongRowView?

    @Query(
        """
        SELECT DISTINCT song.recording_id
        FROM library_song_view song
        JOIN source_reference src ON src.recording_id = song.recording_id
        JOIN metadata_observation obs ON obs.observation_id = src.raw_metadata_observation_id
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        WHERE obs.genres_json = :genre
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    suspend fun genreRecordingIdsForPlayback(genre: String): List<String>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE recording_id IN (:recordingIds)
        ORDER BY title_sort_key, recording_id
        """
    )
    fun observeLibrarySongs(recordingIds: Set<String>): Flow<List<LibrarySongRowView>>

    @Query(
        """
        SELECT * FROM playlist_entry_view
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    fun observePlaylistEntries(playlistId: String): Flow<List<PlaylistEntryRowView>>

    @Query(
        """
        SELECT playlist_entry_id, playlist_id, recording_id, order_key
        FROM playlist_entry
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    suspend fun playlistEntriesForPlayback(playlistId: String): List<PlaylistEntryPlaybackSeedRow>

    /** Full lightweight playback context for a playlist's current filter and display sort. */
    @Query(
        """
        SELECT entry.playlist_entry_id, entry.playlist_id, entry.recording_id, entry.order_key
        FROM playlist_entry entry
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId
          AND (
              :query IS NULL
              OR INSTR(LOWER(song.title), LOWER(:query)) > 0
              OR INSTR(LOWER(song.artist_display), LOWER(:query)) > 0
              OR INSTR(LOWER(COALESCE(song.release_title, '')), LOWER(:query)) > 0
          )
        ORDER BY
            CASE WHEN :sortMode = 'CUSTOM' THEN entry.order_key END ASC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                      THEN entry.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                      THEN entry.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'ASC'
                      THEN entry.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'DESC'
                      THEN entry.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                      THEN song.title_sort_key END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                      THEN song.title_sort_key END DESC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                      THEN song.artist_sort_key END ASC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                      THEN song.artist_sort_key END DESC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                      THEN LOWER(COALESCE(song.release_title, '')) END ASC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                      THEN LOWER(COALESCE(song.release_title, '')) END DESC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                      THEN COALESCE(song.duration_ms, -1) END ASC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                      THEN COALESCE(song.duration_ms, -1) END DESC,
            entry.playlist_entry_id ASC
        """
    )
    suspend fun playlistEntriesForPlaybackSorted(
        playlistId: String,
        query: String?,
        sortMode: String,
        sortDirection: String,
    ): List<PlaylistEntryPlaybackSeedRow>

    @Query(
        """
        SELECT *
        FROM playlist_entry_view
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun playlistEntriesPage(
        playlistId: String,
        limit: Int,
        offset: Int,
    ): List<PlaylistEntryRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM playlist_entry_view
            WHERE playlist_id = :playlistId
            ORDER BY order_key, playlist_entry_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasPlaylistEntryAt(playlistId: String, offset: Int): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM playlist_entry_view entry
            JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
            JOIN library_song_view song ON song.recording_id = entry.recording_id
            WHERE entry.playlist_id = :playlistId
              AND (
                  :query IS NULL OR entry.recording_id IN (
                      SELECT recording_id FROM recording_fts WHERE recording_fts MATCH :query
                  )
              )
            ORDER BY
                CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                          THEN raw.added_at_epoch_ms END DESC,
                CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                          THEN raw.added_at_epoch_ms END ASC,
                CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'ASC'
                          THEN raw.added_at_epoch_ms END ASC,
                CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'DESC'
                          THEN raw.added_at_epoch_ms END DESC,
                CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                          THEN song.title_sort_key END ASC,
                CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                          THEN song.title_sort_key END DESC,
                CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                          THEN song.artist_sort_key END ASC,
                CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                          THEN song.artist_sort_key END DESC,
                CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                          THEN LOWER(COALESCE(song.release_title, '')) END ASC,
                CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                          THEN LOWER(COALESCE(song.release_title, '')) END DESC,
                CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                          THEN COALESCE(song.duration_ms, -1) END ASC,
                CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                          THEN COALESCE(song.duration_ms, -1) END DESC,
                entry.playlist_entry_id ASC
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasSortedPlaylistEntryAt(
        playlistId: String,
        query: String?,
        sortMode: String,
        sortDirection: String,
        offset: Int,
    ): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM playlist_entry_view entry
            JOIN recording_fts search ON search.recording_id = entry.recording_id
            WHERE entry.playlist_id = :playlistId
              AND recording_fts MATCH :query
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasPlaylistSearchAt(playlistId: String, query: String, offset: Int): Boolean

    @Query("SELECT * FROM playlist_entry_view WHERE playlist_entry_id = :playlistEntryId LIMIT 1")
    suspend fun playlistEntry(playlistEntryId: String): PlaylistEntryRowView?

    @Query(
        """
        SELECT
            p.playlist_id AS playlist_id,
            p.name AS name,
            COALESCE(layout.pinned, 0) AS pinned,
            COALESCE(layout.order_key, 9223372036854775807) AS library_order_key,
            p.artwork_override AS artwork_override,
            p.display_sort_mode AS display_sort_mode,
            p.display_sort_direction AS display_sort_direction,
            COUNT(entry.playlist_entry_id) AS entry_count,
            COALESCE(SUM(song.duration_ms), 0) AS total_duration_ms
        FROM playlist p
        LEFT JOIN library_layout_entry layout
            ON layout.target_type = 'PLAYLIST' AND layout.target_id = p.playlist_id
        LEFT JOIN playlist_entry entry ON entry.playlist_id = p.playlist_id
        LEFT JOIN library_song_view song ON song.recording_id = entry.recording_id
        GROUP BY
            p.playlist_id,
            p.name,
            layout.pinned,
            layout.order_key,
            p.artwork_override,
            p.display_sort_mode,
            p.display_sort_direction
        ORDER BY COALESCE(layout.pinned, 0) DESC,
            COALESCE(layout.order_key, 9223372036854775807), p.playlist_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun playlistSummaries(limit: Int, offset: Int): List<PlaylistSummaryRowView>

    @Query(
        """
        SELECT
            p.playlist_id AS playlist_id,
            p.name AS name,
            COALESCE(layout.pinned, 0) AS pinned,
            COALESCE(layout.order_key, 9223372036854775807) AS library_order_key,
            p.artwork_override AS artwork_override,
            p.display_sort_mode AS display_sort_mode,
            p.display_sort_direction AS display_sort_direction,
            COUNT(entry.playlist_entry_id) AS entry_count,
            COALESCE(SUM(song.duration_ms), 0) AS total_duration_ms
        FROM playlist p
        JOIN playlist_fts search ON search.playlist_id = p.playlist_id
        LEFT JOIN library_layout_entry layout
            ON layout.target_type = 'PLAYLIST' AND layout.target_id = p.playlist_id
        LEFT JOIN playlist_entry entry ON entry.playlist_id = p.playlist_id
        LEFT JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE playlist_fts MATCH :query
        GROUP BY
            p.playlist_id,
            p.name,
            layout.pinned,
            layout.order_key,
            p.artwork_override,
            p.display_sort_mode,
            p.display_sort_direction
        ORDER BY COALESCE(layout.pinned, 0) DESC,
            COALESCE(layout.order_key, 9223372036854775807), p.playlist_id
        """
    )
    fun searchPlaylistSummaries(query: String): PagingSource<Int, PlaylistSummaryRowView>

    @Query(
        """
        SELECT
            playlist_id,
            name,
            pinned,
            library_order_key,
            artwork_override,
            display_sort_mode,
            display_sort_direction,
            0 AS entry_count,
            0 AS total_duration_ms
        FROM playlist
        WHERE 0
        """
    )
    fun emptyPlaylistSummariesPage(): PagingSource<Int, PlaylistSummaryRowView>

    @Query(
        """
        SELECT
            p.playlist_id AS playlist_id,
            p.name AS name,
            COALESCE(layout.pinned, 0) AS pinned,
            COALESCE(layout.order_key, 9223372036854775807) AS library_order_key,
            p.artwork_override AS artwork_override,
            p.display_sort_mode AS display_sort_mode,
            p.display_sort_direction AS display_sort_direction,
            COUNT(entry.playlist_entry_id) AS entry_count,
            COALESCE(SUM(song.duration_ms), 0) AS total_duration_ms
        FROM playlist p
        LEFT JOIN library_layout_entry layout
            ON layout.target_type = 'PLAYLIST' AND layout.target_id = p.playlist_id
        LEFT JOIN playlist_entry entry ON entry.playlist_id = p.playlist_id
        LEFT JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE p.playlist_id = :playlistId
        GROUP BY
            p.playlist_id,
            p.name,
            layout.pinned,
            layout.order_key,
            p.artwork_override,
            p.display_sort_mode,
            p.display_sort_direction
        LIMIT 1
        """
    )
    suspend fun playlistSummary(playlistId: String): PlaylistSummaryRowView?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM playlist p
            LEFT JOIN library_layout_entry layout
                ON layout.target_type = 'PLAYLIST' AND layout.target_id = p.playlist_id
            ORDER BY COALESCE(layout.pinned, 0) DESC,
                COALESCE(layout.order_key, 9223372036854775807), p.playlist_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasPlaylistSummaryAt(offset: Int): Boolean

    @Query(
        """
        SELECT song.*
        FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    fun librarySongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query("SELECT * FROM library_song_view WHERE 0")
    fun emptyLibrarySongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE liked
        ORDER BY title_sort_key, recording_id
        """
    )
    fun likedSongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE local_asset_exists
        ORDER BY title_sort_key, recording_id
        """
    )
    fun localSongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query(SYSTEM_COLLECTION_FILTER_PAGE)
    fun filterSystemCollection(
        collectionId: String,
        query: String?,
    ): PagingSource<Int, LibrarySongRowView>

    @Query(SYSTEM_COLLECTION_FILTER_PLAYBACK)
    suspend fun filteredSystemCollectionRecordingIdsForPlayback(
        collectionId: String,
        query: String?,
    ): List<String>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE download_asset_exists
        ORDER BY title_sort_key, recording_id
        """
    )
    fun downloadedSongsPage(): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE liked
        ORDER BY title_sort_key, recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun likedSongs(limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM library_song_view
            WHERE liked
            ORDER BY title_sort_key, recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasLikedSongAt(offset: Int): Boolean

    @Query(
        """
        SELECT recording_id FROM library_song_view
        WHERE liked
        ORDER BY title_sort_key, recording_id
        """
    )
    suspend fun likedRecordingIdsForPlayback(): List<String>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE local_asset_exists
        ORDER BY title_sort_key, recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun localSongs(limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM library_song_view
            WHERE local_asset_exists
            ORDER BY title_sort_key, recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasLocalSongAt(offset: Int): Boolean

    @Query(
        """
        SELECT recording_id FROM library_song_view
        WHERE local_asset_exists
        ORDER BY title_sort_key, recording_id
        """
    )
    suspend fun localRecordingIdsForPlayback(): List<String>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE download_asset_exists
        ORDER BY title_sort_key, recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun downloadedSongs(limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM library_song_view
            WHERE download_asset_exists
            ORDER BY title_sort_key, recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasDownloadedSongAt(offset: Int): Boolean

    @Query(
        """
        SELECT recording_id FROM library_song_view
        WHERE download_asset_exists
        ORDER BY title_sort_key, recording_id
        """
    )
    suspend fun downloadedRecordingIdsForPlayback(): List<String>

    @Query(
        """
        SELECT * FROM playlist_entry_view
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    fun playlistPage(playlistId: String): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT entry.*
        FROM playlist_entry_view entry
        JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId
        ORDER BY
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                      THEN song.title_sort_key END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                      THEN song.title_sort_key END DESC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                      THEN song.artist_sort_key END ASC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                      THEN song.artist_sort_key END DESC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                      THEN LOWER(COALESCE(song.release_title, '')) END ASC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                      THEN LOWER(COALESCE(song.release_title, '')) END DESC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                      THEN COALESCE(song.duration_ms, -1) END ASC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                      THEN COALESCE(song.duration_ms, -1) END DESC,
            entry.playlist_entry_id ASC
        """
    )
    fun sortedPlaylistPage(
        playlistId: String,
        sortMode: String,
        sortDirection: String,
    ): PagingSource<Int, PlaylistEntryRowView>

    /** Bounded browser projection; the UI PagingSource above remains independent. */
    @Query(
        """
        SELECT entry.*
        FROM playlist_entry_view entry
        JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId
        ORDER BY
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                      THEN song.title_sort_key END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                      THEN song.title_sort_key END DESC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                      THEN song.artist_sort_key END ASC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                      THEN song.artist_sort_key END DESC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                      THEN LOWER(COALESCE(song.release_title, '')) END ASC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                      THEN LOWER(COALESCE(song.release_title, '')) END DESC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                      THEN COALESCE(song.duration_ms, -1) END ASC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                      THEN COALESCE(song.duration_ms, -1) END DESC,
            entry.playlist_entry_id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun sortedPlaylistEntriesPage(
        playlistId: String,
        sortMode: String,
        sortDirection: String,
        limit: Int,
        offset: Int,
    ): List<PlaylistEntryRowView>

    @Query("SELECT * FROM playlist_entry_view WHERE 0")
    fun emptyPlaylistPage(): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT song.* FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        JOIN recording_fts search ON search.recording_id = song.recording_id
        WHERE recording_fts MATCH :query
        ORDER BY member.title_sort_key, member.recording_id
        """
    )
    fun searchLibrary(query: String): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT song.*
        FROM library_song_view song
        JOIN library_membership_index member ON member.recording_id = song.recording_id
        JOIN recording_fts search ON search.recording_id = song.recording_id
        WHERE recording_fts MATCH :query
        ORDER BY member.title_sort_key, member.recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchLibraryPage(query: String, limit: Int, offset: Int): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM library_song_view song
            JOIN library_membership_index member ON member.recording_id = song.recording_id
            JOIN recording_fts search ON search.recording_id = song.recording_id
            WHERE recording_fts MATCH :query
            ORDER BY member.title_sort_key, member.recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasLibrarySearchAt(query: String, offset: Int): Boolean

    @Query(
        """
        SELECT song.*
        FROM library_song_view song
        JOIN recording_fts search ON search.recording_id = song.recording_id
        WHERE recording_fts MATCH :query
        ORDER BY song.title_sort_key, song.recording_id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchCanonicalPage(
        query: String,
        limit: Int,
        offset: Int,
    ): List<LibrarySongRowView>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM library_song_view song
            JOIN recording_fts search ON search.recording_id = song.recording_id
            WHERE recording_fts MATCH :query
            ORDER BY song.title_sort_key, song.recording_id
            LIMIT 1 OFFSET :offset
        )
        """
    )
    suspend fun hasCanonicalSearchAt(query: String, offset: Int): Boolean

    @Query(
        """
        SELECT entry.* FROM playlist_entry_view entry
        JOIN recording_fts search ON search.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId AND recording_fts MATCH :query
        ORDER BY entry.order_key, entry.playlist_entry_id
        """
    )
    fun searchPlaylist(playlistId: String, query: String): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT entry.* FROM playlist_entry_view entry
        WHERE entry.playlist_id = :playlistId
          AND (
              INSTR(LOWER(entry.title), LOWER(:query)) > 0
              OR INSTR(LOWER(entry.artist_display), LOWER(:query)) > 0
              OR INSTR(LOWER(COALESCE(entry.release_title, '')), LOWER(:query)) > 0
          )
        ORDER BY entry.order_key, entry.playlist_entry_id
        """
    )
    fun filterPlaylist(playlistId: String, query: String): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT entry.* FROM playlist_entry_view entry
        JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        JOIN recording_fts search ON search.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId AND recording_fts MATCH :query
        ORDER BY
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'OLDEST_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                      THEN song.title_sort_key END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                      THEN song.title_sort_key END DESC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                      THEN song.artist_sort_key END ASC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                      THEN song.artist_sort_key END DESC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                      THEN LOWER(COALESCE(song.release_title, '')) END ASC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                      THEN LOWER(COALESCE(song.release_title, '')) END DESC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                      THEN COALESCE(song.duration_ms, -1) END ASC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                      THEN COALESCE(song.duration_ms, -1) END DESC,
            entry.playlist_entry_id ASC
        """
    )
    fun searchSortedPlaylist(
        playlistId: String,
        query: String,
        sortMode: String,
        sortDirection: String,
    ): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT entry.* FROM playlist_entry_view entry
        JOIN playlist_entry raw ON raw.playlist_entry_id = entry.playlist_entry_id
        JOIN library_song_view song ON song.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId
          AND (
              INSTR(LOWER(entry.title), LOWER(:query)) > 0
              OR INSTR(LOWER(entry.artist_display), LOWER(:query)) > 0
              OR INSTR(LOWER(COALESCE(entry.release_title, '')), LOWER(:query)) > 0
          )
        ORDER BY
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'ASC'
                      THEN raw.added_at_epoch_ms END DESC,
            CASE WHEN :sortMode = 'RECENTLY_ADDED' AND :sortDirection = 'DESC'
                      THEN raw.added_at_epoch_ms END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'ASC'
                      THEN song.title_sort_key END ASC,
            CASE WHEN :sortMode = 'TITLE' AND :sortDirection = 'DESC'
                      THEN song.title_sort_key END DESC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'ASC'
                      THEN song.artist_sort_key END ASC,
            CASE WHEN :sortMode = 'ARTIST' AND :sortDirection = 'DESC'
                      THEN song.artist_sort_key END DESC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'ASC'
                      THEN LOWER(COALESCE(song.release_title, '')) END ASC,
            CASE WHEN :sortMode = 'ALBUM' AND :sortDirection = 'DESC'
                      THEN LOWER(COALESCE(song.release_title, '')) END DESC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'ASC'
                      THEN COALESCE(song.duration_ms, -1) END ASC,
            CASE WHEN :sortMode = 'DURATION' AND :sortDirection = 'DESC'
                      THEN COALESCE(song.duration_ms, -1) END DESC,
            entry.playlist_entry_id ASC
        """
    )
    fun filterSortedPlaylist(
        playlistId: String,
        query: String,
        sortMode: String,
        sortDirection: String,
    ): PagingSource<Int, PlaylistEntryRowView>
}
