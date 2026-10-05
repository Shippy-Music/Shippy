/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowser.kt is part of Auxio.
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
package app.shippy.data.browser

import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.dao.PlaylistEntryPlaybackSeedRow
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.db.view.PlaylistSummaryRowView
import app.shippy.data.library.R16LibrarySongQuery

/**
 * A finite request. The repository clamps [pageSize] to [R16MediaBrowserRepository.MAX_PAGE_SIZE].
 */
data class R16MediaBrowserPageRequest(
    val offset: Int = 0,
    val pageSize: Int = R16MediaBrowserRepository.DEFAULT_PAGE_SIZE,
) {
    init {
        require(offset >= 0) { "offset must be non-negative" }
        require(pageSize > 0) { "pageSize must be positive" }
    }

    internal val boundedPageSize: Int
        get() = pageSize.coerceAtMost(R16MediaBrowserRepository.MAX_PAGE_SIZE)
}

data class R16MediaBrowserPage<out Item>(
    val items: List<Item>,
    val offset: Int,
    val pageSize: Int,
    val hasNext: Boolean,
) {
    val nextOffset: Int?
        get() = if (hasNext) offset + items.size else null
}

data class R16MediaBrowserRecording(
    val recordingId: RecordingId,
    val title: String,
    val artistDisplay: String,
    val releaseTitle: String?,
    val artworkLocation: String?,
    val durationMs: Long?,
    val liked: Boolean,
    val localAssetExists: Boolean,
    val downloadAssetExists: Boolean,
)

data class R16MediaBrowserArtistSummary(
    val artistId: ArtistId,
    val canonicalName: String,
    val recordingCount: Int,
)

data class R16MediaBrowserPlaylistSummary(
    val playlistId: PlaylistId,
    val name: String,
    val pinned: Boolean,
    val libraryOrderKey: Long,
    val artworkOverride: String?,
    val displaySortMode: String,
    val displaySortDirection: String,
    val entryCount: Int,
    val totalDurationMs: Long,
) {
    /** Typed view of the persisted wire values; unknown/legacy values safely become Custom. */
    val displaySort: PlaylistSort
        get() = PlaylistSort.fromWire(displaySortMode, displaySortDirection)
}

data class R16MediaBrowserPlaylistEntry(
    val playlistEntryId: PlaylistEntryId,
    val playlistId: PlaylistId,
    val recordingId: RecordingId,
    val orderKey: Long,
    val title: String,
    val artistDisplay: String,
    val releaseTitle: String?,
    val artworkLocation: String?,
    val durationMs: Long?,
    val liked: Boolean,
    val offlineAvailable: Boolean,
    val downloadState: String,
    val availabilitySummary: String,
)

/** Minimal ordered playlist occurrence used to build a playback queue without rich row work. */
data class R16MediaBrowserPlaylistPlaybackSeed(
    val playlistEntryId: PlaylistEntryId,
    val playlistId: PlaylistId,
    val recordingId: RecordingId,
    val orderKey: Long,
)

/** The exact playlist context selected by a Library detail surface for playback. */
data class R16MediaBrowserPlaylistPlaybackContext(
    val search: String? = null,
    val sort: PlaylistSort = PlaylistSort(),
)

/** An exact item lookup result; occurrences retain PlaylistEntryId even when recordings repeat. */
sealed interface R16MediaBrowserItem {
    val mediaId: String

    data object Root : R16MediaBrowserItem {
        override val mediaId: String = R16MediaBrowserIdCodec.ROOT
    }

    data object LibrarySongs : R16MediaBrowserItem {
        override val mediaId: String = R16MediaBrowserIdCodec.LIBRARY_SONGS
    }

    data object LibraryArtists : R16MediaBrowserItem {
        override val mediaId: String = R16MediaBrowserIdCodec.LIBRARY_ARTISTS
    }

