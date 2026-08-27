/*
 * Copyright (c) 2026 Auxio Project
 * R16BrowserQueueResolver.kt is part of Auxio.
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

import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.R16SystemCollection
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.queue.PlaybackOrigin
import app.shippy.core.queue.PlaybackOriginKind
import app.shippy.core.queue.QueueEntry
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.browser.R16MediaBrowserItem
import app.shippy.data.browser.R16MediaBrowserPage
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackContext
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackSeed
import app.shippy.data.browser.R16MediaBrowserRecording
import app.shippy.data.browser.R16MediaBrowserRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The small, inactive bridge from the canonical R16 browser read model to playback commands.
 *
 * It deliberately returns a command instead of dispatching one. Authority selection and service
 * wiring remain outside this seam until the R16 cutover. No provider is consulted: this resolver
 * only turns already-present canonical browser rows into source-neutral queue occurrences.
 */
class R16BrowserQueueResolver(
    private val browser: R16MediaBrowserRepository,
    private val now: () -> Instant = Instant::now,
    private val maxQueueEntries: Int = 50,
    private val queueContextNonce: () -> UUID = { UUID.randomUUID() },
) {
    init {
        require(maxQueueEntries in 1..MAX_QUEUE_ENTRIES) {
            "maxQueueEntries must be between 1 and $MAX_QUEUE_ENTRIES"
        }
    }

    /**
     * Resolves one exact browser item, or the playback context for a container item.
     *
     * Browser children remain paged for display, but a playlist play replaces the queue with the
     * complete ordered playlist. Queue entries are intentionally lightweight; source, artwork, and
     * presentation work remains deferred to playback/window consumers.
     */
    suspend fun resolvePlay(
        mediaId: String,
        playlistContext: R16MediaBrowserPlaylistPlaybackContext? = null,
        songsQuery: String? = null,
    ): R16BrowserQueueResolution {
        val item = exactItem(mediaId) ?: return rejectedFor(mediaId)
        return when (item) {
            R16MediaBrowserItem.Root ->
                rejected(mediaId, R16BrowserQueueRejection.UNSUPPORTED_MEDIA_ID)
            R16MediaBrowserItem.LibrarySongs -> resolveSongsContext(mediaId, query = songsQuery)
            R16MediaBrowserItem.LibraryArtists ->
                rejected(mediaId, R16BrowserQueueRejection.UNSUPPORTED_MEDIA_ID)
            is R16MediaBrowserItem.SystemCollection ->
                resolveSystemCollectionContext(mediaId, item.collection)
            is R16MediaBrowserItem.SystemCollectionRecording ->
                resolveSystemCollectionContext(
                    sourceMediaId = mediaId,
                    collection = item.collection,
                    selectedMediaId = mediaId,
                )
            is R16MediaBrowserItem.Playlist ->
                resolvePlaylistContext(
                    sourceMediaId = mediaId,
                    playlistId = item.summary.playlistId,
                    playlistContext =
                        playlistContext
                            ?: R16MediaBrowserPlaylistPlaybackContext(
                                sort = item.summary.displaySort
                            ),
                )
            is R16MediaBrowserItem.Recording ->
                if (songsQuery != null) {
                    resolveSongsContext(
                        sourceMediaId = R16MediaBrowserIdCodec.LIBRARY_SONGS,
                        selectedMediaId = mediaId,
                        query = songsQuery.takeIf(String::isNotBlank),
                    )
                } else {
                    ready(
                        mediaId,
                        listOf(item.recording.toQueueEntry(PlaybackOriginKind.LOCAL_LIBRARY)),
                    )
                }
            is R16MediaBrowserItem.Artist -> resolveArtistContext(mediaId, item.summary.artistId)
            is R16MediaBrowserItem.ArtistRecording ->
                resolveArtistContext(
                    sourceMediaId = mediaId,
                    artistId = item.artistId,
                    selectedMediaId = mediaId,
                )
            is R16MediaBrowserItem.PlaylistEntry ->
                resolvePlaylistContext(
                    sourceMediaId = mediaId,
                    playlistId = item.entry.playlistId,
                    selectedMediaId = mediaId,
                    playlistContext = playlistContext,
                )
            is R16MediaBrowserItem.Album ->
                resolveAlbumContext(sourceMediaId = mediaId, releaseId = item.releaseId)
            is R16MediaBrowserItem.AlbumRecording ->
                resolveAlbumContext(
                    sourceMediaId = mediaId,
                    releaseId = item.releaseId,
                    selectedMediaId = mediaId,
                )
            is R16MediaBrowserItem.Genre ->
                resolveGenreContext(sourceMediaId = mediaId, genre = item.name)
            is R16MediaBrowserItem.GenreRecording ->
                resolveGenreContext(
                    sourceMediaId = mediaId,
                    genre = item.genre,
                    selectedMediaId = mediaId,
                )
        }
    }

    /**
     * Builds a queue from a browser container page. [selectedMediaId] must be one of the exact rows
     * in the returned page, which prevents stale or cross-context selection from silently changing
     * the intended recording.
     */
    suspend fun resolveContainer(
        containerMediaId: String,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
        selectedMediaId: String? = null,
        playlistContext: R16MediaBrowserPlaylistPlaybackContext? = null,
    ): R16BrowserQueueResolution {
        val item = exactItem(containerMediaId) ?: return rejectedFor(containerMediaId)
        val boundedPage = page.copy(pageSize = page.pageSize.coerceAtMost(maxQueueEntries))
        return when (item) {
            R16MediaBrowserItem.LibrarySongs ->
                resolveLibraryPage(containerMediaId, boundedPage, selectedMediaId)
            is R16MediaBrowserItem.Artist ->
                resolveArtistContext(
                    sourceMediaId = containerMediaId,
                    artistId = item.summary.artistId,
                    selectedMediaId = selectedMediaId,
                )
            is R16MediaBrowserItem.Album ->
                resolveAlbumContext(
                    sourceMediaId = containerMediaId,
                    releaseId = item.releaseId,
                    selectedMediaId = selectedMediaId,
                )
            is R16MediaBrowserItem.Genre ->
                resolveGenreContext(
                    sourceMediaId = containerMediaId,
                    genre = item.name,
                    selectedMediaId = selectedMediaId,
                )
            is R16MediaBrowserItem.SystemCollection ->
                resolveSystemCollectionPage(
                    containerMediaId,
                    item.collection,
                    boundedPage,
                    selectedMediaId,
                )
            is R16MediaBrowserItem.Playlist ->
                resolvePlaylistContext(
                    sourceMediaId = containerMediaId,
                    playlistId = item.summary.playlistId,
                    selectedMediaId = selectedMediaId,
                    playlistContext = playlistContext,
                )
            else -> rejected(containerMediaId, R16BrowserQueueRejection.UNSUPPORTED_MEDIA_ID)
        }
    }

    /**
     * Resolves local Library search results into a bounded playback context. This is intentionally
     * separate from global/provider search: provider network calls never occur here.
     */
    suspend fun resolveLibrarySearch(
        query: String,
        page: R16MediaBrowserPageRequest = R16MediaBrowserPageRequest(),
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution {
        val boundedPage = page.copy(pageSize = page.pageSize.coerceAtMost(maxQueueEntries))
        val result = browser.searchLibrary(query, boundedPage)
        return resolveRecordingPage(
            sourceMediaId = R16MediaBrowserIdCodec.LIBRARY_SONGS,
            page = result,
            originKind = PlaybackOriginKind.SEARCH,
            selectedMediaId = selectedMediaId,
        )
    }

    private suspend fun resolveLibraryPage(
        sourceMediaId: String,
        page: R16MediaBrowserPageRequest,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution =
        resolveRecordingPage(
            sourceMediaId = sourceMediaId,
            page = browser.librarySongs(page),
            originKind = PlaybackOriginKind.LOCAL_LIBRARY,
            selectedMediaId = selectedMediaId,
        )

    /**
     * Songs row tap and container actions use the complete lightweight ordered identity context.
     * Rich Library rows remain paged and are never materialized for playback setup.
     */
    suspend fun resolveSongsContext(
        sourceMediaId: String = R16MediaBrowserIdCodec.LIBRARY_SONGS,
        selectedMediaId: String? = null,
        query: String? = null,
    ): R16BrowserQueueResolution =
        withContext(Dispatchers.Default) {
            val recordingIds = browser.librarySongRecordingIdsForPlayback(query)
            if (recordingIds.isEmpty()) {
                return@withContext rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
            }
            val originKind =
                if (query.isNullOrBlank()) PlaybackOriginKind.LOCAL_LIBRARY
                else PlaybackOriginKind.SEARCH
            val entries = recordingIds.map { recordingId -> recordingId.toQueueEntry(originKind) }
            val mediaIds =
                recordingIds.map { recordingId ->
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recordingId))
                }
            when (val selectedIndex = selectedIndex(selectedMediaId, mediaIds)) {
                is SelectionResult.Rejected -> rejected(sourceMediaId, selectedIndex.reason)
                is SelectionResult.Index -> ready(sourceMediaId, entries, selectedIndex.value)
            }
        }

    private suspend fun resolveArtistContext(
        sourceMediaId: String,
        artistId: ArtistId,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution =
        withContext(Dispatchers.Default) {
            val recordingIds = browser.artistRecordingIdsForPlayback(artistId)
            if (recordingIds.isEmpty()) {
                return@withContext rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
            }
            val entries = recordingIds.map { recordingId -> recordingId.toQueueEntry(artistId) }
            val mediaIds =
                recordingIds.map { recordingId ->
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.ArtistRecording(artistId, recordingId)
                    )
                }
            when (val selectedIndex = selectedIndex(selectedMediaId, mediaIds)) {
                is SelectionResult.Rejected -> rejected(sourceMediaId, selectedIndex.reason)
                is SelectionResult.Index -> ready(sourceMediaId, entries, selectedIndex.value)
            }
        }

    private suspend fun resolveAlbumContext(
        sourceMediaId: String,
        releaseId: app.shippy.core.identity.ReleaseId,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution =
        withContext(Dispatchers.Default) {
            val recordingIds = browser.albumRecordingIdsForPlayback(releaseId)
            if (recordingIds.isEmpty()) {
                return@withContext rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
            }
            val entries = recordingIds.map { recordingId -> recordingId.toQueueEntry(releaseId) }
            val mediaIds =
                recordingIds.map { recordingId ->
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.AlbumRecording(releaseId, recordingId)
                    )
                }
            when (val selectedIndex = selectedIndex(selectedMediaId, mediaIds)) {
                is SelectionResult.Rejected -> rejected(sourceMediaId, selectedIndex.reason)
                is SelectionResult.Index -> ready(sourceMediaId, entries, selectedIndex.value)
            }
        }

    private suspend fun resolveGenreContext(
        sourceMediaId: String,
        genre: String,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution =
        withContext(Dispatchers.Default) {
            val recordingIds = browser.genreRecordingIdsForPlayback(genre)
            if (recordingIds.isEmpty()) {
                return@withContext rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
            }
            val entries = recordingIds.map { recordingId -> recordingId.toGenreQueueEntry(genre) }
            val mediaIds =
                recordingIds.map { recordingId ->
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.GenreRecording(genre, recordingId)
                    )
                }
            when (val selectedIndex = selectedIndex(selectedMediaId, mediaIds)) {
                is SelectionResult.Rejected -> rejected(sourceMediaId, selectedIndex.reason)
                is SelectionResult.Index -> ready(sourceMediaId, entries, selectedIndex.value)
            }
        }

    private suspend fun resolveSystemCollectionPage(
        sourceMediaId: String,
        collection: R16SystemCollection,
        page: R16MediaBrowserPageRequest,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution {
        val result = browser.systemCollection(collection, page)
        if (result.items.isEmpty())
            return rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
        val entries = result.items.map { it.recordingId.toQueueEntry(collection) }
        val selectedIndex =
            selectedIndex(
                selectedMediaId,
                result.items.map {
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.SystemCollectionRecording(collection, it.recordingId)
                    )
                },
            )
        if (selectedIndex is SelectionResult.Rejected) {
            return rejected(sourceMediaId, selectedIndex.reason)
        }
        return ready(sourceMediaId, entries, (selectedIndex as SelectionResult.Index).value)
    }

    /**
     * Container actions and a scoped row tap use the full lightweight ordered identity context.
     * Rich Library rows remain paged and are never materialized for playback setup.
     */
    private suspend fun resolveSystemCollectionContext(
        sourceMediaId: String,
        collection: R16SystemCollection,
        selectedMediaId: String? = null,
    ): R16BrowserQueueResolution =
        withContext(Dispatchers.Default) {
            val recordingIds = browser.systemCollectionRecordingIdsForPlayback(collection)
            if (recordingIds.isEmpty()) {
                return@withContext rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
            }
            val entries = recordingIds.map { it.toQueueEntry(collection) }
            val selectedIndex =
                selectedIndex(
                    selectedMediaId,
                    recordingIds.map {
                        R16MediaBrowserIdCodec.encode(
                            R16MediaBrowserId.SystemCollectionRecording(collection, it)
                        )
                    },
                )
            if (selectedIndex is SelectionResult.Rejected) {
                return@withContext rejected(sourceMediaId, selectedIndex.reason)
            }
            ready(sourceMediaId, entries, (selectedIndex as SelectionResult.Index).value)
        }

    /**
     * Loads every lightweight occurrence for a playlist using the repository's dedicated ordered
     * playback query. The browser's 50-row page size is a display bound, not a playback-context
     * bound; truncating here would make Play/Shuffle and an exact occurrence selection silently
     * lose the rest of the playlist.
     */
    private suspend fun resolvePlaylistContext(
        sourceMediaId: String,
        playlistId: PlaylistId,
        selectedMediaId: String? = null,
        playlistContext: R16MediaBrowserPlaylistPlaybackContext? = null,
    ): R16BrowserQueueResolution {
        val effectiveContext =
            playlistContext
                ?: (browser.item(
                        R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId))
                    ) as? R16MediaBrowserItem.Playlist)
                    ?.summary
                    ?.displaySort
                    ?.let { sort -> R16MediaBrowserPlaylistPlaybackContext(sort = sort) }
        val entries =
            if (effectiveContext == null) {
                browser.playlistEntriesForPlayback(playlistId)
            } else {
                browser.playlistEntriesForPlayback(playlistId, effectiveContext)
            }
        return resolvePlaylistEntries(sourceMediaId, entries, selectedMediaId)
    }

    private suspend fun resolvePlaylistEntries(
        sourceMediaId: String,
        items: List<R16MediaBrowserPlaylistPlaybackSeed>,
        selectedMediaId: String?,
    ): R16BrowserQueueResolution {
        if (items.isEmpty()) return rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
        val contextNonce = queueContextNonce()
        val entries =
            items.mapIndexed { index, item ->
                item.toQueueEntry(PlaybackOriginKind.PLAYLIST, contextNonce, index)
            }
        val selectedIndex =
            selectedIndex(
                selectedMediaId,
                items.map {
                    R16MediaBrowserIdCodec.encode(
                        R16MediaBrowserId.PlaylistEntry(it.playlistEntryId)
                    )
                },
            )
        if (selectedIndex is SelectionResult.Rejected) {
            return rejected(sourceMediaId, selectedIndex.reason)
        }
        return ready(sourceMediaId, entries, (selectedIndex as SelectionResult.Index).value)
    }

    private suspend fun resolveRecordingPage(
        sourceMediaId: String,
        page: R16MediaBrowserPage<R16MediaBrowserRecording>,
        originKind: PlaybackOriginKind,
        selectedMediaId: String?,
    ): R16BrowserQueueResolution {
        if (page.items.isEmpty())
            return rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
        val entries = page.items.map { it.toQueueEntry(originKind) }
        val selectedIndex =
            selectedIndex(
                selectedMediaId,
                page.items.map {
                    R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(it.recordingId))
                },
            )
        if (selectedIndex is SelectionResult.Rejected) {
            return rejected(sourceMediaId, selectedIndex.reason)
        }
        return ready(sourceMediaId, entries, (selectedIndex as SelectionResult.Index).value)
    }

    private suspend fun exactItem(mediaId: String): R16MediaBrowserItem? {
        if (R16MediaBrowserIdCodec.decode(mediaId) == null) return null
        return browser.item(mediaId)
    }

    private fun rejectedFor(mediaId: String): R16BrowserQueueResolution {
        val reason =
            if (R16MediaBrowserIdCodec.decode(mediaId) == null) {
                R16BrowserQueueRejection.MALFORMED_MEDIA_ID
            } else {
                R16BrowserQueueRejection.STALE_MEDIA_ID
            }
        return rejected(mediaId, reason)
    }

    private suspend fun selectedIndex(
        selectedMediaId: String?,
        pageMediaIds: List<String>,
    ): SelectionResult {
        if (selectedMediaId == null) return SelectionResult.Index(0)
        if (R16MediaBrowserIdCodec.decode(selectedMediaId) == null) {
            return SelectionResult.Rejected(R16BrowserQueueRejection.MALFORMED_MEDIA_ID)
        }
        if (browser.item(selectedMediaId) == null) {
            return SelectionResult.Rejected(R16BrowserQueueRejection.STALE_MEDIA_ID)
        }
        val index = pageMediaIds.indexOf(selectedMediaId)
        return if (index >= 0) SelectionResult.Index(index)
        else SelectionResult.Rejected(R16BrowserQueueRejection.SELECTION_NOT_IN_PAGE)
    }

    private fun ready(
        sourceMediaId: String,
        entries: List<QueueEntry>,
        selectedIndex: Int = 0,
    ): R16BrowserQueueResolution {
        if (entries.isEmpty()) return rejected(sourceMediaId, R16BrowserQueueRejection.EMPTY_PAGE)
        require(selectedIndex in entries.indices) {
            "Selected queue occurrence must be in the queue"
        }
        return R16BrowserQueueResolution.Ready(
            sourceMediaId = sourceMediaId,
            entries = entries,
            selectedEntryId = entries[selectedIndex].id,
        )
    }

    private fun rejected(
        sourceMediaId: String,
        reason: R16BrowserQueueRejection,
    ): R16BrowserQueueResolution =
        R16BrowserQueueResolution.Rejected(sourceMediaId = sourceMediaId, reason = reason)

    private fun R16MediaBrowserRecording.toQueueEntry(originKind: PlaybackOriginKind): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = recordingId,
            origin = PlaybackOrigin(originKind, null),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun RecordingId.toQueueEntry(collection: R16SystemCollection): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = this,
            origin = PlaybackOrigin(PlaybackOriginKind.SYSTEM_COLLECTION, collection.id),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun RecordingId.toQueueEntry(originKind: PlaybackOriginKind): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = this,
            origin = PlaybackOrigin(originKind, null),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun RecordingId.toQueueEntry(artistId: ArtistId): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = this,
            origin = PlaybackOrigin(PlaybackOriginKind.ARTIST, artistId.value),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun RecordingId.toQueueEntry(
        releaseId: app.shippy.core.identity.ReleaseId
    ): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = this,
            origin = PlaybackOrigin(PlaybackOriginKind.RELEASE, releaseId.value),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun RecordingId.toGenreQueueEntry(genre: String): QueueEntry =
        QueueEntry(
            id = QueueEntryId(UUID.randomUUID().toString()),
            recordingId = this,
            origin = PlaybackOrigin(PlaybackOriginKind.LOCAL_LIBRARY, genre),
            playlistEntryId = null,
            contributor = null,
            addedAt = now(),
        )

    private fun R16MediaBrowserPlaylistPlaybackSeed.toQueueEntry(
        originKind: PlaybackOriginKind,
        contextNonce: UUID,
        occurrenceIndex: Int,
    ): QueueEntry {
        // QueueEntryId identifies this playback occurrence, not the durable playlist row.
        // Re-resolving a playlist creates one fresh context nonce and therefore fresh IDs.
        val queueEntryId =
            QueueEntryId(
                UUID.nameUUIDFromBytes(
                        "$contextNonce:$occurrenceIndex:${playlistEntryId.value}"
                            .toByteArray(Charsets.UTF_8)
                    )
                    .toString()
            )
        return QueueEntry(
            id = queueEntryId,
            recordingId = recordingId,
            origin = PlaybackOrigin(originKind, playlistId.value),
            playlistEntryId = playlistEntryId,
            contributor = null,
            addedAt = now(),
        )
    }

    private sealed interface SelectionResult {
        data class Index(val value: Int) : SelectionResult

        data class Rejected(val reason: R16BrowserQueueRejection) : SelectionResult
    }

    companion object {
        const val MAX_QUEUE_ENTRIES = 50
    }
}

