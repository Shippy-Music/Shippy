/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryReadRepository.kt is part of Auxio.
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
package app.shippy.data.library

import androidx.paging.PagingSource
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.db.view.PlaylistSummaryRowView

/**
 * Canonical, scoped Library Songs read path.
 *
 * A caller creates its own [androidx.paging.Pager] from the returned source. This keeps paging,
 * cancellation, and scroll state in the screen layer while Room owns query invalidation. No page is
 * ever composed into a whole-catalogue list here.
 */
interface R16LibraryReadRepository {
    fun songs(
        query: R16LibrarySongQuery = R16LibrarySongQuery()
    ): PagingSource<Int, LibrarySongRowView>

    /** Paged artists with at least one currently indexed Library recording. */
    fun artists(): PagingSource<Int, ArtistLibrarySummaryRow>

    /** Paged albums with at least one currently indexed Library recording. */
    fun albums(): PagingSource<Int, app.shippy.data.db.view.AlbumLibrarySummaryRow>

    /** Paged, distinct Library recordings in one canonical album. */
    fun albumSongs(releaseId: String): PagingSource<Int, LibrarySongRowView>

    /** Paged genres with at least one currently indexed Library recording. */
    fun genres(): PagingSource<Int, app.shippy.data.db.view.GenreLibrarySummaryRow>

    /** Paged Library recordings in one genre. */
    fun genreSongs(genre: String): PagingSource<Int, LibrarySongRowView>

    /** Paged artist targets scoped to durable Library recordings and the shared recording FTS. */
    fun searchArtists(query: R16LibrarySearchQuery): PagingSource<Int, ArtistLibrarySummaryRow>

    /** Paged playlist-name targets from the durable playlist-title FTS. */
    fun searchPlaylists(query: R16LibrarySearchQuery): PagingSource<Int, PlaylistSummaryRowView>

    /** Paged, distinct Library recordings credited to one canonical artist. */
    fun artistSongs(artistId: String): PagingSource<Int, LibrarySongRowView>

    /** Paged display read for an asset/relationship-derived built-in collection. */
    fun systemCollection(collection: R16SystemCollection): PagingSource<Int, LibrarySongRowView>

    fun systemCollection(
        collection: R16SystemCollection,
        query: R16LibrarySongQuery,
    ): PagingSource<Int, LibrarySongRowView>

    /** Paged display read for one playlist, preserving canonical occurrence order. */
    fun playlistEntries(
        playlistId: String,
        query: R16LibraryPlaylistQuery = R16LibraryPlaylistQuery(),
    ): PagingSource<Int, PlaylistEntryRowView>
}

/** A contextual Library Songs query. A blank query is the normal Songs list. */
data class R16LibrarySongQuery(val search: String? = null) {
    internal fun ftsMatchOrNull(): String? =
        search
            ?.let(LIBRARY_SEARCH_TOKEN::findAll)
            ?.map { it.value }
            ?.filterNot { it.uppercase() in LIBRARY_FTS_OPERATORS }
            ?.joinToString(" ") { "$it*" }
            ?.takeIf(String::isNotBlank)
}

/** A contextual playlist query. Search and persisted display sort are independent dimensions. */
data class R16LibraryPlaylistQuery(
    val search: String? = null,
    val sort: PlaylistSort = PlaylistSort(),
) {
    /** Reuse the same conservative FTS expression used by Library Songs. */
    internal fun ftsMatchOrNull(): String? = R16LibrarySongQuery(search).ftsMatchOrNull()
}

/** Shared, provider-free query input for the dedicated Library Search screen. */
data class R16LibrarySearchQuery(val text: String) {
    internal fun ftsMatchOrNull(): String? = R16LibrarySongQuery(text).ftsMatchOrNull()
}