    data class SystemCollection(val collection: R16SystemCollection) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.SystemCollection(collection))
    }

    data class SystemCollectionRecording(
        val collection: R16SystemCollection,
        val recording: R16MediaBrowserRecording,
    ) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.SystemCollectionRecording(collection, recording.recordingId)
            )
    }

    data class Playlist(val summary: R16MediaBrowserPlaylistSummary) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(summary.playlistId))
    }

    data class PlaylistEntry(val entry: R16MediaBrowserPlaylistEntry) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.PlaylistEntry(entry.playlistEntryId))
    }

    data class Recording(val recording: R16MediaBrowserRecording) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recording.recordingId))
    }

    data class Artist(val summary: R16MediaBrowserArtistSummary) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Artist(summary.artistId))
    }

    data class ArtistRecording(val artistId: ArtistId, val recording: R16MediaBrowserRecording) :
        R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.ArtistRecording(artistId, recording.recordingId)
            )
    }

    data class Album(val releaseId: app.shippy.core.identity.ReleaseId, val title: String) :
        R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Album(releaseId))
    }

    data class AlbumRecording(
        val releaseId: app.shippy.core.identity.ReleaseId,
        val recording: R16MediaBrowserRecording,
    ) : R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.AlbumRecording(releaseId, recording.recordingId)
            )
    }

    data class Genre(val name: String) : R16MediaBrowserItem {
        override val mediaId: String = R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Genre(name))
    }

    data class GenreRecording(val genre: String, val recording: R16MediaBrowserRecording) :
        R16MediaBrowserItem {
        override val mediaId: String =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.GenreRecording(genre, recording.recordingId)
            )
    }
}

interface R16MediaBrowserRepository {
    suspend fun librarySongs(
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest()
    ): R16MediaBrowserPage<R16MediaBrowserRecording>

    /** Lightweight ordered recording IDs for the Songs surface; never materializes rich rows. */
    suspend fun librarySongRecordingIdsForPlayback(query: String? = null): List<RecordingId> =
        emptyList()

    suspend fun libraryArtists(
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest()
    ): R16MediaBrowserPage<R16MediaBrowserArtistSummary>

    suspend fun artistRecordings(
        artistId: ArtistId,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
    ): R16MediaBrowserPage<R16MediaBrowserRecording>

    /** Lightweight ordered IDs for one artist; display rows are intentionally not materialized. */
    suspend fun artistRecordingIdsForPlayback(artistId: ArtistId): List<RecordingId>

    /**
     * Lightweight ordered IDs for one album/release; display rows are intentionally not
     * materialized.
     */
    suspend fun albumRecordingIdsForPlayback(
        releaseId: app.shippy.core.identity.ReleaseId
    ): List<RecordingId> = emptyList()

    /** Lightweight ordered IDs for one genre; display rows are intentionally not materialized. */
    suspend fun genreRecordingIdsForPlayback(genre: String): List<RecordingId> = emptyList()

