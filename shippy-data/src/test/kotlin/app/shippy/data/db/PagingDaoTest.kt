/*
 * Copyright (c) 2026 Auxio Project
 * PagingDaoTest.kt is part of Auxio.
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
package app.shippy.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import app.shippy.data.library.R16LibraryPlaylistQuery
import app.shippy.data.library.R16LibrarySongQuery
import app.shippy.data.library.RoomR16LibraryReadRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PagingDaoTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .addCallback(R16LibraryMembershipTriggers)
                .addCallback(R16PlaylistSearchTriggers)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Library system collections and history load through bounded pages`() = runBlocking {
        insertRecording("recording-1", "Alpha")
        insertRecording("recording-2", "Beta")
        insertRecording("recording-3", "Transient")
        database.libraryDao().upsertRelationship(libraryRelationship("recording-1"))
        database.assetDao().upsert(asset("asset-local", "recording-1", "LOCAL_FILE"))
        database.assetDao().upsert(asset("asset-download", "recording-2", "SHIPPY_DOWNLOAD"))
        database.historyDao().save(history("session-1", "recording-1", 1))
        database.historyDao().save(history("session-2", "recording-2", 2))

        assertEquals(
            listOf("recording-1", "recording-2"),
            database.readModelDao().librarySongsPage().loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("recording-1"),
            database.readModelDao().likedSongsPage().loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("recording-1"),
            database.readModelDao().localSongsPage().loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("recording-2"),
            database.readModelDao().downloadedSongsPage().loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("session-2", "session-1"),
            database.historyDao().page().loadPage().map { it.listeningSessionId },
        )
    }

    @Test
    fun `system collection substring pages and playback share literal scoped membership`() =
        runBlocking {
            insertRecording("liked", "Underwater 100%_Mix")
            insertRecording("local", "UNDERWATER 100%_Mix")
            insertRecording("download", "Underwater 100%_Mix")
            insertRecording("outside", "Underwater 100%_Mix")
            insertRecording("unavailable", "Underwater 100%_Mix")
            insertRecording("wildcard", "Underwater 100XXMix")
            database.libraryDao().upsertRelationship(libraryRelationship("liked"))
            database.libraryDao().upsertRelationship(libraryRelationship("wildcard"))
            database.assetDao().upsert(asset("local-a", "local", "LOCAL_FILE"))
            database.assetDao().upsert(asset("local-b", "local", "LOCAL_FILE"))
            database.assetDao().upsert(asset("download-a", "download", "SHIPPY_DOWNLOAD"))
            database
                .assetDao()
                .upsert(asset("missing", "unavailable", "LOCAL_FILE").copy(assetState = "MISSING"))
            val repository = RoomR16LibraryReadRepository(database)
            val expected =
                mapOf(
                    R16SystemCollection.LIKED to "liked",
                    R16SystemCollection.LOCAL to "local",
                    R16SystemCollection.DOWNLOADS to "download",
                )
            for ((collection, recordingId) in expected) {
                for (query in listOf("rWaTeR 100%_", "%_", "tIsT")) {
                    val ids =
                        repository
                            .systemCollection(collection, R16LibrarySongQuery(query))
                            .loadPage()
                            .map { it.recordingId }
                    val expectedIds =
                        if (collection == R16SystemCollection.LIKED && query == "tIsT")
                            listOf("liked", "wildcard")
                        else listOf(recordingId)
                    assertEquals(expectedIds, ids)
                    assertEquals(
                        ids,
                        database
                            .readModelDao()
                            .filteredSystemCollectionRecordingIdsForPlayback(collection.id, query),
                    )
                }
            }
        }

    @Test
    fun `playlist paging and search preserve duplicate occurrences`() = runBlocking {
        insertRecording("recording-1", "Target Track")
        insertRecording("recording-2", "Other")
        database.libraryDao().upsertRelationship(libraryRelationship("recording-1"))
        database
            .playlistDao()
            .create(
                PlaylistEntity(
                    playlistId = "playlist-1",
                    name = "Mix",
                    pinned = false,
                    libraryOrderKey = 1,
                    artworkOverride = null,
                    displaySortMode = "CUSTOM",
                    displaySortDirection = "ASC",
                    originKind = "USER",
                    originKey = null,
                    createdAtEpochMs = 1,
                    updatedAtEpochMs = 1,
                ),
                listOf(
                    playlistEntry("entry-1", "recording-1", 1),
                    playlistEntry("entry-2", "recording-2", 2),
                    playlistEntry("entry-3", "recording-1", 3),
                ),
            )

        assertEquals(
            listOf("entry-1", "entry-2", "entry-3"),
            database.readModelDao().playlistPage("playlist-1").loadPage().map { it.playlistEntryId },
        )
        assertEquals(
            listOf("entry-1", "entry-3"),
            database.readModelDao().searchPlaylist("playlist-1", "Target*").loadPage().map {
                it.playlistEntryId
            },
        )
        val repository = RoomR16LibraryReadRepository(database)
        assertEquals(
            listOf("entry-1", "entry-2", "entry-3"),
            repository.playlistEntries("playlist-1").loadPage().map { it.playlistEntryId },
        )
        assertEquals(
            listOf("entry-1", "entry-3"),
            repository
                .playlistEntries("playlist-1", R16LibraryPlaylistQuery("Target"))
                .loadPage()
                .map { it.playlistEntryId },
        )
        assertTrue(
            repository
                .playlistEntries("playlist-1", R16LibraryPlaylistQuery("---"))
                .loadPage()
                .isEmpty()
        )
        assertEquals(
            listOf("entry-1", "entry-3"),
            repository
                .playlistEntries("playlist-1", R16LibraryPlaylistQuery("aRgEt"))
                .loadPage()
                .map { it.playlistEntryId },
        )
        assertTrue(
            repository
                .playlistEntries("playlist-1", R16LibraryPlaylistQuery("%"))
                .loadPage()
                .isEmpty()
        )
        assertTrue(
            database
                .readModelDao()
                .playlistEntriesForPlaybackSorted("playlist-1", "%", "CUSTOM", "ASC")
                .isEmpty()
        )
        assertEquals(
            listOf("recording-1"),
            database.readModelDao().searchLibrary("Target*").loadPage().map { it.recordingId },
        )
    }

    @Test
    fun `Library Songs repository keeps search contextual and paged`() = runBlocking {
        insertRecording("recording-1", "The River")
        insertRecording("recording-2", "River of Dreams")
        insertRecording("recording-3", "Unrelated")
        database.libraryDao().upsertRelationship(libraryRelationship("recording-1"))
        database.assetDao().upsert(asset("asset-2", "recording-2", "LOCAL_FILE"))

        val repository = RoomR16LibraryReadRepository(database)

        assertEquals(
            listOf("recording-2", "recording-1"),
            repository.songs().loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("recording-2", "recording-1"),
            repository.songs(R16LibrarySongQuery("river")).loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("recording-2"),
            repository.songs(R16LibrarySongQuery("river dreams")).loadPage().map { it.recordingId },
        )
    }

    @Test
    fun `Library Songs search removes FTS operators from user text`() {
        assertEquals("river* dreams*", R16LibrarySongQuery("river + dreams").ftsMatchOrNull())
        assertEquals(null, R16LibrarySongQuery("---").ftsMatchOrNull())
    }

    @Test
    fun `dedicated Library Search pages durable songs artists and playlist titles`() = runBlocking {
        insertRecording("recording-1", "Night Drive")
        insertRecording("recording-2", "Other Drive")
        insertRecording("recording-transient", "Night Transit")
        database.libraryDao().upsertRelationship(libraryRelationship("recording-1"))
        database.libraryDao().upsertRelationship(libraryRelationship("recording-2"))
        database
            .playlistDao()
            .create(
                PlaylistEntity(
                    playlistId = "playlist-1",
                    name = "Road Trips",
                    pinned = false,
                    libraryOrderKey = 1,
                    artworkOverride = null,
                    displaySortMode = "CUSTOM",
                    displaySortDirection = "ASC",
                    originKind = "USER",
                    originKey = null,
                    createdAtEpochMs = 1,
                    updatedAtEpochMs = 1,
                ),
                emptyList(),
            )
        val repository = RoomR16LibraryReadRepository(database)

        assertEquals(
            listOf("recording-1"),
            repository.songs(R16LibrarySongQuery("night")).loadPage().map { it.recordingId },
        )
        assertEquals(
            listOf("artist-1" to 2),
            repository
                .searchArtists(app.shippy.data.library.R16LibrarySearchQuery("artist"))
                .loadPage()
                .map { it.artistId to it.recordingCount },
        )
        assertEquals(
            listOf("playlist-1"),
            repository
                .searchPlaylists(app.shippy.data.library.R16LibrarySearchQuery("road"))
                .loadPage()
                .map { it.playlistId },
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE playlist SET name = 'Commute' WHERE playlist_id = 'playlist-1'"
        )
        assertTrue(
            repository
                .searchPlaylists(app.shippy.data.library.R16LibrarySearchQuery("road"))
                .loadPage()
                .isEmpty()
        )
        assertEquals(
            listOf("playlist-1"),
            repository
                .searchPlaylists(app.shippy.data.library.R16LibrarySearchQuery("commute"))
                .loadPage()
                .map { it.playlistId },
        )
    }

    private suspend fun insertRecording(recordingId: String, title: String) {
        val artist =
            ArtistEntity(
                artistId = "artist-1",
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

    private fun libraryRelationship(recordingId: String) =
        LibraryRecordingEntity(
            recordingId = recordingId,
            liked = true,
            explicitlySaved = false,
            userEdited = false,
            manuallyIdentified = false,
            firstAddedAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun asset(assetId: String, recordingId: String, kind: String) =
        MediaAssetEntity(
            assetId = assetId,
            recordingId = recordingId,
            sourceReferenceId = null,
            assetKind = kind,
            assetState = "AVAILABLE",
            locationType = "CONTENT_URI",
            location = "content://music/$assetId",
            documentId = null,
            mediaStoreId = null,
            displayName = "$assetId.flac",
            mimeType = "audio/flac",
            container = "flac",
            codec = "flac",
            bitrateBps = null,
            sampleRateHz = null,
            channelCount = null,
            contentLength = 1_000,
            contentChecksum = null,
            fingerprintId = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            lastVerifiedAtEpochMs = 1,
        )

    private fun history(sessionId: String, recordingId: String, startedAt: Long) =
        PlayHistoryEntity(
            listeningSessionId = sessionId,
            recordingId = recordingId,
            queueEntryId = "queue-$sessionId",
            sourceReferenceId = null,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + 1,
            activeListenedMs = 1,
            lastPositionMs = 1,
            completionKind = "STOPPED",
            chosenByUser = true,
        )

    private fun playlistEntry(playlistEntryId: String, recordingId: String, orderKey: Long) =
        PlaylistEntryEntity(
            playlistEntryId = playlistEntryId,
            playlistId = "playlist-1",
            recordingId = recordingId,
            orderKey = orderKey,
            addedAtEpochMs = 1,
        )

    private suspend fun <Value : Any> PagingSource<Int, Value>.loadPage(): List<Value> {
        val result =
            load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 50,
                    placeholdersEnabled = false,
                )
            )
        assertTrue(result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }
}