internal class RoomR16LibraryReadRepository(private val database: ShippyR16Database) :
    R16LibraryReadRepository {
    override fun songs(query: R16LibrarySongQuery): PagingSource<Int, LibrarySongRowView> =
        when {
            query.search.isNullOrBlank() -> database.readModelDao().librarySongsPage()
            else ->
                query.ftsMatchOrNull()?.let(database.readModelDao()::searchLibrary)
                    ?: database.readModelDao().emptyLibrarySongsPage()
        }

    override fun artists(): PagingSource<Int, ArtistLibrarySummaryRow> =
        database.readModelDao().artistLibrarySummariesPage()

    override fun albums(): PagingSource<Int, app.shippy.data.db.view.AlbumLibrarySummaryRow> =
        database.readModelDao().albumLibrarySummariesPage()

    override fun albumSongs(releaseId: String): PagingSource<Int, LibrarySongRowView> =
        database.readModelDao().albumSongsPage(releaseId)

    override fun genres(): PagingSource<Int, app.shippy.data.db.view.GenreLibrarySummaryRow> =
        database.readModelDao().genreLibrarySummariesPage()

    override fun genreSongs(genre: String): PagingSource<Int, LibrarySongRowView> =
        database.readModelDao().genreSongsPage(genre)

    override fun searchArtists(
        query: R16LibrarySearchQuery
    ): PagingSource<Int, ArtistLibrarySummaryRow> =
        query.ftsMatchOrNull()?.let(database.readModelDao()::searchArtistLibrarySummaries)
            ?: database.readModelDao().emptyArtistLibrarySummariesPage()

    override fun searchPlaylists(
        query: R16LibrarySearchQuery
    ): PagingSource<Int, PlaylistSummaryRowView> =
        query.ftsMatchOrNull()?.let(database.readModelDao()::searchPlaylistSummaries)
            ?: database.readModelDao().emptyPlaylistSummariesPage()

    override fun artistSongs(artistId: String): PagingSource<Int, LibrarySongRowView> =
        database.readModelDao().artistSongsPage(artistId)

    override fun systemCollection(
        collection: R16SystemCollection
    ): PagingSource<Int, LibrarySongRowView> =
        when (collection) {
            R16SystemCollection.LIKED -> database.readModelDao().likedSongsPage()
            R16SystemCollection.DOWNLOADS -> database.readModelDao().downloadedSongsPage()
            R16SystemCollection.LOCAL -> database.readModelDao().localSongsPage()
        }

    override fun systemCollection(
        collection: R16SystemCollection,
        query: R16LibrarySongQuery,
    ): PagingSource<Int, LibrarySongRowView> =
        query.search?.trim()?.takeIf(String::isNotEmpty)?.let { search ->
            database.readModelDao().filterSystemCollection(collection.id, search)
        } ?: systemCollection(collection)

    override fun playlistEntries(
        playlistId: String,
        query: R16LibraryPlaylistQuery,
    ): PagingSource<Int, PlaylistEntryRowView> {
        val sort = query.sort.normalized()
        return when {
            query.search.isNullOrBlank() && sort.isCustom ->
                database.readModelDao().playlistPage(playlistId)
            query.search.isNullOrBlank() ->
                database
                    .readModelDao()
                    .sortedPlaylistPage(
                        playlistId = playlistId,
                        sortMode = sort.mode.wireValue,
                        sortDirection = sort.direction.wireValue,
                    )
            else ->
                query.search.trim().takeIf(String::isNotEmpty)?.let {
                    if (sort.isCustom) {
                        database.readModelDao().filterPlaylist(playlistId, it)
                    } else {
                        database
                            .readModelDao()
                            .filterSortedPlaylist(
                                playlistId = playlistId,
                                query = it,
                                sortMode = sort.mode.wireValue,
                                sortDirection = sort.direction.wireValue,
                            )
                    }
                } ?: database.readModelDao().emptyPlaylistPage()
        }
    }
}

/**
 * Converts user input into a conservative FTS prefix expression. Keeping only word tokens avoids
 * exposing FTS operators from UI text while retaining the normal title/artist word search path.
 */
private val LIBRARY_SEARCH_TOKEN = Regex("[\\p{L}\\p{N}]+")

/** SQLite FTS operators are discarded so user text cannot change the boolean query grammar. */
private val LIBRARY_FTS_OPERATORS = setOf("AND", "OR", "NOT", "NEAR")
