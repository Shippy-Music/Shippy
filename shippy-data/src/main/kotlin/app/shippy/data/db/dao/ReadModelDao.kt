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
import androidx.room.Dao
import androidx.room.Query
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistEntryRowView
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ReadModelDao {
    @Query("SELECT * FROM library_song_view WHERE recording_id = :recordingId")
    suspend fun librarySong(recordingId: String): LibrarySongRowView?

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
        SELECT * FROM playlist_entry_view
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    suspend fun playlistEntries(playlistId: String): List<PlaylistEntryRowView>

    @Query(
        """
        SELECT * FROM library_song_view
        WHERE date_added_epoch_ms IS NOT NULL OR local_asset_exists OR download_asset_exists
        ORDER BY title_sort_key, recording_id
        """
    )
    fun librarySongsPage(): PagingSource<Int, LibrarySongRowView>

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
        SELECT * FROM playlist_entry_view
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    fun playlistPage(playlistId: String): PagingSource<Int, PlaylistEntryRowView>

    @Query(
        """
        SELECT song.* FROM library_song_view song
        JOIN recording_fts search ON search.recording_id = song.recording_id
        WHERE recording_fts MATCH :query
          AND (song.date_added_epoch_ms IS NOT NULL OR song.local_asset_exists OR song.download_asset_exists)
        ORDER BY song.title_sort_key, song.recording_id
        """
    )
    fun searchLibrary(query: String): PagingSource<Int, LibrarySongRowView>

    @Query(
        """
        SELECT entry.* FROM playlist_entry_view entry
        JOIN recording_fts search ON search.recording_id = entry.recording_id
        WHERE entry.playlist_id = :playlistId AND recording_fts MATCH :query
        ORDER BY entry.order_key, entry.playlist_entry_id
        """
    )
    fun searchPlaylist(playlistId: String, query: String): PagingSource<Int, PlaylistEntryRowView>
}