    suspend fun playlistSummaries(
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest()
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistSummary>

    suspend fun systemCollection(
        collection: R16SystemCollection,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
    ): R16MediaBrowserPage<R16MediaBrowserRecording> =
        R16MediaBrowserPage(emptyList(), page.offset, page.boundedPageSize, hasNext = false)

    /** Lightweight ordered identity context for Play/Shuffle; never a display model. */
    suspend fun systemCollectionRecordingIdsForPlayback(
        collection: R16SystemCollection
    ): List<RecordingId> = emptyList()

    suspend fun systemCollectionRecordingIdsForPlayback(
        collection: R16SystemCollection,
        query: String?,
    ): List<RecordingId> = systemCollectionRecordingIdsForPlayback(collection)

    suspend fun playlistEntries(
        playlistId: PlaylistId,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistEntry>

    /** Optional typed display order for browser children; legacy callers retain Custom order. */
    suspend fun playlistEntries(
        playlistId: PlaylistId,
        page: R16MediaBrowserPageRequest,
        sort: PlaylistSort,
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistEntry> = playlistEntries(playlistId, page)

    /**
     * Returns the complete ordered occurrence context for playback. Unlike [playlistEntries], this
     * is not a visible browser page and must not be used by display surfaces.
     */
    suspend fun playlistEntriesForPlayback(
        playlistId: PlaylistId
    ): List<R16MediaBrowserPlaylistPlaybackSeed>

    /** Full lightweight context for the selected filter and sort; never limited to 50 rows. */
    suspend fun playlistEntriesForPlayback(
        playlistId: PlaylistId,
        context: R16MediaBrowserPlaylistPlaybackContext,
    ): List<R16MediaBrowserPlaylistPlaybackSeed> = playlistEntriesForPlayback(playlistId)

    suspend fun recording(recordingId: RecordingId): R16MediaBrowserRecording?

    suspend fun playlistEntry(playlistEntryId: PlaylistEntryId): R16MediaBrowserPlaylistEntry?

    suspend fun item(mediaId: String): R16MediaBrowserItem?

    suspend fun searchLibrary(
        query: String,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
    ): R16MediaBrowserPage<R16MediaBrowserRecording>

    /** Bounded canonical-catalogue FTS query used only by Global Search. */
    suspend fun searchCanonical(
        query: String,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
    ): R16MediaBrowserPage<R16MediaBrowserRecording> = searchLibrary(query, page)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 50
    }
}

internal class RoomR16MediaBrowserRepository(private val database: ShippyR16Database) :
    R16MediaBrowserRepository {
    private val dao
        get() = database.readModelDao()

    override suspend fun librarySongs(
        page: R16MediaBrowserPageRequest
    ): R16MediaBrowserPage<R16MediaBrowserRecording> {
        val request = page.normalized()
        val rows = dao.librarySongs(request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasLibrarySongAt(nextOffset) },
            map = LibrarySongRowView::toBrowserRecording,
        )
    }

    override suspend fun librarySongRecordingIdsForPlayback(query: String?): List<RecordingId> {
        val trimmed = query?.trim()?.takeIf(String::isNotEmpty)
        if (trimmed == null) {
            return dao.librarySongRecordingIdsForPlayback().map(::RecordingId)
        }
        val match = R16LibrarySongQuery(trimmed).ftsMatchOrNull() ?: return emptyList()
        return dao.searchLibraryRecordingIdsForPlayback(match).map(::RecordingId)
    }

    override suspend fun libraryArtists(
        page: R16MediaBrowserPageRequest
    ): R16MediaBrowserPage<R16MediaBrowserArtistSummary> {
        val request = page.normalized()
        val rows = dao.artistLibrarySummaries(request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasArtistLibrarySummaryAt(nextOffset) },
            map = ArtistLibrarySummaryRow::toBrowserArtistSummary,
        )
    }

