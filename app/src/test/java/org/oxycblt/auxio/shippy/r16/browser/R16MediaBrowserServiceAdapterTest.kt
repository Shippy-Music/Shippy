/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserServiceAdapterTest.kt is part of Auxio.
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
import androidx.media.utils.MediaConstants
import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.queue.QueueEntry
import app.shippy.data.browser.R16MediaBrowserArtistSummary
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.browser.R16MediaBrowserItem
import app.shippy.data.browser.R16MediaBrowserPage
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.data.browser.R16MediaBrowserPlaylistEntry
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackContext
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackSeed
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import app.shippy.data.browser.R16MediaBrowserRecording
import app.shippy.data.browser.R16MediaBrowserRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MediaBrowserServiceAdapterTest {
    @Test
    fun `strict models project to canonical ids and browse flags`() = runBlocking {
        val recording = recording("recording", "Track")
        val artist =
            R16MediaBrowserArtistSummary(
                artistId = ArtistId(uuid("artist")),
                canonicalName = "Artist",
                recordingCount = 1,
            )
        val playlist = playlistSummary("playlist", "Road trip")
        val entry = playlistEntry(playlist.playlistId, "entry", recording.recordingId)
        val browser = FakeBrowser()
        browser.items +=
            listOf(
                R16MediaBrowserItem.Root,
                R16MediaBrowserItem.LibrarySongs,
                R16MediaBrowserItem.LibraryArtists,
                R16MediaBrowserItem.Artist(artist),
                R16MediaBrowserItem.ArtistRecording(artist.artistId, recording),
                R16MediaBrowserItem.Playlist(playlist),
                R16MediaBrowserItem.Recording(recording),
                R16MediaBrowserItem.PlaylistEntry(entry),
            )
        val router = router(this)
        val adapter = R16MediaBrowserServiceAdapter(browser, router)

        val root = checkNotNull(adapter.loadItem(R16MediaBrowserIdCodec.ROOT))
        val library = checkNotNull(adapter.loadItem(R16MediaBrowserIdCodec.LIBRARY_SONGS))
        val playlistItem =
            checkNotNull(adapter.loadItem(id(R16MediaBrowserId.Playlist(playlist.playlistId))))
        val recordingItem =
            checkNotNull(adapter.loadItem(id(R16MediaBrowserId.Recording(recording.recordingId))))
        val entryItem =
            checkNotNull(
                adapter.loadItem(id(R16MediaBrowserId.PlaylistEntry(entry.playlistEntryId)))
            )

        assertEquals(R16MediaBrowserIdCodec.ROOT, root.mediaId())
        assertTrue(root.isBrowsable())
        assertEquals(R16MediaBrowserIdCodec.LIBRARY_SONGS, library.mediaId())
        assertTrue(library.isBrowsable())
        val artists = checkNotNull(adapter.loadItem(R16MediaBrowserIdCodec.LIBRARY_ARTISTS))
        assertEquals(R16MediaBrowserIdCodec.LIBRARY_ARTISTS, artists.mediaId())
        assertTrue(artists.isBrowsable())
        val artistItem =
            checkNotNull(adapter.loadItem(id(R16MediaBrowserId.Artist(artist.artistId))))
        assertEquals("Artist", artistItem.description.title)
        assertTrue(artistItem.isBrowsable())
        val artistRecordingItem =
            checkNotNull(
                adapter.loadItem(
                    id(R16MediaBrowserId.ArtistRecording(artist.artistId, recording.recordingId))
                )
            )
        assertTrue(artistRecordingItem.isPlayable())
        assertEquals(
            R16MediaBrowserId.ArtistRecording(artist.artistId, recording.recordingId),
            R16MediaBrowserIdCodec.decode(checkNotNull(artistRecordingItem.mediaId)),
        )
        assertEquals("Road trip", playlistItem.description.title)
        assertTrue(playlistItem.isBrowsable())
        assertEquals("Track", recordingItem.description.title)
        assertEquals("Artist", recordingItem.description.subtitle)
        assertTrue(recordingItem.isPlayable())
        assertEquals(
            entry.playlistEntryId.value,
            checkNotNull(R16MediaBrowserIdCodec.decode(checkNotNull(entryItem.mediaId()))).let {
                (it as R16MediaBrowserId.PlaylistEntry).id.value
            },
        )
        assertTrue(entryItem.isPlayable())
        adapter.release()
    }

    @Test
    fun `children and search stay local and clamp requested pages`() = runBlocking {
        val library = (0 until 60).map { recording("recording-$it", "Track $it") }
        val playlists = (0 until 5).map { playlistSummary("playlist-$it", "Playlist $it") }
        val browser = FakeBrowser(library = library, playlists = playlists)
        val adapter = R16MediaBrowserServiceAdapter(browser, router(this))

        val options =
            Bundle().apply {
                putInt(MediaBrowserCompat.EXTRA_PAGE, 1)
                putInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, 500)
            }
        val page = checkNotNull(adapter.loadChildren(R16MediaBrowserIdCodec.LIBRARY_SONGS, options))
        assertEquals(10, page.size)
        assertEquals("Track 50", page.first().description.title)
        assertEquals(50, browser.lastLibraryRequest?.offset)
        assertEquals(50, browser.lastLibraryRequest?.pageSize)

        val rootHints =
            Bundle().apply { putInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 3) }
        val root =
            checkNotNull(adapter.loadChildren(R16MediaBrowserIdCodec.ROOT, rootHints = rootHints))
        assertEquals(
            listOf("Library", "Artists in your Library", "Playlist 0"),
            root.map { it.description.title },
        )
        assertEquals(1, browser.lastPlaylistRequest?.pageSize)

        val search = checkNotNull(adapter.search("track", options))
        assertEquals(10, search.size)
        assertEquals("track", browser.lastSearchQuery)
        assertEquals(50, browser.lastSearchRequest?.pageSize)
        adapter.release()
    }

    @Test
    fun `malformed ids are rejected and callbacks route through playback`() = runBlocking {
        val browser = FakeBrowser()
        val dispatched = CompletableDeferred<String>()
        val router =
            router(
                this,
                onResult = { result ->
                    if (result is R16BrowserPlaybackRoutingResult.Dispatched) {
                        dispatched.complete(result.resolution.sourceMediaId)
                    }
                },
            )
        val adapter = R16MediaBrowserServiceAdapter(browser, router)

        assertNull(adapter.loadItem("legacy-song"))
        assertNull(adapter.loadChildren("legacy-parent"))
        assertEquals(0, browser.itemCalls)

        adapter.playFromMediaId("r16:recording:known")
        assertEquals("r16:recording:known", withTimeout(2_000) { dispatched.await() })
        adapter.release()
    }

    @Test
    fun `play extras decode the namespaced shuffle seed`() = runBlocking {
        val dispatched = CompletableDeferred<PlaybackCommand.PlayContext>()
        val adapter =
            R16MediaBrowserServiceAdapter(
                FakeBrowser(),
                router(this, onCommand = { dispatched.complete(it as PlaybackCommand.PlayContext) }),
            )
        val extras = Bundle().apply { putLong(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED, 7331L) }

        adapter.playFromMediaId("r16:recording:known", extras)
        val command = withTimeout(2_000) { dispatched.await() }

        assertEquals(7331L, command.shuffleSeed)
        assertEquals(command.entries.single().id, command.selectedEntryId)
        adapter.release()
    }

    @Test
    fun `partial wrong type and unknown playlist sort extras are rejected`() = runBlocking {
        val results = mutableListOf<R16BrowserPlaybackRoutingResult>()
        val adapter =
            R16MediaBrowserServiceAdapter(FakeBrowser(), router(this, onResult = results::add))

        adapter.playFromMediaId(
            "r16:recording:known",
            Bundle().apply { putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE, "CUSTOM") },
        )
        adapter.playFromMediaId(
            "r16:recording:known",
            Bundle().apply {
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION, "ASC")
            },
        )
        adapter.playFromMediaId(
            "r16:recording:known",
            Bundle().apply {
                putInt(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE, 7)
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION, "ASC")
            },
        )
        adapter.playFromMediaId(
            "r16:recording:known",
            Bundle().apply {
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE, "UNKNOWN")
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION, "ASC")
            },
        )

        assertEquals(
            listOf(
                R16BrowserPlaybackRoutingResult.InvalidRequest,
                R16BrowserPlaybackRoutingResult.InvalidRequest,
                R16BrowserPlaybackRoutingResult.InvalidRequest,
                R16BrowserPlaybackRoutingResult.InvalidRequest,
            ),
            results,
        )
        adapter.release()
    }

    @Test
    fun `filter-only playlist extras use the default custom ascending sort`() = runBlocking {
        val context = CompletableDeferred<R16MediaBrowserPlaylistPlaybackContext?>()
        val adapter =
            R16MediaBrowserServiceAdapter(
                FakeBrowser(),
                router(this, onContext = { context.complete(it) }),
            )

        adapter.playFromMediaId(
            "r16:recording:known",
            Bundle().apply { putString(R16MediaBrowserContract.EXTRA_PLAYLIST_FILTER, "road trip") },
        )
        val decoded = withTimeout(2_000) { context.await() }

        assertEquals("road trip", decoded?.search)
        assertEquals("CUSTOM", decoded?.sort?.mode?.wireValue)
        assertEquals("ASC", decoded?.sort?.direction?.wireValue)
        adapter.release()
    }

    @Test
    fun `custom sort direction is normalized before playback routing`() = runBlocking {
        val context = CompletableDeferred<R16MediaBrowserPlaylistPlaybackContext?>()
        val adapter =
            R16MediaBrowserServiceAdapter(
                FakeBrowser(),
                router(this, onContext = { context.complete(it) }),
            )
        val extras =
            Bundle().apply {
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE, "CUSTOM")
                putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION, "DESC")
            }

        adapter.playFromMediaId("r16:recording:known", extras)
        val decoded = withTimeout(2_000) { context.await() }

        assertEquals("CUSTOM", decoded?.sort?.mode?.wireValue)
        assertEquals("ASC", decoded?.sort?.direction?.wireValue)
        adapter.release()
    }

    private fun router(
        scope: kotlinx.coroutines.CoroutineScope,
        onResult: (R16BrowserPlaybackRoutingResult) -> Unit = {},
        onCommand: (PlaybackCommand) -> Unit = {},
        onContext: (R16MediaBrowserPlaylistPlaybackContext?) -> Unit = {},
    ) =
        R16BrowserPlaybackRouter(
            parentScope = scope,
            resolveMediaId = { mediaId -> ready(mediaId) },
            resolveMediaIdWithContext = { mediaId, context ->
                onContext(context)
                ready(mediaId)
            },
            resolveSearch = { query -> ready(query) },
            commands =
                object : PlaybackCommandRouter {
                    override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult {
                        onCommand(command)
                        return PlaybackCommandResult.Accepted(1, 1)
                    }
                },
            onResult = onResult,
        )

    private fun ready(sourceMediaId: String): R16BrowserQueueResolution.Ready {
        val entry =
            QueueEntry(
                id = QueueEntryId(UUID.nameUUIDFromBytes(sourceMediaId.toByteArray()).toString()),
                recordingId =
                    RecordingId(UUID.nameUUIDFromBytes(sourceMediaId.toByteArray()).toString()),
                origin = null,
                playlistEntryId = null,
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        return R16BrowserQueueResolution.Ready(sourceMediaId, listOf(entry), entry.id)
    }

    private class FakeBrowser(
        private val library: List<R16MediaBrowserRecording> = emptyList(),
        private val playlists: List<R16MediaBrowserPlaylistSummary> = emptyList(),
    ) : R16MediaBrowserRepository {
        val items = mutableListOf<R16MediaBrowserItem>()
        var itemCalls = 0
        var lastLibraryRequest: R16MediaBrowserPageRequest? = null
        var lastPlaylistRequest: R16MediaBrowserPageRequest? = null
        var lastSearchRequest: R16MediaBrowserPageRequest? = null
        var lastSearchQuery: String? = null

        override suspend fun librarySongs(page: R16MediaBrowserPageRequest) =
            makePage(library, page).also { lastLibraryRequest = page }

        override suspend fun libraryArtists(page: R16MediaBrowserPageRequest) =
            R16MediaBrowserPage<R16MediaBrowserArtistSummary>(
                emptyList(),
                page.offset,
                page.pageSize,
                false,
            )

        override suspend fun artistRecordings(
            artistId: ArtistId,
            page: R16MediaBrowserPageRequest,
        ) =
            R16MediaBrowserPage<R16MediaBrowserRecording>(
                emptyList(),
                page.offset,
                page.pageSize,
                false,
            )

        override suspend fun artistRecordingIdsForPlayback(artistId: ArtistId) =
            emptyList<RecordingId>()

        override suspend fun playlistSummaries(page: R16MediaBrowserPageRequest) =
            makePage(playlists, page).also { lastPlaylistRequest = page }

        override suspend fun playlistEntries(
            playlistId: PlaylistId,
            page: R16MediaBrowserPageRequest,
        ) =
            R16MediaBrowserPage(
                emptyList<R16MediaBrowserPlaylistEntry>(),
                page.offset,
                page.pageSize,
                false,
            )

        override suspend fun playlistEntriesForPlayback(playlistId: PlaylistId) =
            emptyList<R16MediaBrowserPlaylistPlaybackSeed>()

        override suspend fun recording(recordingId: RecordingId) =
            library.firstOrNull { it.recordingId == recordingId }

        override suspend fun playlistEntry(playlistEntryId: PlaylistEntryId) = null

        override suspend fun item(mediaId: String): R16MediaBrowserItem? {
            itemCalls++
            val decoded = R16MediaBrowserIdCodec.decode(mediaId) ?: return null
            return items.firstOrNull { it.mediaId == mediaId }
                ?: when (decoded) {
                    R16MediaBrowserId.Root -> R16MediaBrowserItem.Root
                    R16MediaBrowserId.LibrarySongs -> R16MediaBrowserItem.LibrarySongs
                    R16MediaBrowserId.LibraryArtists -> R16MediaBrowserItem.LibraryArtists
                    is R16MediaBrowserId.SystemCollection,
                    is R16MediaBrowserId.SystemCollectionRecording -> null
                    is R16MediaBrowserId.Artist,
                    is R16MediaBrowserId.ArtistRecording -> null
                    else -> null
                }
        }

        override suspend fun searchLibrary(query: String, page: R16MediaBrowserPageRequest) =
            makePage(library, page).also {
                lastSearchQuery = query
                lastSearchRequest = page
            }

        private fun <T> makePage(items: List<T>, request: R16MediaBrowserPageRequest) =
            R16MediaBrowserPage(
                items = items.drop(request.offset).take(request.pageSize),
                offset = request.offset,
                pageSize = request.pageSize,
                hasNext = false,
            )
    }

    private fun recording(seed: String, title: String) =
        R16MediaBrowserRecording(
            recordingId = RecordingId(uuid(seed)),
            title = title,
            artistDisplay = "Artist",
            releaseTitle = "Release",
            artworkLocation = "ignored://artwork",
            durationMs = 120_000,
            liked = false,
            localAssetExists = true,
            downloadAssetExists = false,
        )

    private fun playlistSummary(seed: String, name: String) =
        R16MediaBrowserPlaylistSummary(
            playlistId = PlaylistId(uuid(seed)),
            name = name,
            pinned = false,
            libraryOrderKey = 1,
            artworkOverride = "ignored://artwork",
            displaySortMode = "CUSTOM",
            displaySortDirection = "ASC",
            entryCount = 1,
            totalDurationMs = 120_000,
        )

    private fun playlistEntry(playlistId: PlaylistId, seed: String, recordingId: RecordingId) =
        R16MediaBrowserPlaylistEntry(
            playlistEntryId = PlaylistEntryId(uuid(seed)),
            playlistId = playlistId,
            recordingId = recordingId,
            orderKey = 1,
            title = "Track",
            artistDisplay = "Artist",
            releaseTitle = "Release",
            artworkLocation = "ignored://artwork",
            durationMs = 120_000,
            liked = false,
            offlineAvailable = true,
            downloadState = "READY",
            availabilitySummary = "LOCAL",
        )

    private fun id(value: R16MediaBrowserId) = R16MediaBrowserIdCodec.encode(value)

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun android.support.v4.media.MediaBrowserCompat.MediaItem.mediaId() =
        description.mediaId

    private fun android.support.v4.media.MediaBrowserCompat.MediaItem.isBrowsable() =
        flags and MediaItem.FLAG_BROWSABLE != 0

    private fun android.support.v4.media.MediaBrowserCompat.MediaItem.isPlayable() =
        flags and MediaItem.FLAG_PLAYABLE != 0
}
