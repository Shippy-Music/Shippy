/*
 * Copyright (c) 2026 Auxio Project
 * R16BrowserQueueResolverTest.kt is part of Auxio.
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
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.PlaylistSortDirection
import app.shippy.core.library.PlaylistSortMode
import app.shippy.core.library.R16SystemCollection
import app.shippy.core.queue.PlaybackOriginKind
import app.shippy.core.queue.QueueReducer
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R16BrowserQueueResolverTest {
    @Test
    fun `playlist duplicate occurrences retain exact origin and distinct queue identities`() =
        runBlocking {
            val recordingId = recordingId("same-recording")
            val playlistId = playlistId("playlist")
            val first = playlistEntry(playlistId, "first", recordingId, 1)
            val second = playlistEntry(playlistId, "second", recordingId, 2)
            val repository = FakeBrowser(playlistId, listOf(first, second))
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })

            val result = resolver.resolvePlay(id(R16MediaBrowserId.Playlist(playlistId)))

            val ready = result as R16BrowserQueueResolution.Ready
            assertEquals(2, ready.entries.size)
            assertEquals(
                listOf(first.playlistEntryId, second.playlistEntryId),
                ready.entries.map { it.playlistEntryId },
            )
            assertEquals(listOf(recordingId, recordingId), ready.entries.map { it.recordingId })
            assertEquals(2, ready.entries.map { it.id }.toSet().size)
            assertNotEquals(ready.entries[0].id, ready.entries[1].id)
            assertEquals(PlaybackOriginKind.PLAYLIST, ready.entries[0].origin?.kind)
            assertEquals(recordingId, ready.selectedRecordingId)
            assertEquals(ready.selectedEntryId, ready.playCommand().selectedEntryId)
        }

    @Test
    fun `playlist playback keeps the complete ordered context beyond browser page size`() =
        runBlocking {
            val playlistId = playlistId("large-playlist")
            val recordingId = recordingId("repeated-recording")
            val rows =
                (0 until 123).map { index ->
                    playlistEntry(playlistId, "large-$index", recordingId, index.toLong())
                }
            val selectedIndex = 97
            val selectedMediaId =
                id(R16MediaBrowserId.PlaylistEntry(rows[selectedIndex].playlistEntryId))
            val repository = FakeBrowser(playlistId, rows)
            val resolver =
                R16BrowserQueueResolver(repository, now = { Instant.EPOCH }, maxQueueEntries = 1)

            val playlistResult =
                resolver.resolvePlay(id(R16MediaBrowserId.Playlist(playlistId)))
                    as R16BrowserQueueResolution.Ready
            assertEquals(rows.size, playlistResult.entries.size)
            assertEquals(
                rows.map(R16MediaBrowserPlaylistEntry::playlistEntryId),
                playlistResult.entries.map { it.playlistEntryId },
            )
            assertEquals(1, repository.playbackPlaylistQueries)
            assertTrue(repository.playlistPageSizes.isEmpty())

            repository.playbackPlaylistQueries = 0
            val exactResult =
                resolver.resolvePlay(selectedMediaId) as R16BrowserQueueResolution.Ready
            assertEquals(rows.size, exactResult.entries.size)
            assertEquals(
                rows[selectedIndex].playlistEntryId,
                exactResult.entries[selectedIndex].playlistEntryId,
            )
            assertEquals(exactResult.entries[selectedIndex].id, exactResult.selectedEntryId)
            assertNotEquals(playlistResult.entries.map { it.id }, exactResult.entries.map { it.id })
            assertEquals(1, repository.playbackPlaylistQueries)
            assertTrue(repository.playlistPageSizes.isEmpty())

            val shuffle = exactResult.playCommand(shuffleSeed = 42L)
            assertEquals(rows.size, shuffle.entries.size)
            assertEquals(
                QueueReducer()
                    .replace(shuffle.entries, exactResult.selectedEntryId, 42L)
                    .traversalOrder
                    .first(),
                shuffle.selectedEntryId,
            )
            assertEquals(
                rows.map(R16MediaBrowserPlaylistEntry::playlistEntryId).toSet(),
                shuffle.entries.mapNotNull { it.playlistEntryId }.toSet(),
            )
            assertEquals(
                rows.map(R16MediaBrowserPlaylistEntry::recordingId),
                shuffle.entries.map { it.recordingId },
            )
        }

    @Test
    fun `playlist play without extras uses the persisted display sort`() = runBlocking {
        val playlistId = playlistId("persisted-sort-playlist")
        val rows = listOf(playlistEntry(playlistId, "one", recordingId("one"), 1))
        val persistedSort = PlaylistSort(PlaylistSortMode.TITLE, PlaylistSortDirection.DESCENDING)
        val repository =
            FakeBrowser(playlist = playlistId, playlistRows = rows, displaySort = persistedSort)
        val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })

        val result =
            resolver.resolvePlay(
                R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId))
            )

        assertTrue(result is R16BrowserQueueResolution.Ready)
        assertEquals(listOf(persistedSort), repository.playbackSorts)
    }

    @Test
    fun `library search is local bounded and selects exact recording identity`() = runBlocking {
        val recordings = (0 until 8).map { recording("recording-$it", "Track $it") }
        val repository = FakeBrowser(library = recordings)
        val resolver =
            R16BrowserQueueResolver(repository, now = { Instant.EPOCH }, maxQueueEntries = 3)
        val selected =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recordings[1].recordingId))

        val result =
            resolver.resolveLibrarySearch(
                query = "track",
                page = R16MediaBrowserPageRequest(pageSize = 100),
                selectedMediaId = selected,
            )

        val ready = result as R16BrowserQueueResolution.Ready
        assertEquals(3, ready.entries.size)
        assertEquals(recordings[1].recordingId, ready.selectedRecordingId)
        assertEquals(PlaybackOriginKind.SEARCH, ready.entries[0].origin?.kind)
        assertEquals(3, repository.lastSearchPageSize)
    }

    @Test
    fun `system filtered play shuffle and row selection retain only matching context`() =
        runBlocking {
            for (collection in R16SystemCollection.entries) {
                val matching = recording("match", "Underwater")
                val other = recording("other", "Other")
                val repository =
                    FakeBrowser(collectionRows = mapOf(collection to listOf(matching, other)))
                val resolver = R16BrowserQueueResolver(repository)
                for (mediaId in
                    listOf(
                        id(R16MediaBrowserId.SystemCollection(collection)),
                        id(
                            R16MediaBrowserId.SystemCollectionRecording(
                                collection,
                                matching.recordingId,
                            )
                        ),
                    )) {
                    val ready =
                        resolver.resolvePlay(mediaId, songsQuery = "rWaTeR")
                            as R16BrowserQueueResolution.Ready
                    assertEquals(listOf(matching.recordingId), ready.entries.map { it.recordingId })
                    assertEquals(
                        listOf(matching.recordingId),
                        ready.playCommand(shuffleSeed = 42L).entries.map { it.recordingId },
                    )
                }
                assertTrue(
                    resolver.resolvePlay(
                        id(
                            R16MediaBrowserId.SystemCollectionRecording(
                                collection,
                                other.recordingId,
                            )
                        ),
                        songsQuery = "rWaTeR",
                    ) is R16BrowserQueueResolution.Rejected
                )
            }
        }

    @Test
    fun `system collection keeps full ID-only context while its display stays paged`() =
        runBlocking {
            val collection = R16SystemCollection.DOWNLOADS
            val rows = (0 until 105).map { recording("download-$it", "Download $it") }
            val selected = rows[87]
            val repository = FakeBrowser(collectionRows = mapOf(collection to rows))
            val resolver = R16BrowserQueueResolver(repository, maxQueueEntries = 1)
            val selectedMediaId =
                id(R16MediaBrowserId.SystemCollectionRecording(collection, selected.recordingId))

            val result = resolver.resolvePlay(selectedMediaId) as R16BrowserQueueResolution.Ready

            assertEquals(rows.map { it.recordingId }, result.entries.map { it.recordingId })
            assertEquals(selected.recordingId, result.selectedRecordingId)
            assertTrue(
                result.entries.all { it.origin?.kind == PlaybackOriginKind.SYSTEM_COLLECTION }
            )
            assertTrue(result.entries.all { it.origin?.referenceId == collection.id })
            assertEquals(1, repository.systemPlaybackQueries)
            assertTrue(repository.systemPageSizes.isEmpty())
            assertEquals(0, repository.providerCalls)
        }

    @Test
    fun `artist playback keeps selected composite identity and exact artist origin`() =
        runBlocking {
            val artist = artistId("artist")
            val rows = (0 until 3).map { recording("artist-recording-$it", "Track $it") }
            val repository = FakeBrowser(artist = artist, artistRows = rows)
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })
            val selected = id(R16MediaBrowserId.ArtistRecording(artist, rows[1].recordingId))

            val result = resolver.resolvePlay(selected) as R16BrowserQueueResolution.Ready

            assertEquals(rows.map { it.recordingId }, result.entries.map { it.recordingId })
            assertEquals(rows[1].recordingId, result.selectedRecordingId)
            assertEquals(PlaybackOriginKind.ARTIST, result.entries[0].origin?.kind)
            assertEquals(artist.value, result.entries[0].origin?.referenceId)
            val second = resolver.resolvePlay(selected) as R16BrowserQueueResolution.Ready
            assertNotEquals(result.entries.map { it.id }, second.entries.map { it.id })
        }

    @Test
    fun `malformed stale and out of-page ids are rejected before playback command creation`() =
        runBlocking {
            val recording = recording("only", "Only")
            val other = recording("other", "Other")
            val repository = FakeBrowser(library = listOf(recording, other))
            val resolver = R16BrowserQueueResolver(repository, maxQueueEntries = 1)

            val malformed = resolver.resolvePlay("legacy-song-id")
            assertEquals(
                R16BrowserQueueRejection.MALFORMED_MEDIA_ID,
                (malformed as R16BrowserQueueResolution.Rejected).reason,
            )

            val staleId =
                R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recordingId("missing")))
            val stale = resolver.resolvePlay(staleId)
            assertEquals(
                R16BrowserQueueRejection.STALE_MEDIA_ID,
                (stale as R16BrowserQueueResolution.Rejected).reason,
            )

            val outOfPage =
                resolver.resolveContainer(
                    R16MediaBrowserIdCodec.LIBRARY_SONGS,
                    selectedMediaId =
                        R16MediaBrowserIdCodec.encode(
                            R16MediaBrowserId.Recording(other.recordingId)
                        ),
                )
            assertEquals(
                R16BrowserQueueRejection.SELECTION_NOT_IN_PAGE,
                (outOfPage as R16BrowserQueueResolution.Rejected).reason,
            )
            assertTrue(repository.providerCalls == 0)
        }

    @Test
    fun `songs row tap resolves complete ordered context and exact selected song index`() =
        runBlocking {
            val songs = (0 until 50).map { index -> recording("song-$index", "Song $index") }
            val repository = FakeBrowser(library = songs)
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })
            val selectedIndex = 17
            val selectedMediaId =
                R16MediaBrowserIdCodec.encode(
                    R16MediaBrowserId.Recording(songs[selectedIndex].recordingId)
                )

            val result = resolver.resolvePlay(mediaId = selectedMediaId, songsQuery = "")

            val ready = result as R16BrowserQueueResolution.Ready
            assertEquals(50, ready.entries.size)
            assertEquals(selectedIndex, ready.selectedIndex)
            assertEquals(songs[selectedIndex].recordingId, ready.selectedRecordingId)
            assertEquals(PlaybackOriginKind.LOCAL_LIBRARY, ready.entries[0].origin?.kind)
            assertEquals(1, repository.librarySongPlaybackQueries)
        }

    @Test
    fun `songs playback with filter matches filtered songs and rejects missing selected song`() =
        runBlocking {
            val matchingOne = recording("seed-1", "Alpha Song")
            val matchingTwo = recording("seed-2", "Alpha Track")
            val other = recording("seed-3", "Beta Tune")
            val repository = FakeBrowser(library = listOf(matchingOne, matchingTwo, other))
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })
            val selectedMediaId =
                R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(matchingTwo.recordingId))

            val result = resolver.resolvePlay(mediaId = selectedMediaId, songsQuery = "Alpha")

            val ready = result as R16BrowserQueueResolution.Ready
            assertEquals(2, ready.entries.size)
            assertEquals(1, ready.selectedIndex)
            assertEquals(matchingTwo.recordingId, ready.selectedRecordingId)
            assertEquals(PlaybackOriginKind.SEARCH, ready.entries[0].origin?.kind)

            val staleSelectedMediaId =
                R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(other.recordingId))
            val staleResult =
                resolver.resolvePlay(mediaId = staleSelectedMediaId, songsQuery = "Alpha")
            assertTrue(staleResult is R16BrowserQueueResolution.Rejected)
            assertEquals(
                R16BrowserQueueRejection.SELECTION_NOT_IN_PAGE,
                (staleResult as R16BrowserQueueResolution.Rejected).reason,
            )
        }

    @Test
    fun `10k songs context resolves lightweight queue without paging limit truncation`() =
        runBlocking {
            val largeList =
                (0 until 10_000).map { index -> recording("huge-$index", "Track $index") }
            val repository = FakeBrowser(library = largeList)
            val resolver =
                R16BrowserQueueResolver(repository, now = { Instant.EPOCH }, maxQueueEntries = 50)
            val selectedIndex = 7_420
            val selectedMediaId =
                R16MediaBrowserIdCodec.encode(
                    R16MediaBrowserId.Recording(largeList[selectedIndex].recordingId)
                )

            val result = resolver.resolvePlay(mediaId = selectedMediaId, songsQuery = "")

            val ready = result as R16BrowserQueueResolution.Ready
            assertEquals(10_000, ready.entries.size)
            assertEquals(selectedIndex, ready.selectedIndex)
            assertEquals(largeList[selectedIndex].recordingId, ready.selectedRecordingId)
        }

    @Test
    fun `album playback resolves full ordered album context with correct selected occurrence`() =
        runBlocking {
            val album = releaseId("album-1")
            val rows = (0 until 5).map { recording("album-rec-$it", "Album Track $it") }
            val repository = FakeBrowser(album = album, albumRows = rows)
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })
            val selected = id(R16MediaBrowserId.AlbumRecording(album, rows[2].recordingId))

            val result = resolver.resolvePlay(selected) as R16BrowserQueueResolution.Ready

            assertEquals(5, result.entries.size)
            assertEquals(2, result.selectedIndex)
            assertEquals(rows[2].recordingId, result.selectedRecordingId)
            assertEquals(PlaybackOriginKind.RELEASE, result.entries[0].origin?.kind)
            assertEquals(album.value, result.entries[0].origin?.referenceId)
        }

    @Test
    fun `genre playback resolves full ordered genre context with correct selected occurrence`() =
        runBlocking {
            val genre = "Rock: 80s"
            val rows = (0 until 4).map { recording("genre-rec-$it", "Genre Track $it") }
            val repository = FakeBrowser(genreName = genre, genreRows = rows)
            val resolver = R16BrowserQueueResolver(repository, now = { Instant.EPOCH })
            val selected = id(R16MediaBrowserId.GenreRecording(genre, rows[1].recordingId))

            val result = resolver.resolvePlay(selected) as R16BrowserQueueResolution.Ready

            assertEquals(4, result.entries.size)
            assertEquals(1, result.selectedIndex)
            assertEquals(rows[1].recordingId, result.selectedRecordingId)
            assertEquals(PlaybackOriginKind.LOCAL_LIBRARY, result.entries[0].origin?.kind)
            assertEquals(genre, result.entries[0].origin?.referenceId)
        }

    private class FakeBrowser(
        private val playlist: PlaylistId? = null,
        private val playlistRows: List<R16MediaBrowserPlaylistEntry> = emptyList(),
        private val library: List<R16MediaBrowserRecording> = emptyList(),
        private val collectionRows: Map<R16SystemCollection, List<R16MediaBrowserRecording>> =
            emptyMap(),
        private val artist: ArtistId? = null,
        private val artistRows: List<R16MediaBrowserRecording> = emptyList(),
        private val album: app.shippy.core.identity.ReleaseId? = null,
        private val albumRows: List<R16MediaBrowserRecording> = emptyList(),
        private val genreName: String? = null,
        private val genreRows: List<R16MediaBrowserRecording> = emptyList(),
        private val displaySort: PlaylistSort = PlaylistSort(),
    ) : R16MediaBrowserRepository {
        var providerCalls = 0
        var lastSearchPageSize = 0
        val playlistPageSizes = mutableListOf<Int>()
        var playbackPlaylistQueries = 0
        val playbackSorts = mutableListOf<PlaylistSort>()
        var systemPlaybackQueries = 0
        val systemPageSizes = mutableListOf<Int>()
        var librarySongPlaybackQueries = 0

        override suspend fun librarySongs(page: R16MediaBrowserPageRequest) =
            makePage(library, page)

        override suspend fun librarySongRecordingIdsForPlayback(query: String?): List<RecordingId> {
            librarySongPlaybackQueries++
            val items =
                if (query.isNullOrBlank()) library
                else library.filter { it.title.contains(query, ignoreCase = true) }
            return items.map { it.recordingId }
        }

        override suspend fun libraryArtists(page: R16MediaBrowserPageRequest) =
            makePage(
                listOfNotNull(
                    artist?.let {
                        R16MediaBrowserArtistSummary(
                            artistId = it,
                            canonicalName = "Artist",
                            recordingCount = artistRows.size,
                        )
                    }
                ),
                page,
            )

        override suspend fun artistRecordings(
            artistId: ArtistId,
            page: R16MediaBrowserPageRequest,
        ) = makePage(if (artistId == artist) artistRows else emptyList(), page)

        override suspend fun artistRecordingIdsForPlayback(artistId: ArtistId) =
            if (artistId == artist) artistRows.map { it.recordingId } else emptyList()

        override suspend fun albumRecordingIdsForPlayback(
            releaseId: app.shippy.core.identity.ReleaseId
        ) = if (releaseId == album) albumRows.map { it.recordingId } else emptyList()

        override suspend fun genreRecordingIdsForPlayback(genre: String) =
            if (genre == genreName) genreRows.map { it.recordingId } else emptyList()

        override suspend fun playlistSummaries(page: R16MediaBrowserPageRequest) =
            makePage(
                listOfNotNull(
                    playlist?.let {
                        R16MediaBrowserPlaylistSummary(
                            playlistId = it,
                            name = "Playlist",
                            pinned = false,
                            libraryOrderKey = 1,
                            artworkOverride = null,
                            displaySortMode = displaySort.mode.wireValue,
                            displaySortDirection = displaySort.direction.wireValue,
                            entryCount = playlistRows.size,
                            totalDurationMs = playlistRows.sumOf { row -> row.durationMs ?: 0 },
                        )
                    }
                ),
                page,
            )

        override suspend fun systemCollection(
            collection: R16SystemCollection,
            page: R16MediaBrowserPageRequest,
        ): R16MediaBrowserPage<R16MediaBrowserRecording> {
            systemPageSizes += page.pageSize
            return makePage(collectionRows[collection].orEmpty(), page)
        }

        override suspend fun systemCollectionRecordingIdsForPlayback(
            collection: R16SystemCollection
        ) =
            collectionRows[collection]
                .orEmpty()
                .map { it.recordingId }
                .also { systemPlaybackQueries++ }

        override suspend fun systemCollectionRecordingIdsForPlayback(
            collection: R16SystemCollection,
            query: String?,
        ) =
            collectionRows[collection]
                .orEmpty()
                .filter { query == null || it.title.contains(query, ignoreCase = true) }
                .map { it.recordingId }
                .also { systemPlaybackQueries++ }

        override suspend fun playlistEntries(
            playlistId: PlaylistId,
            page: R16MediaBrowserPageRequest,
        ): R16MediaBrowserPage<R16MediaBrowserPlaylistEntry> {
            playlistPageSizes += page.pageSize
            return makePage(playlistRows.filter { it.playlistId == playlistId }, page)
        }

        override suspend fun playlistEntriesForPlayback(playlistId: PlaylistId) =
            playlistRows
                .filter { it.playlistId == playlistId }
                .map {
                    R16MediaBrowserPlaylistPlaybackSeed(
                        playlistEntryId = it.playlistEntryId,
                        playlistId = it.playlistId,
                        recordingId = it.recordingId,
                        orderKey = it.orderKey,
                    )
                }
                .also { playbackPlaylistQueries++ }

        override suspend fun playlistEntriesForPlayback(
            playlistId: PlaylistId,
            context: R16MediaBrowserPlaylistPlaybackContext,
        ): List<R16MediaBrowserPlaylistPlaybackSeed> {
            playbackSorts += context.sort
            return playlistEntriesForPlayback(playlistId)
        }

        override suspend fun recording(recordingId: RecordingId) =
            library.firstOrNull { it.recordingId == recordingId }

        override suspend fun playlistEntry(playlistEntryId: PlaylistEntryId) =
            playlistRows.firstOrNull { it.playlistEntryId == playlistEntryId }

        override suspend fun item(mediaId: String): R16MediaBrowserItem? {
            return when (val decoded = R16MediaBrowserIdCodec.decode(mediaId)) {
                R16MediaBrowserId.Root -> R16MediaBrowserItem.Root
                R16MediaBrowserId.LibrarySongs -> R16MediaBrowserItem.LibrarySongs
                R16MediaBrowserId.LibraryArtists -> R16MediaBrowserItem.LibraryArtists
                is R16MediaBrowserId.SystemCollection ->
                    R16MediaBrowserItem.SystemCollection(decoded.collection)
                is R16MediaBrowserId.SystemCollectionRecording ->
                    collectionRows[decoded.collection]
                        .orEmpty()
                        .firstOrNull { it.recordingId == decoded.recordingId }
                        ?.let {
                            R16MediaBrowserItem.SystemCollectionRecording(decoded.collection, it)
                        }
                is R16MediaBrowserId.Recording ->
                    recording(decoded.id)?.let(R16MediaBrowserItem::Recording)
                is R16MediaBrowserId.Artist ->
                    if (decoded.id == artist) {
                        R16MediaBrowserItem.Artist(
                            R16MediaBrowserArtistSummary(
                                artistId = decoded.id,
                                canonicalName = "Artist",
                                recordingCount = artistRows.size,
                            )
                        )
                    } else null
                is R16MediaBrowserId.ArtistRecording ->
                    if (decoded.artistId == artist) {
                        artistRows
                            .firstOrNull { it.recordingId == decoded.recordingId }
                            ?.let { R16MediaBrowserItem.ArtistRecording(decoded.artistId, it) }
                    } else null
                is R16MediaBrowserId.PlaylistEntry ->
                    playlistEntry(decoded.id)?.let(R16MediaBrowserItem::PlaylistEntry)
                is R16MediaBrowserId.Playlist ->
                    if (decoded.id == playlist) {
                        R16MediaBrowserItem.Playlist(
                            checkNotNull(
                                playlistSummaries(R16MediaBrowserPageRequest()).items.firstOrNull()
                            )
                        )
                    } else null
                is R16MediaBrowserId.Album -> R16MediaBrowserItem.Album(decoded.id, "Test Album")
                is R16MediaBrowserId.AlbumRecording ->
                    albumRows
                        .firstOrNull { it.recordingId == decoded.recordingId }
                        ?.let { R16MediaBrowserItem.AlbumRecording(decoded.releaseId, it) }
                is R16MediaBrowserId.Genre -> R16MediaBrowserItem.Genre(decoded.name)
                is R16MediaBrowserId.GenreRecording ->
                    genreRows
                        .firstOrNull { it.recordingId == decoded.recordingId }
                        ?.let { R16MediaBrowserItem.GenreRecording(decoded.genre, it) }
                null -> null
            }
        }

        override suspend fun searchLibrary(
            query: String,
            page: R16MediaBrowserPageRequest,
        ): R16MediaBrowserPage<R16MediaBrowserRecording> {
            lastSearchPageSize = page.pageSize
            return makePage(library, page)
        }

        private fun <T> makePage(items: List<T>, request: R16MediaBrowserPageRequest) =
            R16MediaBrowserPage(
                items = items.drop(request.offset).take(request.pageSize),
                offset = request.offset,
                pageSize = request.pageSize,
                hasNext = request.offset + request.pageSize < items.size,
            )
    }

    private fun recording(seed: String, title: String) =
        R16MediaBrowserRecording(
            recordingId = recordingId(seed),
            title = title,
            artistDisplay = "Artist",
            releaseTitle = null,
            artworkLocation = null,
            durationMs = 120_000,
            liked = false,
            localAssetExists = false,
            downloadAssetExists = false,
        )

    private fun playlistEntry(
        playlistId: PlaylistId,
        seed: String,
        recordingId: RecordingId,
        orderKey: Long,
    ) =
        R16MediaBrowserPlaylistEntry(
            playlistEntryId = PlaylistEntryId(uuid("entry-$seed")),
            playlistId = playlistId,
            recordingId = recordingId,
            orderKey = orderKey,
            title = "Track",
            artistDisplay = "Artist",
            releaseTitle = null,
            artworkLocation = null,
            durationMs = 120_000,
            liked = false,
            offlineAvailable = false,
            downloadState = "NONE",
            availabilitySummary = "UNKNOWN",
        )

    private fun id(value: R16MediaBrowserId) = R16MediaBrowserIdCodec.encode(value)

    private fun recordingId(seed: String) = RecordingId(uuid(seed))

    private fun artistId(seed: String) = ArtistId(uuid(seed))

    private fun releaseId(seed: String) = app.shippy.core.identity.ReleaseId(uuid(seed))

    private fun playlistId(seed: String) = PlaylistId(uuid(seed))

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}