    override suspend fun artistRecordings(
        artistId: ArtistId,
        page: R16MediaBrowserPageRequest,
    ): R16MediaBrowserPage<R16MediaBrowserRecording> {
        val request = page.normalized()
        val rows = dao.artistSongs(artistId.value, request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasArtistSongAt(artistId.value, nextOffset) },
            map = LibrarySongRowView::toBrowserRecording,
        )
    }

    override suspend fun artistRecordingIdsForPlayback(artistId: ArtistId): List<RecordingId> =
        dao.artistRecordingIdsForPlayback(artistId.value).map(::RecordingId)

    override suspend fun albumRecordingIdsForPlayback(
        releaseId: app.shippy.core.identity.ReleaseId
    ): List<RecordingId> = dao.albumRecordingIdsForPlayback(releaseId.value).map(::RecordingId)

    override suspend fun genreRecordingIdsForPlayback(genre: String): List<RecordingId> =
        dao.genreRecordingIdsForPlayback(genre).map(::RecordingId)

    override suspend fun playlistSummaries(
        page: R16MediaBrowserPageRequest
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistSummary> {
        val request = page.normalized()
        val rows = dao.playlistSummaries(request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasPlaylistSummaryAt(nextOffset) },
            map = PlaylistSummaryRowView::toBrowserSummary,
        )
    }

    override suspend fun systemCollection(
        collection: R16SystemCollection,
        page: R16MediaBrowserPageRequest,
    ): R16MediaBrowserPage<R16MediaBrowserRecording> {
        val request = page.normalized()
        val rows =
            when (collection) {
                R16SystemCollection.LIKED -> dao.likedSongs(request.pageSize, request.offset)
                R16SystemCollection.DOWNLOADS ->
                    dao.downloadedSongs(request.pageSize, request.offset)
                R16SystemCollection.LOCAL -> dao.localSongs(request.pageSize, request.offset)
            }
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset ->
                when (collection) {
                    R16SystemCollection.LIKED -> dao.hasLikedSongAt(nextOffset)
                    R16SystemCollection.DOWNLOADS -> dao.hasDownloadedSongAt(nextOffset)
                    R16SystemCollection.LOCAL -> dao.hasLocalSongAt(nextOffset)
                }
            },
            map = LibrarySongRowView::toBrowserRecording,
        )
    }

    override suspend fun systemCollectionRecordingIdsForPlayback(
        collection: R16SystemCollection
    ): List<RecordingId> = systemCollectionRecordingIdsForPlayback(collection, null)

    override suspend fun systemCollectionRecordingIdsForPlayback(
        collection: R16SystemCollection,
        query: String?,
    ): List<RecordingId> =
        dao.filteredSystemCollectionRecordingIdsForPlayback(
                collection.id,
                query?.trim()?.takeIf(String::isNotEmpty),
            )
            .map(::RecordingId)

    override suspend fun playlistEntries(
        playlistId: PlaylistId,
        page: R16MediaBrowserPageRequest,
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistEntry> {
        val request = page.normalized()
        val rows = dao.playlistEntriesPage(playlistId.value, request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasPlaylistEntryAt(playlistId.value, nextOffset) },
            map = PlaylistEntryRowView::toBrowserEntry,
        )
    }

    override suspend fun playlistEntries(
        playlistId: PlaylistId,
        page: R16MediaBrowserPageRequest,
        sort: PlaylistSort,
    ): R16MediaBrowserPage<R16MediaBrowserPlaylistEntry> {
        val normalizedSort = sort.normalized()
        if (normalizedSort.isCustom) return playlistEntries(playlistId, page)
        val request = page.normalized()
        val mode = normalizedSort.mode.wireValue
        val direction = normalizedSort.direction.wireValue
        val rows =
            dao.sortedPlaylistEntriesPage(
                playlistId = playlistId.value,
                sortMode = mode,
                sortDirection = direction,
                limit = request.pageSize,
                offset = request.offset,
            )
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset ->
                dao.hasSortedPlaylistEntryAt(playlistId.value, null, mode, direction, nextOffset)
            },
            map = PlaylistEntryRowView::toBrowserEntry,
        )
    }

    override suspend fun playlistEntriesForPlayback(
        playlistId: PlaylistId
    ): List<R16MediaBrowserPlaylistPlaybackSeed> =
        dao.playlistEntriesForPlayback(playlistId.value)
            .map(PlaylistEntryPlaybackSeedRow::toPlaybackSeed)

    override suspend fun playlistEntriesForPlayback(
        playlistId: PlaylistId,
        context: R16MediaBrowserPlaylistPlaybackContext,
    ): List<R16MediaBrowserPlaylistPlaybackSeed> {
        val normalizedSort = context.sort.normalized()
        val query = context.search?.trim()?.takeIf(String::isNotEmpty)
        return dao.playlistEntriesForPlaybackSorted(
                playlistId = playlistId.value,
                query = query,
                sortMode = normalizedSort.mode.wireValue,
                sortDirection = normalizedSort.direction.wireValue,
            )
            .map(PlaylistEntryPlaybackSeedRow::toPlaybackSeed)
    }

    override suspend fun recording(recordingId: RecordingId): R16MediaBrowserRecording? =
        dao.librarySong(recordingId.value)?.toBrowserRecording()

    override suspend fun playlistEntry(
        playlistEntryId: PlaylistEntryId
    ): R16MediaBrowserPlaylistEntry? = dao.playlistEntry(playlistEntryId.value)?.toBrowserEntry()

    override suspend fun item(mediaId: String): R16MediaBrowserItem? =
        when (val id = R16MediaBrowserIdCodec.decode(mediaId)) {
            R16MediaBrowserId.Root -> R16MediaBrowserItem.Root
            R16MediaBrowserId.LibrarySongs -> R16MediaBrowserItem.LibrarySongs
            R16MediaBrowserId.LibraryArtists -> R16MediaBrowserItem.LibraryArtists
            is R16MediaBrowserId.SystemCollection ->
                R16MediaBrowserItem.SystemCollection(id.collection)
            is R16MediaBrowserId.SystemCollectionRecording ->
                dao.librarySong(id.recordingId.value)
                    ?.toBrowserRecording()
                    ?.takeIf { it.belongsTo(id.collection) }
                    ?.let { recording ->
                        R16MediaBrowserItem.SystemCollectionRecording(id.collection, recording)
                    }
            is R16MediaBrowserId.Playlist ->
                dao.playlistSummary(id.id.value)
                    ?.toBrowserSummary()
                    ?.let(R16MediaBrowserItem::Playlist)
            is R16MediaBrowserId.PlaylistEntry ->
                dao.playlistEntry(id.id.value)
                    ?.toBrowserEntry()
                    ?.let(R16MediaBrowserItem::PlaylistEntry)
            is R16MediaBrowserId.Recording ->
                dao.librarySong(id.id.value)
                    ?.toBrowserRecording()
                    ?.let(R16MediaBrowserItem::Recording)
            is R16MediaBrowserId.Artist ->
                dao.artistLibrarySummary(id.id.value)
                    ?.toBrowserArtistSummary()
                    ?.let(R16MediaBrowserItem::Artist)
            is R16MediaBrowserId.ArtistRecording ->
                dao.artistSong(id.artistId.value, id.recordingId.value)
                    ?.toBrowserRecording()
                    ?.let { recording ->
                        R16MediaBrowserItem.ArtistRecording(id.artistId, recording)
                    }
            is R16MediaBrowserId.Album ->
                dao.releaseTitle(id.id.value)?.let { title ->
                    R16MediaBrowserItem.Album(id.id, title)
                } ?: R16MediaBrowserItem.Album(id.id, id.id.value)
            is R16MediaBrowserId.AlbumRecording ->
                dao.albumSong(id.releaseId.value, id.recordingId.value)
                    ?.toBrowserRecording()
                    ?.let { recording ->
                        R16MediaBrowserItem.AlbumRecording(id.releaseId, recording)
                    }
            is R16MediaBrowserId.Genre -> R16MediaBrowserItem.Genre(id.name)
            is R16MediaBrowserId.GenreRecording ->
                dao.genreSong(id.genre, id.recordingId.value)?.toBrowserRecording()?.let { recording
                    ->
                    R16MediaBrowserItem.GenreRecording(id.genre, recording)
                }
            null -> null
        }

    override suspend fun searchLibrary(
        query: String,
        page: R16MediaBrowserPageRequest,
    ): R16MediaBrowserPage<R16MediaBrowserRecording> {
        val request = page.normalized()
        val match = R16LibrarySongQuery(query).ftsMatchOrNull()
        if (match == null) {
            return if (query.isBlank()) librarySongs(page) else emptyPage(request)
        }

        val rows = dao.searchLibraryPage(match, request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasLibrarySearchAt(match, nextOffset) },
            map = LibrarySongRowView::toBrowserRecording,
        )
    }

    override suspend fun searchCanonical(
        query: String,
        page: R16MediaBrowserPageRequest,
    ): R16MediaBrowserPage<R16MediaBrowserRecording> {
        val request = page.normalized()
        val match = R16LibrarySongQuery(query).ftsMatchOrNull()
        if (match == null) return emptyPage(request)

        val rows = dao.searchCanonicalPage(match, request.pageSize, request.offset)
        return rows.toPage(
            request = request,
            hasNextAt = { nextOffset -> dao.hasCanonicalSearchAt(match, nextOffset) },
            map = LibrarySongRowView::toBrowserRecording,
        )
    }

    private fun R16MediaBrowserPageRequest.normalized(): R16MediaBrowserPageRequest =
        if (pageSize == boundedPageSize) this else copy(pageSize = boundedPageSize)

    private suspend fun <RoomRow, Item> List<RoomRow>.toPage(
        request: R16MediaBrowserPageRequest,
        hasNextAt: suspend (Int) -> Boolean,
        map: (RoomRow) -> Item,
    ): R16MediaBrowserPage<Item> {
        val items = map { map(it) }
        val nextOffset = request.offset.saturatedAdd(request.pageSize)
        val hasNext = items.size == request.pageSize && hasNextAt(nextOffset)
        return R16MediaBrowserPage(items, request.offset, request.pageSize, hasNext)
    }

    private fun emptyPage(request: R16MediaBrowserPageRequest) =
        R16MediaBrowserPage<R16MediaBrowserRecording>(
            items = emptyList(),
            offset = request.offset,
            pageSize = request.pageSize,
            hasNext = false,
        )

    private fun Int.saturatedAdd(value: Int): Int =
        if (this > Int.MAX_VALUE - value) Int.MAX_VALUE else this + value
}

