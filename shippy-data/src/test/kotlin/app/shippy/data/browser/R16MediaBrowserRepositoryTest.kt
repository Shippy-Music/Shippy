/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserRepositoryTest.kt is part of Auxio.
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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.PlaylistSortDirection
import app.shippy.core.library.PlaylistSortMode
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import app.shippy.data.library.R16LibrarySongQuery
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MediaBrowserRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var repository: R16MediaBrowserRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .addCallback(R16LibraryMembershipTriggers)
                .allowMainThreadQueries()
                .build()
        repository = RoomR16MediaBrowserRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `codec round trips strict node ids and rejects malformed or legacy ids`() {
        val recording = recordingId("codec-recording")
        val artist = artistId("codec-artist")
        val playlist = playlistId("codec-playlist")
        val entry = playlistEntryId("codec-entry")

        val album = app.shippy.core.identity.ReleaseId(uuid("codec-album"))
        listOf(
                R16MediaBrowserId.Root,
                R16MediaBrowserId.LibrarySongs,
                R16MediaBrowserId.LibraryArtists,
                R16MediaBrowserId.Artist(artist),
                R16MediaBrowserId.ArtistRecording(artist, recording),
                R16MediaBrowserId.Recording(recording),
                R16MediaBrowserId.Playlist(playlist),
                R16MediaBrowserId.PlaylistEntry(entry),
                R16MediaBrowserId.Album(album),
                R16MediaBrowserId.AlbumRecording(album, recording),
                R16MediaBrowserId.Genre("Rock"),
                R16MediaBrowserId.GenreRecording("Rock", recording),
                R16MediaBrowserId.Genre("Rock: 80s"),
                R16MediaBrowserId.GenreRecording("Rock: 80s", recording),
            )
            .forEach { id ->
                assertEquals(id, R16MediaBrowserIdCodec.decode(R16MediaBrowserIdCodec.encode(id)))
            }

        assertNull(R16MediaBrowserIdCodec.decode("mi123"))
        assertNull(R16MediaBrowserIdCodec.decode("r16:recording:not-a-uuid"))
        assertNull(R16MediaBrowserIdCodec.decode("r16:recording:${recording.value}:extra"))
        assertNull(R16MediaBrowserIdCodec.decode("r16:artist-recording:${artist.value}:not-a-uuid"))
        assertNull(R16MediaBrowserIdCodec.decode(" r16:root"))
    }

    @Test
    fun `artists are current-library scoped distinct and deterministic`() = runBlocking {
        val artistA = artistId("artist-a")
        val artistB = artistId("artist-b")
        val first = recordingId("artist-first")
        val second = recordingId("artist-second")
        val outside = recordingId("artist-outside")
        insertRecordingGraph(first, "Zulu", artistA, "A", creditPositions = listOf(0, 1))
        insertRecordingGraph(second, "Alpha", artistA, "A")
        insertRecordingGraph(outside, "Outside", artistB, "B")
        database.libraryDao().upsertRelationship(libraryRelationship(first.value))
        database.libraryDao().upsertRelationship(libraryRelationship(second.value))

        val summaries = repository.libraryArtists(R16MediaBrowserPageRequest(pageSize = 50))
        assertEquals(listOf(artistA), summaries.items.map { it.artistId })
        assertEquals(2, summaries.items.single().recordingCount)
        assertFalse(summaries.hasNext)

        val songs = repository.artistRecordings(artistA, R16MediaBrowserPageRequest(pageSize = 50))
        assertEquals(listOf("Alpha", "Zulu"), songs.items.map { it.title })
        assertEquals(listOf(second, first), repository.artistRecordingIdsForPlayback(artistA))
        assertNotNull(repository.item(id(R16MediaBrowserId.Artist(artistA))))
        assertNotNull(repository.item(id(R16MediaBrowserId.ArtistRecording(artistA, first))))
        assertNull(repository.item(id(R16MediaBrowserId.ArtistRecording(artistA, outside))))
        assertNull(repository.item(id(R16MediaBrowserId.Artist(artistB))))
    }

    @Test
    fun `library page is capped and ordered with continuation`() = runBlocking {
        repeat(51) { index ->
            val id = recordingId("library-$index")
            insertRecording(id.value, "Track ${index.toString().padStart(3, '0')}")
            database.libraryDao().upsertRelationship(libraryRelationship(id.value))
        }

        val first =
            repository.librarySongs(
                R16MediaBrowserPageRequest(pageSize = R16MediaBrowserRepository.MAX_PAGE_SIZE + 100)
            )
        assertEquals(R16MediaBrowserRepository.MAX_PAGE_SIZE, first.items.size)
        assertTrue(first.hasNext)
        assertEquals(50, first.nextOffset)
        assertEquals("Track 000", first.items.first().title)
        assertEquals("Track 049", first.items.last().title)

        val second = repository.librarySongs(R16MediaBrowserPageRequest(offset = 50, pageSize = 50))
        assertEquals(listOf("Track 050"), second.items.map { it.title })
        assertFalse(second.hasNext)
    }

    @Test
    fun `playlist summaries and entries are finite ordered and occurrence safe`() = runBlocking {
        val recording = recordingId("playlist-recording")
        insertRecording(recording.value, "Repeated")
        val playlistA = playlistId("playlist-a")
        val playlistB = playlistId("playlist-b")
        val entries =
            listOf(
                playlistEntry(playlistA.value, playlistEntryId("entry-a-1"), recording, 1),
                playlistEntry(playlistA.value, playlistEntryId("entry-a-2"), recording, 2),
                playlistEntry(playlistA.value, playlistEntryId("entry-a-3"), recording, 3),
            )
        database.playlistDao().create(playlist(playlistA, "A", pinned = false, order = 2), entries)
        database
            .playlistDao()
            .create(playlist(playlistB, "B", pinned = true, order = 1), emptyList())
        database
            .libraryDao()
            .upsertLayout(
                listOf(
                    LibraryLayoutEntryEntity(
                        "PLAYLIST",
                        playlistA.value,
                        pinned = false,
                        orderKey = 2,
                    ),
                    LibraryLayoutEntryEntity(
                        "PLAYLIST",
                        playlistB.value,
                        pinned = true,
                        orderKey = 1,
                    ),
                )
            )

        val summaries = repository.playlistSummaries(R16MediaBrowserPageRequest(pageSize = 1))
        assertEquals(listOf(playlistB), summaries.items.map { it.playlistId })
        assertTrue(summaries.hasNext)
        val secondSummary =
            repository.playlistSummaries(R16MediaBrowserPageRequest(offset = 1)).items.single()
        assertEquals(playlistA, secondSummary.playlistId)
        assertEquals(3, secondSummary.entryCount)

        val page = repository.playlistEntries(playlistA, R16MediaBrowserPageRequest(pageSize = 2))
        assertEquals(
            listOf(playlistEntryId("entry-a-1").value, playlistEntryId("entry-a-2").value),
            page.items.map { it.playlistEntryId.value },
        )
        assertTrue(page.hasNext)
        val tail = repository.playlistEntries(playlistA, R16MediaBrowserPageRequest(offset = 2))
        assertEquals(
            listOf(playlistEntryId("entry-a-3").value),
            tail.items.map { it.playlistEntryId.value },
        )
        assertEquals(recording, tail.items.single().recordingId)
        assertNotNull(repository.playlistEntry(playlistEntryId("entry-a-2")))
    }

    @Test
    fun `playlist temporal sort keeps paging and full playback order identical`() = runBlocking {
        val playlist = playlistId("temporal-playlist")
        val oldest = recordingId("temporal-oldest")
        val middle = recordingId("temporal-middle")
        val newest = recordingId("temporal-newest")
        listOf(oldest to "Oldest", middle to "Middle", newest to "Newest").forEach {
            (recording, title) ->
            insertRecording(recording.value, title)
            database.libraryDao().upsertRelationship(libraryRelationship(recording.value))
        }
        database
            .playlistDao()
            .create(
                playlist(playlist, "Temporal", pinned = false, order = 1),
                listOf(
                    playlistEntry(
                        playlist.value,
                        playlistEntryId("temporal-middle-entry"),
                        middle,
                        1,
                        20,
                    ),
                    playlistEntry(
                        playlist.value,
                        playlistEntryId("temporal-oldest-entry"),
                        oldest,
                        2,
                        10,
                    ),
                    playlistEntry(
                        playlist.value,
                        playlistEntryId("temporal-newest-entry"),
                        newest,
                        3,
                        30,
                    ),
                ),
            )

        val recent = PlaylistSort(PlaylistSortMode.RECENTLY_ADDED, PlaylistSortDirection.ASCENDING)
        val oldestSort =
            PlaylistSort(PlaylistSortMode.OLDEST_ADDED, PlaylistSortDirection.ASCENDING)
        val recentPage =
            repository.playlistEntries(playlist, R16MediaBrowserPageRequest(pageSize = 50), recent)
        val oldestPage =
            repository.playlistEntries(
                playlist,
                R16MediaBrowserPageRequest(pageSize = 50),
                oldestSort,
            )
        val recentPlayback =
            repository.playlistEntriesForPlayback(
                playlist,
                R16MediaBrowserPlaylistPlaybackContext(sort = recent),
            )
        val oldestPlayback =
            repository.playlistEntriesForPlayback(
                playlist,
                R16MediaBrowserPlaylistPlaybackContext(sort = oldestSort),
            )

        assertEquals(
            listOf(
                playlistEntryId("temporal-newest-entry").value,
                playlistEntryId("temporal-middle-entry").value,
                playlistEntryId("temporal-oldest-entry").value,
            ),
            recentPage.items.map { it.playlistEntryId.value },
        )
        assertEquals(
            listOf(
                playlistEntryId("temporal-oldest-entry").value,
                playlistEntryId("temporal-middle-entry").value,
                playlistEntryId("temporal-newest-entry").value,
            ),
            oldestPage.items.map { it.playlistEntryId.value },
        )
        assertEquals(
            recentPage.items.map { it.playlistEntryId.value },
            recentPlayback.map { it.playlistEntryId.value },
        )
        assertEquals(
            oldestPage.items.map { it.playlistEntryId.value },
            oldestPlayback.map { it.playlistEntryId.value },
        )
        assertTrue(
            repository
                .playlistEntriesForPlayback(
                    playlist,
                    R16MediaBrowserPlaylistPlaybackContext(search = "---", sort = recent),
                )
                .isEmpty()
        )
    }

    @Test
    fun `playlist summaries use layout pinning and order instead of playlist fields`() =
        runBlocking {
            val layoutPinned = playlistId("layout-pinned")
            val layoutUnpinned = playlistId("layout-unpinned")
            val missingLayout = playlistId("missing-layout")
            database
                .playlistDao()
                .create(
                    playlist(layoutPinned, "Layout pinned", pinned = false, order = Long.MAX_VALUE),
                    emptyList(),
                )
            database
                .playlistDao()
                .create(
                    playlist(layoutUnpinned, "Layout unpinned", pinned = true, order = 0),
                    emptyList(),
                )
            database
                .playlistDao()
                .create(
                    playlist(missingLayout, "Missing layout", pinned = true, order = 1),
                    emptyList(),
                )
            database
                .libraryDao()
                .upsertLayout(
                    listOf(
                        LibraryLayoutEntryEntity(
                            "PLAYLIST",
                            layoutPinned.value,
                            pinned = true,
                            orderKey = 20,
                        ),
                        LibraryLayoutEntryEntity(
                            "PLAYLIST",
                            layoutUnpinned.value,
                            pinned = false,
                            orderKey = 10,
                        ),
                    )
                )

            val summaries =
                repository.playlistSummaries(R16MediaBrowserPageRequest(pageSize = 50)).items

            assertEquals(
                listOf(layoutPinned, layoutUnpinned, missingLayout),
                summaries.map { it.playlistId },
            )
            assertEquals(listOf(true, false, false), summaries.map { it.pinned })
            assertEquals(listOf(20L, 10L, Long.MAX_VALUE), summaries.map { it.libraryOrderKey })
        }

    @Test
    fun `exact items and local library search stay scoped and sanitized`() = runBlocking {
        val matching = recordingId("matching")
        val hidden = recordingId("hidden")
        insertRecording(matching.value, "River Hidden")
        insertRecording(hidden.value, "River Outside")
        database.libraryDao().upsertRelationship(libraryRelationship(matching.value))

        val results = repository.searchLibrary("river OR hidden")
        assertEquals(listOf(matching), results.items.map { it.recordingId })
        assertTrue(R16LibrarySongQuery("river + OR hidden").ftsMatchOrNull() == "river* hidden*")
        assertTrue(repository.searchLibrary("---").items.isEmpty())

        val itemId = R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(matching))
        assertEquals(
            matching,
            (repository.item(itemId) as R16MediaBrowserItem.Recording).recording.recordingId,
        )
        assertEquals(R16MediaBrowserItem.Root, repository.item(R16MediaBrowserIdCodec.ROOT))
        assertEquals(
            hidden,
            (repository.item(R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(hidden)))
                    as R16MediaBrowserItem.Recording)
                .recording
                .recordingId,
        )
    }

    private suspend fun insertRecording(recordingId: String, title: String) {
        val artist =
            ArtistEntity(
                artistId = artistId("shared").value,
                canonicalName = "Artist",
                sortName = null,
                disambiguation = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            )
        CanonicalWriteTransactions(database)
            .upsertRecordingGraph(
                recording =
                    RecordingEntity(
                        recordingId = recordingId,
                        canonicalTitle = title,
                        durationMs = 120_000,
                        versionKind = "ORIGINAL",
                        versionLabel = null,
                        explicitness = "UNKNOWN",
                        preferredReleaseId = null,
                        preferredArtworkId = null,
                        retentionKind = "DURABLE",
                        retainedUntilEpochMs = null,
                        createdAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    ),
                artists = listOf(artist),
                credits =
                    listOf(
                        RecordingArtistCreditEntity(
                            recordingId = recordingId,
                            position = 0,
                            artistId = artist.artistId,
                            creditedName = artist.canonicalName,
                            joinPhrase = "",
                        )
                    ),
            )
    }

    private suspend fun insertRecordingGraph(
        recordingId: app.shippy.core.identity.RecordingId,
        title: String,
        artistId: app.shippy.core.identity.ArtistId,
        artistName: String,
        creditPositions: List<Int> = listOf(0),
    ) {
        val artist =
            ArtistEntity(
                artistId = artistId.value,
                canonicalName = artistName,
                sortName = null,
                disambiguation = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            )
        CanonicalWriteTransactions(database)
            .upsertRecordingGraph(
                recording =
                    RecordingEntity(
                        recordingId = recordingId.value,
                        canonicalTitle = title,
                        durationMs = 120_000,
                        versionKind = "ORIGINAL",
                        versionLabel = null,
                        explicitness = "UNKNOWN",
                        preferredReleaseId = null,
                        preferredArtworkId = null,
                        retentionKind = "DURABLE",
                        retainedUntilEpochMs = null,
                        createdAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    ),
                artists = listOf(artist),
                credits =
                    creditPositions.map { position ->
                        RecordingArtistCreditEntity(
                            recordingId = recordingId.value,
                            position = position,
                            artistId = artistId.value,
                            creditedName = artistName,
                            joinPhrase = "",
                        )
                    },
            )
    }

    private fun libraryRelationship(recordingId: String) =
        LibraryRecordingEntity(
            recordingId = recordingId,
            liked = false,
            explicitlySaved = false,
            userEdited = false,
            manuallyIdentified = false,
            firstAddedAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun playlist(
        id: app.shippy.core.identity.PlaylistId,
        name: String,
        pinned: Boolean,
        order: Long,
    ) =
        PlaylistEntity(
            playlistId = id.value,
            name = name,
            pinned = pinned,
            libraryOrderKey = order,
            artworkOverride = null,
            displaySortMode = "CUSTOM",
            displaySortDirection = "ASC",
            originKind = "USER",
            originKey = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun playlistEntry(
        playlistId: String,
        entryId: app.shippy.core.identity.PlaylistEntryId,
        recordingId: app.shippy.core.identity.RecordingId,
        order: Long,
        addedAt: Long = 1,
    ) =
        PlaylistEntryEntity(
            playlistEntryId = entryId.value,
            playlistId = playlistId,
            recordingId = recordingId.value,
            orderKey = order,
            addedAtEpochMs = addedAt,
        )

    private fun recordingId(seed: String) = app.shippy.core.identity.RecordingId(uuid(seed))

    private fun id(value: R16MediaBrowserId) = R16MediaBrowserIdCodec.encode(value)

    private fun playlistId(seed: String) = app.shippy.core.identity.PlaylistId(uuid(seed))

    private fun playlistEntryId(seed: String) = app.shippy.core.identity.PlaylistEntryId(uuid(seed))

    private fun artistId(seed: String) = app.shippy.core.identity.ArtistId(uuid(seed))

    private fun uuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
}
