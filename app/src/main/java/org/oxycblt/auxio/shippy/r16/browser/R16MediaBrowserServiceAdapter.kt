/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserServiceAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.browser

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.PlaylistSortDirection
import app.shippy.core.library.PlaylistSortMode
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.browser.R16MediaBrowserArtistSummary
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.browser.R16MediaBrowserItem
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.data.browser.R16MediaBrowserPlaylistEntry
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackContext
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import app.shippy.data.browser.R16MediaBrowserRecording
import app.shippy.data.browser.R16MediaBrowserRepository

/**
 * Inactive R16 projection for the existing [MediaBrowserCompat] service boundary.
 *
 * This class contains no Android service lifecycle and no caller trust policy. The eventual
 * [androidx.media.MediaBrowserServiceCompat] owner decides whether a caller may use this seam and
 * handles `Result` detach/send operations. All reads stay on the canonical R16 browser repository;
 * all playback callbacks go through the supplied R16 router.
 */
class R16MediaBrowserServiceAdapter(
    private val browser: R16MediaBrowserRepository,
    private val playbackRouter: R16BrowserPlaybackRouter,
) {
    /** Loads one browse page, or null when [parentId] is malformed or stale. */
    suspend fun loadChildren(
        parentId: String,
        options: Bundle? = null,
        rootHints: Bundle? = null,
    ): List<MediaItem>? {
        if (R16MediaBrowserIdCodec.decode(parentId) == null) return null
        val request = options.toPageRequest()
        val parent = browser.item(parentId) ?: return null
        if (parent.mediaId != parentId) return null
        return when (parent) {
            R16MediaBrowserItem.Root -> loadRoot(request, rootHints)
            R16MediaBrowserItem.LibrarySongs ->
                browser.librarySongs(request).items.map { it.toMediaItem() }
            R16MediaBrowserItem.LibraryArtists ->
                browser.libraryArtists(request).items.map { it.toMediaItem() }
            is R16MediaBrowserItem.Artist ->
                browser.artistRecordings(parent.summary.artistId, request).items.map { recording ->
                    recording.toMediaItem(parent.summary.artistId)
                }
            is R16MediaBrowserItem.SystemCollection ->
                browser.systemCollection(parent.collection, request).items.map { recording ->
                    recording.toMediaItem(parent.collection)
                }
            is R16MediaBrowserItem.Playlist ->
                browser
                    .playlistEntries(parent.summary.playlistId, request, parent.summary.displaySort)
                    .items
                    .map { it.toMediaItem() }
            is R16MediaBrowserItem.Album ->
                browser.librarySongs(request).items.map { it.toMediaItem() }
            is R16MediaBrowserItem.Genre ->
                browser.librarySongs(request).items.map { it.toMediaItem() }
            is R16MediaBrowserItem.SystemCollectionRecording,
            is R16MediaBrowserItem.PlaylistEntry,
            is R16MediaBrowserItem.Recording,
            is R16MediaBrowserItem.ArtistRecording,
            is R16MediaBrowserItem.AlbumRecording,
            is R16MediaBrowserItem.GenreRecording -> emptyList()
        }
    }

    /** Loads one exact canonical item, or null when the ID is malformed or no longer exists. */
    suspend fun loadItem(mediaId: String): MediaItem? {
        if (R16MediaBrowserIdCodec.decode(mediaId) == null) return null
        val item = browser.item(mediaId) ?: return null
        return item.takeIf { it.mediaId == mediaId }?.toMediaItem()
    }

    /** Searches only the canonical local Library read model; provider/global search is excluded. */
    suspend fun search(query: String, options: Bundle? = null): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return browser.searchLibrary(query, options.toPageRequest()).items.map { it.toMediaItem() }
    }

    /** Routes a MediaBrowser play-from-ID callback to the canonical R16 playback router. */
    fun playFromMediaId(mediaId: String?, extras: Bundle? = null) {
        val songsQuery = extras?.getString(R16MediaBrowserContract.EXTRA_SONGS_QUERY)
        when (val decoded = extras.playlistPlaybackContext()) {
            PlaylistPlaybackContextDecode.Invalid ->
                playbackRouter.playFromMediaId(null, extras.shuffleSeed())
            is PlaylistPlaybackContextDecode.Valid ->
                playbackRouter.playFromMediaId(
                    mediaId = mediaId,
                    shuffleSeed = extras.shuffleSeed(),
                    playlistContext = decoded.context,
                    songsQuery = songsQuery,
                )
        }
    }

    /** Routes a MediaBrowser play-from-search callback to the canonical R16 playback router. */
    fun playFromSearch(query: String?) = playbackRouter.playFromSearch(query)

    /** Releases the router owned by this adapter. */
    fun release() = playbackRouter.release()

    private suspend fun loadRoot(
        request: R16MediaBrowserPageRequest,
        rootHints: Bundle?,
    ): List<MediaItem> {
        val rootLimit = rootHints.rootChildrenLimit()
        if (rootLimit == 0 || request.offset >= rootLimit) return emptyList()

        // Library and Artists are stable first children; playlist rows occupy the remaining
        // virtual positions.
        val staticChildren = 2
        val firstPlaylistOffset = (request.offset - staticChildren).coerceAtLeast(0)
        val available = (rootLimit - request.offset).coerceAtMost(request.pageSize)
        if (available <= 0) return emptyList()

        val items = ArrayList<MediaItem>(available)
        if (request.offset == 0) {
            items += libraryItem()
        }
        if (request.offset <= 1 && items.size < available) items += artistsItem()

        val staticItemsInPage = (staticChildren - request.offset).coerceIn(0, available)
        val playlistPageSize = (available - staticItemsInPage).coerceAtLeast(0)
        if (playlistPageSize == 0) return items
        val summaries =
            browser
                .playlistSummaries(
                    R16MediaBrowserPageRequest(
                        offset = firstPlaylistOffset,
                        pageSize = playlistPageSize,
                    )
                )
                .items
        items += summaries.map { it.toMediaItem() }
        return items
    }

    private fun libraryItem(): MediaItem =
        MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(R16MediaBrowserIdCodec.LIBRARY_SONGS)
                .setTitle(LIBRARY_TITLE)
                .build(),
            MediaItem.FLAG_BROWSABLE,
        )

    private fun artistsItem(): MediaItem =
        MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(R16MediaBrowserIdCodec.LIBRARY_ARTISTS)
                .setTitle(ARTISTS_TITLE)
                .build(),
            MediaItem.FLAG_BROWSABLE,
        )

    private fun Bundle?.toPageRequest(): R16MediaBrowserPageRequest {
        val requestedPageSize =
            this?.getInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, DEFAULT_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val pageSize =
            requestedPageSize
                .takeIf { it > 0 }
                ?.coerceAtMost(R16MediaBrowserRepository.MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val page = this?.getInt(MediaBrowserCompat.EXTRA_PAGE, 0)?.takeIf { it >= 0 } ?: 0
        val offset = if (page > Int.MAX_VALUE / pageSize) Int.MAX_VALUE else page * pageSize
        return R16MediaBrowserPageRequest(offset = offset, pageSize = pageSize)
    }

    private fun Bundle?.shuffleSeed(): Long? {
        if (this == null || !containsKey(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED)) return null
        return getLong(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED)
    }

    /** One boundary decoder validates the typed context before it reaches queue resolution. */
    private fun Bundle?.playlistPlaybackContext(): PlaylistPlaybackContextDecode {
        if (this == null) return PlaylistPlaybackContextDecode.Valid(null)
        val hasFilter = containsKey(R16MediaBrowserContract.EXTRA_PLAYLIST_FILTER)
        val hasMode = containsKey(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE)
        val hasDirection = containsKey(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION)
        if (!hasFilter && !hasMode && !hasDirection) {
            return PlaylistPlaybackContextDecode.Valid(null)
        }
        if (hasMode != hasDirection) return PlaylistPlaybackContextDecode.Invalid
        val values =
            runCatching {
                    Triple(
                        getString(R16MediaBrowserContract.EXTRA_PLAYLIST_FILTER),
                        getString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE),
                        getString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION),
                    )
                }
                .getOrNull() ?: return PlaylistPlaybackContextDecode.Invalid
        val filter = values.first
        val rawMode = values.second
        val rawDirection = values.third
        if ((hasMode && rawMode == null) || (hasDirection && rawDirection == null)) {
            return PlaylistPlaybackContextDecode.Invalid
        }
        val mode = PlaylistSortMode.fromWireOrNull(rawMode)
        val direction = PlaylistSortDirection.fromWireOrNull(rawDirection)
        if ((hasMode && mode == null) || (hasDirection && direction == null)) {
            return PlaylistPlaybackContextDecode.Invalid
        }
        return PlaylistPlaybackContextDecode.Valid(
            R16MediaBrowserPlaylistPlaybackContext(
                search = filter?.trim()?.takeIf(String::isNotEmpty),
                sort =
                    if (mode == null && direction == null) PlaylistSort()
                    else
                        PlaylistSort(
                                requireNotNull(mode),
                                direction ?: PlaylistSortDirection.ASCENDING,
                            )
                            .normalized(),
            )
        )
    }

    private sealed interface PlaylistPlaybackContextDecode {
        data class Valid(val context: R16MediaBrowserPlaylistPlaybackContext?) :
            PlaylistPlaybackContextDecode

        data object Invalid : PlaylistPlaybackContextDecode
    }

    private fun Bundle?.rootChildrenLimit(): Int {
        val requested =
            this?.getInt(
                MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT,
                DEFAULT_ROOT_CHILDREN_LIMIT,
            ) ?: DEFAULT_ROOT_CHILDREN_LIMIT
        return requested.coerceIn(0, R16MediaBrowserRepository.MAX_PAGE_SIZE)
    }

    private fun R16MediaBrowserItem.toMediaItem(): MediaItem =
        when (this) {
            R16MediaBrowserItem.Root ->
                MediaItem(
                    description(R16MediaBrowserIdCodec.ROOT, ROOT_TITLE).build(),
                    MediaItem.FLAG_BROWSABLE,
                )
            R16MediaBrowserItem.LibrarySongs -> libraryItem()
            R16MediaBrowserItem.LibraryArtists -> artistsItem()
            is R16MediaBrowserItem.Artist -> summary.toMediaItem()
            is R16MediaBrowserItem.ArtistRecording -> recording.toMediaItem(artistId)
            is R16MediaBrowserItem.SystemCollection -> collection.toMediaItem()
            is R16MediaBrowserItem.SystemCollectionRecording -> recording.toMediaItem(collection)
            is R16MediaBrowserItem.Playlist -> summary.toMediaItem()
            is R16MediaBrowserItem.PlaylistEntry -> entry.toMediaItem()
            is R16MediaBrowserItem.Recording -> recording.toMediaItem()
            is R16MediaBrowserItem.Album ->
                MediaItem(
                    description(
                            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Album(releaseId)),
                            title,
                        )
                        .build(),
                    MediaItem.FLAG_BROWSABLE,
                )
            is R16MediaBrowserItem.AlbumRecording -> recording.toMediaItem()
            is R16MediaBrowserItem.Genre ->
                MediaItem(
                    description(R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Genre(name)), name)
                        .build(),
                    MediaItem.FLAG_BROWSABLE,
                )
            is R16MediaBrowserItem.GenreRecording -> recording.toMediaItem()
        }

    private fun R16MediaBrowserPlaylistSummary.toMediaItem(): MediaItem =
        MediaItem(
            description(R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId)), name)
                .build(),
            MediaItem.FLAG_BROWSABLE,
        )

    private fun R16MediaBrowserArtistSummary.toMediaItem(): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Artist(artistId)),
                    canonicalName,
                )
                .setDescription("$recordingCount recordings")
                .build(),
            MediaItem.FLAG_BROWSABLE,
        )

    private fun R16MediaBrowserPlaylistEntry.toMediaItem(): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.PlaylistEntry(playlistEntryId)),
                    title,
                )
                .setSubtitle(artistDisplay)
                .setDescription(releaseTitle)
                .build(),
            MediaItem.FLAG_PLAYABLE,
        )

    private fun R16MediaBrowserRecording.toMediaItem(): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recordingId)),
                    title,
                )
                .setSubtitle(artistDisplay)
                .setDescription(releaseTitle)
                .build(),
            MediaItem.FLAG_PLAYABLE,
        )

    private fun R16MediaBrowserRecording.toMediaItem(
        artistId: app.shippy.core.identity.ArtistId
    ): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.ArtistRecording(artistId, recordingId)
                    ),
                    title,
                )
                .setSubtitle(artistDisplay)
                .setDescription(releaseTitle)
                .build(),
            MediaItem.FLAG_PLAYABLE,
        )

    private fun R16MediaBrowserRecording.toMediaItem(collection: R16SystemCollection): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.SystemCollectionRecording(collection, recordingId)
                    ),
                    title,
                )
                .setSubtitle(artistDisplay)
                .setDescription(releaseTitle)
                .build(),
            MediaItem.FLAG_PLAYABLE,
        )

    private fun R16SystemCollection.toMediaItem(): MediaItem =
        MediaItem(
            description(
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.SystemCollection(this)),
                    displayName,
                )
                .build(),
            MediaItem.FLAG_BROWSABLE,
        )

    private fun description(mediaId: String, title: String): MediaDescriptionCompat.Builder =
        MediaDescriptionCompat.Builder().setMediaId(mediaId).setTitle(title)

    private companion object {
        const val DEFAULT_PAGE_SIZE = R16MediaBrowserRepository.DEFAULT_PAGE_SIZE
        const val DEFAULT_ROOT_CHILDREN_LIMIT = 4
        const val ROOT_TITLE = "Shippy"
        const val LIBRARY_TITLE = "Library"
        const val ARTISTS_TITLE = "Artists in your Library"
    }
}

private val R16SystemCollection.displayName: String
    get() =
        when (this) {
            R16SystemCollection.LIKED -> "Liked"
            R16SystemCollection.DOWNLOADS -> "Downloads"
            R16SystemCollection.LOCAL -> "Local"
        }