private fun R16MediaBrowserRecording.belongsTo(collection: R16SystemCollection): Boolean =
    when (collection) {
        R16SystemCollection.LIKED -> liked
        R16SystemCollection.DOWNLOADS -> downloadAssetExists
        R16SystemCollection.LOCAL -> localAssetExists
    }

private fun LibrarySongRowView.toBrowserRecording() =
    R16MediaBrowserRecording(
        recordingId = RecordingId(recordingId),
        title = title,
        artistDisplay = artistDisplay,
        releaseTitle = releaseTitle,
        artworkLocation = artworkLocation,
        durationMs = durationMs,
        liked = liked,
        localAssetExists = localAssetExists,
        downloadAssetExists = downloadAssetExists,
    )

private fun PlaylistEntryRowView.toBrowserEntry() =
    R16MediaBrowserPlaylistEntry(
        playlistEntryId = PlaylistEntryId(playlistEntryId),
        playlistId = PlaylistId(playlistId),
        recordingId = RecordingId(recordingId),
        orderKey = orderKey,
        title = title,
        artistDisplay = artistDisplay,
        releaseTitle = releaseTitle,
        artworkLocation = artworkLocation,
        durationMs = durationMs,
        liked = liked,
        offlineAvailable = offlineAvailable,
        downloadState = downloadState,
        availabilitySummary = availabilitySummary,
    )

private fun PlaylistEntryPlaybackSeedRow.toPlaybackSeed() =
    R16MediaBrowserPlaylistPlaybackSeed(
        playlistEntryId = PlaylistEntryId(playlistEntryId),
        playlistId = PlaylistId(playlistId),
        recordingId = RecordingId(recordingId),
        orderKey = orderKey,
    )

private fun PlaylistSummaryRowView.toBrowserSummary() =
    R16MediaBrowserPlaylistSummary(
        playlistId = PlaylistId(playlistId),
        name = name,
        pinned = pinned,
        libraryOrderKey = libraryOrderKey,
        artworkOverride = artworkOverride,
        displaySortMode = displaySortMode,
        displaySortDirection = displaySortDirection,
        entryCount = entryCount,
        totalDurationMs = totalDurationMs,
    )

private fun ArtistLibrarySummaryRow.toBrowserArtistSummary() =
    R16MediaBrowserArtistSummary(
        artistId = ArtistId(artistId),
        canonicalName = canonicalName,
        recordingCount = recordingCount,
    )
