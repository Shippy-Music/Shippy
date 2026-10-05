/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeReadRepository.kt is part of Auxio.
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
package app.shippy.data.home

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.dao.HomePinnedPlaylistRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Canonical metadata plus the durable listening-session identity needed by Home and History. */
data class R16HomeHistoryItem(
    @ColumnInfo(name = "listening_session_id") val listeningSessionId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String?,
    @ColumnInfo(name = "queue_entry_id") val queueEntryId: String,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_epoch_ms") val endedAtEpochMs: Long?,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "release_title") val releaseTitle: String?,
    @ColumnInfo(name = "artwork_location") val artworkLocation: String?,
    @ColumnInfo(name = "scrobble_status") val scrobbleStatus: String? = null,
)

/** A valid pinned user playlist, ordered by the canonical Library layout. */
data class R16HomePinnedPlaylistShortcut(val playlistId: String, val name: String)

/** Read-only Home and raw session History projections over the shared canonical data owner. */
interface R16HomeReadRepository {
    /** Returns at most eight distinct recordings from the newest 64 finished sessions. */
    suspend fun recentlyPlayed(): List<R16HomeHistoryItem>

    /** Observes the same bounded summary while Home is visible, including metadata changes. */
    fun observeRecentlyPlayed(): Flow<List<R16HomeHistoryItem>>

    /**
     * At most eight valid user-playlist shortcuts; system collections are deliberately excluded.
     */
    fun pinnedPlaylistShortcuts(): Flow<List<R16HomePinnedPlaylistShortcut>>

    /** Returns raw sessions in reverse chronological order; repeated recordings are preserved. */
    fun history(): PagingSource<Int, R16HomeHistoryItem>
}

internal class RoomR16HomeReadRepository(private val database: ShippyR16Database) :
    R16HomeReadRepository {
    override suspend fun recentlyPlayed(): List<R16HomeHistoryItem> =
        observeRecentlyPlayed().first()

    override fun observeRecentlyPlayed(): Flow<List<R16HomeHistoryItem>> =
        database
            .historyDao()
            .observeRecentFinishedWithPresentation(RECENT_HISTORY_SCAN_LIMIT)
            .map { sessions ->
                sessions
                    .distinctBy { it.recordingId ?: "session:${it.listeningSessionId}" }
                    .take(RECENTLY_PLAYED_LIMIT)
            }
            .distinctUntilChanged()

    override fun pinnedPlaylistShortcuts(): Flow<List<R16HomePinnedPlaylistShortcut>> =
        database
            .readModelDao()
            .observePinnedPlaylistShortcuts(PLAYLIST_LAYOUT_TARGET_TYPE, PINNED_SHORTCUT_LIMIT)
            .map { rows -> rows.toHomePinnedShortcuts() }

    override fun history(): PagingSource<Int, R16HomeHistoryItem> =
        database.historyDao().historyWithPresentationPage()

    private companion object {
        const val RECENT_HISTORY_SCAN_LIMIT = 64
        const val RECENTLY_PLAYED_LIMIT = 8
        const val PINNED_SHORTCUT_LIMIT = 8
        const val PLAYLIST_LAYOUT_TARGET_TYPE = "PLAYLIST"
    }
}

private fun List<HomePinnedPlaylistRow>.toHomePinnedShortcuts():
    List<R16HomePinnedPlaylistShortcut> = map { row ->
    R16HomePinnedPlaylistShortcut(row.playlistId, row.name)
}