sealed interface R16BrowserQueueResolution {
    val sourceMediaId: String

    data class Ready(
        override val sourceMediaId: String,
        val entries: List<QueueEntry>,
        val selectedEntryId: QueueEntryId,
    ) : R16BrowserQueueResolution {
        init {
            require(entries.isNotEmpty()) { "A ready browser queue must not be empty" }
            require(entries.map(QueueEntry::id).toSet().size == entries.size) {
                "A browser queue must preserve unique QueueEntryId values"
            }
            require(entries.any { it.id == selectedEntryId }) {
                "Selected QueueEntryId must exist in a browser queue"
            }
        }

        fun playCommand(shuffleSeed: Long? = null): PlaybackCommand.PlayContext =
            PlaybackCommand.PlayContext(entries, selectedEntryId, shuffleSeed)

        val selectedIndex: Int
            get() = entries.indexOfFirst { it.id == selectedEntryId }

        val selectedRecordingId: RecordingId
            get() = entries.first { it.id == selectedEntryId }.recordingId
    }

    data class Rejected(override val sourceMediaId: String, val reason: R16BrowserQueueRejection) :
        R16BrowserQueueResolution
}

enum class R16BrowserQueueRejection {
    MALFORMED_MEDIA_ID,
    STALE_MEDIA_ID,
    UNSUPPORTED_MEDIA_ID,
    EMPTY_PAGE,
    SELECTION_NOT_IN_PAGE,
}
