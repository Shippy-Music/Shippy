/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryMutationRepositoryTest.kt is part of Auxio.
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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.data.browser.RoomR16MediaBrowserRepository
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.R16PlaylistSearchTriggers
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import app.shippy.data.home.RoomR16HomeReadRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16LibraryMutationRepositoryTest {
    private lateinit var database: ShippyR16Database
    private var now = 100L
    private lateinit var repository: R16LibraryMutationRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .addCallback(R16LibraryMembershipTriggers)
                .addCallback(R16PlaylistSearchTriggers)
                .build()
        repository = RoomR16LibraryMutationRepository(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first save creates a canonical liked relation and membership index`() = runBlocking {
        insertRecording(RECORDING_ID)

        assertTrue(repository.saveToLiked(RecordingId(RECORDING_ID)))

        val relationship = checkNotNull(database.libraryDao().relationship(RECORDING_ID))
        assertTrue(relationship.liked)
        assertFalse(relationship.explicitlySaved)
        assertFalse(relationship.userEdited)
        assertFalse(relationship.manuallyIdentified)
        assertEquals(100L, relationship.firstAddedAtEpochMs)
        database.openHelper.writableDatabase
            .query(
                "SELECT COUNT(*) FROM library_membership_index WHERE recording_id = '$RECORDING_ID'"
            )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
    }

    @Test
    fun `repeated save preserves relationship metadata and observed like state`() = runBlocking {
        insertRecording(RECORDING_ID)
        database
            .libraryDao()
            .upsertRelationship(
                LibraryRecordingEntity(
                    recordingId = RECORDING_ID,
                    liked = false,
                    explicitlySaved = true,
                    userEdited = true,
                    manuallyIdentified = true,
                    firstAddedAtEpochMs = 10L,
                    updatedAtEpochMs = 20L,
                )
            )
        val recordingId = RecordingId(RECORDING_ID)

        assertFalse(repository.observeLike(recordingId).first())
        now = 30L
        assertTrue(repository.saveToLiked(recordingId))
        assertTrue(repository.observeLike(recordingId).first())
        now = 40L
        assertTrue(repository.saveToLiked(recordingId))

        assertEquals(
            LibraryRecordingEntity(
                recordingId = RECORDING_ID,
                liked = true,
                explicitlySaved = true,
                userEdited = true,
                manuallyIdentified = true,
                firstAddedAtEpochMs = 10L,
                updatedAtEpochMs = 30L,
            ),
            database.libraryDao().relationship(RECORDING_ID),
        )
    }

    @Test
    fun `playlist pin toggle changes the layout-backed summary and Home shortcut`() = runBlocking {
        val playlistId = PlaylistId("00000000-0000-4000-8000-000000000002")
        database.playlistDao().create(playlist(playlistId), emptyList())
        val browser = RoomR16MediaBrowserRepository(database)
        val home = RoomR16HomeReadRepository(database)

        assertFalse(
            repository.setPlaylistPinned(PlaylistId("00000000-0000-4000-8000-000000000003"), true)
        )
        assertTrue(repository.setPlaylistPinned(playlistId, true))
        val pinnedLayout =
            checkNotNull(database.libraryDao().layoutEntry("PLAYLIST", playlistId.value))
        assertTrue(pinnedLayout.pinned)
        assertEquals(
            listOf(playlistId),
            browser
                .playlistSummaries(R16MediaBrowserPageRequest(pageSize = 50))
                .items
                .filter { it.pinned }
                .map { it.playlistId },
        )
        assertEquals(
            listOf(playlistId.value),
            home.pinnedPlaylistShortcuts().first().map { it.playlistId },
        )

        assertTrue(repository.setPlaylistPinned(playlistId, false))
        assertEquals(
            pinnedLayout.copy(pinned = false),
            database.libraryDao().layoutEntry("PLAYLIST", playlistId.value),
        )
        assertFalse(
            browser
                .playlistSummaries(R16MediaBrowserPageRequest(pageSize = 50))
                .items
                .single()
                .pinned
        )
        assertTrue(home.pinnedPlaylistShortcuts().first().isEmpty())
    }

    private suspend fun insertRecording(recordingId: String) {
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
                        canonicalTitle = "Track",
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

    @Test
    fun `create uses a canonical UUID and one unpinned append layout row`() = runBlocking {
        val first = repository.createUserPlaylist("  Road trip  ")
        val firstId = assertCreated(first)
        now = 200L
        val secondId = assertCreated(repository.createUserPlaylist("Focus"))

        val firstPlaylist = checkNotNull(database.playlistDao().get(firstId.value))
        assertEquals("Road trip", firstPlaylist.name)
        assertEquals("USER", firstPlaylist.originKind)
        assertNull(firstPlaylist.originKey)
        assertFalse(firstPlaylist.pinned)
        assertEquals("CUSTOM", firstPlaylist.displaySortMode)
        assertEquals("ASC", firstPlaylist.displaySortDirection)
        assertEquals(100L, firstPlaylist.createdAtEpochMs)
        assertEquals(100L, firstPlaylist.updatedAtEpochMs)

        val layout = checkNotNull(database.libraryDao().layoutEntry("PLAYLIST", firstId.value))
        assertFalse(layout.pinned)
        assertEquals(firstPlaylist.libraryOrderKey, layout.orderKey)
        assertTrue(
            checkNotNull(database.libraryDao().layoutEntry("PLAYLIST", secondId.value)).orderKey >
                layout.orderKey
        )
        assertEquals(
            listOf(firstId.value, secondId.value),
            database.libraryDao().layoutEntries("PLAYLIST").map { it.targetId },
        )
    }

    @Test
    fun `blank create and rename names are rejected without writes`() = runBlocking {
        assertEquals(R16PlaylistLifecycleResult.InvalidName, repository.createUserPlaylist(" \t "))
        val playlistId = createUserPlaylist("Before")
        val before = checkNotNull(database.playlistDao().get(playlistId.value))

        assertEquals(
            R16PlaylistLifecycleResult.InvalidName,
            repository.renamePlaylist(playlistId, "  "),
        )
        assertEquals(before, database.playlistDao().get(playlistId.value))
    }

    @Test
    fun `rename preserves playlist identity entries layout and updates playlist FTS`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Before")
            val layout =
                checkNotNull(database.libraryDao().layoutEntry("PLAYLIST", playlistId.value))
            val entry =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000010",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1024L,
                    addedAtEpochMs = 90L,
                )
            database.playlistDao().insertEntries(listOf(entry))
            now = 200L

            assertEquals(
                R16PlaylistLifecycleResult.Updated,
                repository.renamePlaylist(playlistId, "  After  "),
            )
            val renamed = checkNotNull(database.playlistDao().get(playlistId.value))
            assertEquals("After", renamed.name)
            assertEquals(playlistId.value, renamed.playlistId)
            assertEquals(200L, renamed.updatedAtEpochMs)
            assertEquals(listOf(entry), database.playlistDao().entries(playlistId.value))
            assertEquals(layout, database.libraryDao().layoutEntry("PLAYLIST", playlistId.value))
            database.openHelper.writableDatabase
                .query("SELECT name FROM playlist_fts WHERE playlist_id = '${playlistId.value}'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("After", cursor.getString(0))
                }
        }

    @Test
    fun `delete removes only playlist layout and cascaded entries`() = runBlocking {
        insertRecording(RECORDING_ID)
        val playlistId = createUserPlaylist("Delete me")
        database
            .playlistDao()
            .insertEntries(
                listOf(
                    PlaylistEntryEntity(
                        playlistEntryId = "00000000-0000-4000-8000-000000000011",
                        playlistId = playlistId.value,
                        recordingId = RECORDING_ID,
                        orderKey = 1024L,
                        addedAtEpochMs = now,
                    )
                )
            )
        database
            .libraryDao()
            .upsertRelationship(
                LibraryRecordingEntity(
                    recordingId = RECORDING_ID,
                    liked = true,
                    explicitlySaved = true,
                    userEdited = false,
                    manuallyIdentified = false,
                    firstAddedAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            )
        database
            .downloadDao()
            .save(
                DownloadJobEntity(
                    jobId = "download-1",
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = null,
                    publishedAssetId = null,
                    state = "QUEUED",
                    bytesTransferred = 0,
                    expectedBytes = null,
                    failureKind = null,
                    retryAfterEpochMs = null,
                    pendingLocation = null,
                    displayFallbackJson = "{}",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            )

        assertEquals(R16PlaylistLifecycleResult.Deleted, repository.deletePlaylist(playlistId))
        assertNull(database.playlistDao().get(playlistId.value))
        assertTrue(database.playlistDao().entries(playlistId.value).isEmpty())
        assertNull(database.libraryDao().layoutEntry("PLAYLIST", playlistId.value))
        assertTrue(checkNotNull(database.libraryDao().relationship(RECORDING_ID)).liked)
        assertEquals("QUEUED", database.downloadDao().get("download-1")?.state)
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM playlist_fts WHERE playlist_id = '${playlistId.value}'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
    }

    @Test
    fun `rename and delete preserve imported provenance and reject missing playlist IDs`() =
        runBlocking {
            val deviceId = PlaylistId("00000000-0000-4000-8000-000000000020")
            val legacyId = PlaylistId("00000000-0000-4000-8000-000000000021")
            database.playlistDao().create(playlist(deviceId, "MUSIKR_DEVICE"), emptyList())
            database.playlistDao().create(playlist(legacyId, "LEGACY_SHIPPY"), emptyList())
            val missing = PlaylistId("00000000-0000-4000-8000-000000000022")

            assertEquals(
                R16PlaylistLifecycleResult.Updated,
                repository.renamePlaylist(deviceId, "Nope"),
            )
            assertEquals(R16PlaylistLifecycleResult.Deleted, repository.deletePlaylist(legacyId))
            assertEquals("MUSIKR_DEVICE", database.playlistDao().get(deviceId.value)?.originKind)
            assertNull(database.playlistDao().get(legacyId.value))
            assertEquals(R16PlaylistLifecycleResult.NotFound, repository.deletePlaylist(missing))
        }

    @Test
    fun `entry removal deletes one paired occurrence and preserves playlist recording and library state`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Duplicates")
            val otherPlaylistId = createUserPlaylist("Other")
            val first =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000030",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1024L,
                    addedAtEpochMs = now,
                )
            val second =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000031",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 2048L,
                    addedAtEpochMs = now,
                )
            val other =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000032",
                    playlistId = otherPlaylistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1024L,
                    addedAtEpochMs = now,
                )
            database.playlistDao().insertEntries(listOf(first, second, other))
            database
                .libraryDao()
                .upsertRelationship(
                    LibraryRecordingEntity(
                        recordingId = RECORDING_ID,
                        liked = true,
                        explicitlySaved = true,
                        userEdited = false,
                        manuallyIdentified = false,
                        firstAddedAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                )
            val playlistBefore = checkNotNull(database.playlistDao().get(playlistId.value))
            val browser = RoomR16MediaBrowserRepository(database)
            assertTrue(
                browser
                    .playlistEntries(playlistId, R16MediaBrowserPageRequest(pageSize = 1))
                    .hasNext
            )

            assertTrue(
                repository.removePlaylistEntry(playlistId, PlaylistEntryId(first.playlistEntryId))
            )
            assertFalse(
                repository.removePlaylistEntry(playlistId, PlaylistEntryId(first.playlistEntryId))
            )
            assertFalse(
                repository.removePlaylistEntry(playlistId, PlaylistEntryId(other.playlistEntryId))
            )
            assertFalse(
                repository.removePlaylistEntry(
                    PlaylistId("00000000-0000-4000-8000-000000000033"),
                    PlaylistEntryId(second.playlistEntryId),
                )
            )

            assertEquals(listOf(second), database.playlistDao().entries(playlistId.value))
            assertEquals(listOf(other), database.playlistDao().entries(otherPlaylistId.value))
            assertEquals(playlistBefore, database.playlistDao().get(playlistId.value))
            assertTrue(database.recordingDao().get(RECORDING_ID) != null)
            assertTrue(checkNotNull(database.libraryDao().relationship(RECORDING_ID)).liked)
            val page = browser.playlistEntries(playlistId, R16MediaBrowserPageRequest(pageSize = 1))
            assertEquals(
                listOf(second.playlistEntryId),
                page.items.map { it.playlistEntryId.value },
            )
            assertFalse(page.hasNext)
        }

    @Test
    fun `adding playlist occurrences appends duplicates without creating a library relationship`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Destination")
            val otherPlaylistId = createUserPlaylist("Other")
            val other =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000040",
                    playlistId = otherPlaylistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1024L,
                    addedAtEpochMs = now,
                )
            database.playlistDao().insertEntry(other)

            now = 200L
            val first =
                assertAdded(repository.addPlaylistEntry(playlistId, RecordingId(RECORDING_ID)))
            now = 300L
            val second =
                assertAdded(repository.addPlaylistEntry(playlistId, RecordingId(RECORDING_ID)))

            assertNotEquals(first, second)
            val entries = database.playlistDao().entries(playlistId.value)
            assertEquals(listOf(first.value, second.value), entries.map { it.playlistEntryId })
            assertEquals(listOf(RECORDING_ID, RECORDING_ID), entries.map { it.recordingId })
            assertTrue(entries[0].orderKey < entries[1].orderKey)
            assertEquals(200L, entries[0].addedAtEpochMs)
            assertEquals(300L, entries[1].addedAtEpochMs)
            assertEquals(listOf(other), database.playlistDao().entries(otherPlaylistId.value))
            assertNull(database.libraryDao().relationship(RECORDING_ID))
            assertTrue(hasLibraryMembership(RECORDING_ID))
        }

    @Test
    fun `playlist membership enters Library until its last duplicate occurrence is removed`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Transient destination")

            assertFalse(hasLibraryMembership(RECORDING_ID))
            val first =
                assertAdded(repository.addPlaylistEntry(playlistId, RecordingId(RECORDING_ID)))
            val second =
                assertAdded(repository.addPlaylistEntry(playlistId, RecordingId(RECORDING_ID)))

            assertNull(database.libraryDao().relationship(RECORDING_ID))
            assertTrue(hasLibraryMembership(RECORDING_ID))
            assertTrue(repository.removePlaylistEntry(playlistId, first))
            assertTrue(hasLibraryMembership(RECORDING_ID))
            assertTrue(repository.removePlaylistEntry(playlistId, second))
            assertFalse(hasLibraryMembership(RECORDING_ID))
        }

    @Test
    fun `adding requires exact playlist and recording without mutating either side`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Destination")
            val missingPlaylist = PlaylistId("00000000-0000-4000-8000-000000000050")
            val missingRecording = RecordingId("00000000-0000-4000-8000-000000000051")

            assertEquals(
                R16PlaylistEntryAddResult.PlaylistNotFound,
                repository.addPlaylistEntry(missingPlaylist, RecordingId(RECORDING_ID)),
            )
            assertEquals(
                R16PlaylistEntryAddResult.RecordingNotFound,
                repository.addPlaylistEntry(playlistId, missingRecording),
            )
            assertTrue(database.playlistDao().entries(playlistId.value).isEmpty())
            assertNull(database.libraryDao().relationship(RECORDING_ID))
        }

    @Test
    fun `adding rebalances only the selected playlist when its append key is exhausted`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Rebalance")
            val otherPlaylistId = createUserPlaylist("Other")
            val exhausted =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000060",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = Long.MAX_VALUE,
                    addedAtEpochMs = now,
                )
            val other =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000061",
                    playlistId = otherPlaylistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 777L,
                    addedAtEpochMs = now,
                )
            database.playlistDao().insertEntries(listOf(exhausted, other))

            val added =
                assertAdded(repository.addPlaylistEntry(playlistId, RecordingId(RECORDING_ID)))

            assertEquals(
                listOf(1024L, 2048L),
                database.playlistDao().entries(playlistId.value).map { it.orderKey },
            )
            assertEquals(
                added,
                PlaylistEntryId(database.playlistDao().entries(playlistId.value)[1].playlistEntryId),
            )
            assertEquals(listOf(other), database.playlistDao().entries(otherPlaylistId.value))
        }

    @Test
    fun `adjacent move reorders exact duplicate occurrences without touching other state`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Reorder")
            val otherPlaylistId = createUserPlaylist("Other")
            val first =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000070",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1_024L,
                    addedAtEpochMs = now,
                )
            val second =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000071",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 2_048L,
                    addedAtEpochMs = now,
                )
            val third =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000072",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 3_072L,
                    addedAtEpochMs = now,
                )
            val other =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000073",
                    playlistId = otherPlaylistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 777L,
                    addedAtEpochMs = now,
                )
            database.playlistDao().insertEntries(listOf(first, second, third, other))

            assertEquals(
                R16PlaylistEntryMoveResult.Moved,
                repository.movePlaylistEntry(
                    playlistId,
                    PlaylistEntryId(second.playlistEntryId),
                    R16PlaylistEntryMoveDirection.TOWARD_START,
                ),
            )
            assertEquals(
                listOf(second.playlistEntryId, first.playlistEntryId, third.playlistEntryId),
                database.playlistDao().entries(playlistId.value).map { it.playlistEntryId },
            )
            assertEquals(
                listOf(1_024L, 2_048L, 3_072L),
                database.playlistDao().entries(playlistId.value).map { it.orderKey },
            )
            assertEquals(
                R16PlaylistEntryMoveResult.Moved,
                repository.movePlaylistEntry(
                    playlistId,
                    PlaylistEntryId(second.playlistEntryId),
                    R16PlaylistEntryMoveDirection.TOWARD_END,
                ),
            )
            assertEquals(
                listOf(first.playlistEntryId, second.playlistEntryId, third.playlistEntryId),
                database.playlistDao().entries(playlistId.value).map { it.playlistEntryId },
            )
            assertEquals(listOf(other), database.playlistDao().entries(otherPlaylistId.value))
            assertTrue(database.recordingDao().get(RECORDING_ID) != null)
            assertNull(database.libraryDao().relationship(RECORDING_ID))
            assertTrue(hasLibraryMembership(RECORDING_ID))
        }

    @Test
    fun `batch move preserves relative order of moved entries under explicit anchor with duplicates`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("BatchMove")
            val e1 =
                PlaylistEntryEntity(
                    "00000000-0000-4000-8000-000000000081",
                    playlistId.value,
                    RECORDING_ID,
                    1000L,
                    now,
                )
            val e2 =
                PlaylistEntryEntity(
                    "00000000-0000-4000-8000-000000000082",
                    playlistId.value,
                    RECORDING_ID,
                    2000L,
                    now,
                )
            val e3 =
                PlaylistEntryEntity(
                    "00000000-0000-4000-8000-000000000083",
                    playlistId.value,
                    RECORDING_ID,
                    3000L,
                    now,
                )
            val e4 =
                PlaylistEntryEntity(
                    "00000000-0000-4000-8000-000000000084",
                    playlistId.value,
                    RECORDING_ID,
                    4000L,
                    now,
                )
            database.playlistDao().insertEntries(listOf(e1, e2, e3, e4))

            // Move e3 and e4 before e2 (order becomes e1, e3, e4, e2)
            val success =
                repository.movePlaylistEntries(
                    playlistId = playlistId,
                    entryIdsToMove =
                        listOf(
                            PlaylistEntryId(e3.playlistEntryId),
                            PlaylistEntryId(e4.playlistEntryId),
                        ),
                    targetAnchorEntryId = PlaylistEntryId(e2.playlistEntryId),
                    moveBefore = true,
                )
            assertTrue(success)
            assertEquals(
                listOf(
                    e1.playlistEntryId,
                    e3.playlistEntryId,
                    e4.playlistEntryId,
                    e2.playlistEntryId,
                ),
                database.playlistDao().entries(playlistId.value).map { it.playlistEntryId },
            )
        }

    @Test
    fun `adjacent move returns typed no ops for invalid position identity and sort`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val playlistId = createUserPlaylist("Boundary")
            val first =
                PlaylistEntryEntity(
                    playlistEntryId = "00000000-0000-4000-8000-000000000080",
                    playlistId = playlistId.value,
                    recordingId = RECORDING_ID,
                    orderKey = 1_024L,
                    addedAtEpochMs = now,
                )
            database.playlistDao().insertEntry(first)
            val before = database.playlistDao().entries(playlistId.value)

            assertEquals(
                R16PlaylistEntryMoveResult.AtBoundary,
                repository.movePlaylistEntry(
                    playlistId,
                    PlaylistEntryId(first.playlistEntryId),
                    R16PlaylistEntryMoveDirection.TOWARD_START,
                ),
            )
            assertEquals(
                R16PlaylistEntryMoveResult.NotFound,
                repository.movePlaylistEntry(
                    playlistId,
                    PlaylistEntryId("00000000-0000-4000-8000-000000000081"),
                    R16PlaylistEntryMoveDirection.TOWARD_END,
                ),
            )
            assertEquals(
                R16PlaylistEntryMoveResult.NotFound,
                repository.movePlaylistEntry(
                    PlaylistId("00000000-0000-4000-8000-000000000082"),
                    PlaylistEntryId(first.playlistEntryId),
                    R16PlaylistEntryMoveDirection.TOWARD_END,
                ),
            )
            assertEquals(before, database.playlistDao().entries(playlistId.value))

            val sortedPlaylistId = PlaylistId("00000000-0000-4000-8000-000000000083")
            val sortedEntry =
                first.copy(
                    playlistEntryId = "00000000-0000-4000-8000-000000000084",
                    playlistId = sortedPlaylistId.value,
                )
            database
                .playlistDao()
                .create(
                    playlist(sortedPlaylistId).copy(displaySortMode = "TITLE"),
                    listOf(sortedEntry),
                )
            assertEquals(
                R16PlaylistEntryMoveResult.NotCustomOrder,
                repository.movePlaylistEntry(
                    sortedPlaylistId,
                    PlaylistEntryId(sortedEntry.playlistEntryId),
                    R16PlaylistEntryMoveDirection.TOWARD_END,
                ),
            )
            assertEquals(
                listOf(sortedEntry),
                database.playlistDao().entries(sortedPlaylistId.value),
            )
        }

    private suspend fun createUserPlaylist(name: String): PlaylistId =
        assertCreated(repository.createUserPlaylist(name))

    private fun assertCreated(result: R16PlaylistLifecycleResult): PlaylistId {
        assertTrue(result is R16PlaylistLifecycleResult.Created)
        return (result as R16PlaylistLifecycleResult.Created).playlistId
    }

    private fun assertAdded(result: R16PlaylistEntryAddResult): PlaylistEntryId {
        assertTrue(result is R16PlaylistEntryAddResult.Added)
        return (result as R16PlaylistEntryAddResult.Added).playlistEntryId
    }

    private fun hasLibraryMembership(recordingId: String): Boolean =
        database.openHelper.writableDatabase
            .query(
                "SELECT COUNT(*) FROM library_membership_index WHERE recording_id = '$recordingId'"
            )
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0) == 1
            }

    private fun playlist(playlistId: PlaylistId, originKind: String = "TEST") =
        PlaylistEntity(
            playlistId = playlistId.value,
            name = "Pinned playlist",
            pinned = true,
            libraryOrderKey = 0,
            artworkOverride = null,
            displaySortMode = "CUSTOM",
            displaySortDirection = "ASC",
            originKind = originKind,
            originKey = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    @Test
    fun `searchIdentifyCandidates finds matching recording and computes isExact`() = runBlocking {
        insertRecording(RECORDING_ID)
        database.searchDao().refresh(RECORDING_ID)

        val results = repository.searchIdentifyCandidates("Track")
        assertEquals(1, results.size)
        assertEquals(RECORDING_ID, results[0].recordingId)
        assertEquals("Track", results[0].title)
        assertTrue(results[0].isExact)

        val partial = repository.searchIdentifyCandidates("Tra")
        assertEquals(1, partial.size)
        assertEquals(RECORDING_ID, partial[0].recordingId)
        assertFalse(partial[0].isExact)
    }

    @Test
    fun `confirmIdentifyCandidate and undoIdentifyCandidate manage source linkage and decisions`() =
        runBlocking {
            val initialRecordingId = "00000000-0000-4000-8000-000000000002"
            insertRecording(RECORDING_ID)
            insertRecording(initialRecordingId)
            val sourceRef =
                app.shippy.data.db.entity.SourceReferenceEntity(
                    sourceReferenceId = "source-1",
                    recordingId = initialRecordingId,
                    providerId = "LOCAL",
                    sourceKind = "FILE",
                    itemType = "TRACK",
                    sourceItemId = "item-1",
                    originalUrl = null,
                    availabilityState = "AVAILABLE",
                    availabilityCheckedAtEpochMs = 100,
                    availabilityExpiresAtEpochMs = null,
                    failureKind = null,
                    failureRetryable = null,
                    identityStatus = "PENDING",
                    rawMetadataObservationId = "obs-1",
                    createdAtEpochMs = 100,
                    updatedAtEpochMs = 100,
                )
            database.sourceDao().insertSource(sourceRef)

            val confirmed = repository.confirmIdentifyCandidate("source-1", RECORDING_ID)
            assertTrue(confirmed)

            val updatedSource = database.sourceDao().get("source-1")
            assertEquals(RECORDING_ID, updatedSource?.recordingId)

            val decisions = database.identityDao().decisionsFor("SOURCE", "source-1")
            assertEquals(1, decisions.size)
            assertTrue(decisions[0].userConfirmed)
            assertEquals(
                R16IdentifyUndo("source-1", initialRecordingId, RECORDING_ID),
                repository.latestIdentifyUndo("source-1"),
            )

            val undone = repository.undoLatestIdentifyCandidate("source-1")
            assertTrue(undone)

            val revertedSource = database.sourceDao().get("source-1")
            assertEquals(initialRecordingId, revertedSource?.recordingId)

            val revertedDecisions = database.identityDao().decisionsFor("SOURCE", "source-1")
            assertTrue(revertedDecisions.isEmpty())
        }

    @Test
    fun `updateMetadataOverride and revertMetadataOverride manage field overrides`() = runBlocking {
        insertRecording(RECORDING_ID)

        assertTrue(
            repository.updateMetadataOverride(RECORDING_ID, "TITLE", "{\"value\":\"Custom Title\"}")
        )
        assertTrue(
            repository.updateMetadataOverride(
                RECORDING_ID,
                "ARTIST",
                "{\"value\":\"Custom Artist\"}",
            )
        )

        val overrides = repository.getMetadataOverrides(RECORDING_ID)
        assertEquals(2, overrides.size)
        assertEquals("{\"value\":\"Custom Title\"}", overrides["TITLE"])
        assertEquals("{\"value\":\"Custom Artist\"}", overrides["ARTIST"])

        assertTrue(repository.revertMetadataOverride(RECORDING_ID, "TITLE"))

        val overridesAfter = repository.getMetadataOverrides(RECORDING_ID)
        assertEquals(1, overridesAfter.size)
        assertNull(overridesAfter["TITLE"])
        assertEquals("{\"value\":\"Custom Artist\"}", overridesAfter["ARTIST"])
    }

    @Test
    fun `merge then unmerge restores exact pre-merge graph duplicate playlist occurrences and redirect`() =
        runBlocking {
            val survivorId = RECORDING_ID
            val retiredId = "00000000-0000-4000-8000-000000000002"
            insertRecording(survivorId)
            insertRecording(retiredId)

            database
                .libraryDao()
                .upsertRelationship(
                    LibraryRecordingEntity(
                        recordingId = survivorId,
                        liked = false,
                        explicitlySaved = false,
                        userEdited = true,
                        manuallyIdentified = true,
                        firstAddedAtEpochMs = 50,
                        updatedAtEpochMs = 50,
                    )
                )
            repository.updateMetadataOverride(
                survivorId,
                "TITLE",
                "{\"value\":\"Survivor Custom Title\"}",
            )

            val sourceRef =
                app.shippy.data.db.entity.SourceReferenceEntity(
                    sourceReferenceId = "source-retired-1",
                    recordingId = retiredId,
                    providerId = "LOCAL",
                    sourceKind = "FILE",
                    itemType = "TRACK",
                    sourceItemId = "item-retired-1",
                    originalUrl = null,
                    availabilityState = "AVAILABLE",
                    availabilityCheckedAtEpochMs = 100,
                    availabilityExpiresAtEpochMs = null,
                    failureKind = null,
                    failureRetryable = null,
                    identityStatus = "MATCHED",
                    rawMetadataObservationId = "obs-1",
                    createdAtEpochMs = 100,
                    updatedAtEpochMs = 100,
                )
            database.sourceDao().insertSource(sourceRef)

            val asset =
                app.shippy.data.db.entity.MediaAssetEntity(
                    assetId = "asset-retired-1",
                    recordingId = retiredId,
                    sourceReferenceId = "source-retired-1",
                    assetKind = "LOCAL_FILE",
                    locationType = "FILE_PATH",
                    location = "/music/retired.flac",
                    documentId = null,
                    mediaStoreId = null,
                    displayName = "retired.flac",
                    mimeType = "audio/flac",
                    normalizedPathToken = "music/retired.flac",
                    contentChecksum = "checksum1",
                    fingerprintId = null,
                    container = "FLAC",
                    codec = "FLAC",
                    bitrateBps = 320000,
                    sampleRateHz = 44100,
                    channelCount = 2,
                    contentLength = 1000,
                    assetState = "AVAILABLE",
                    lastVerifiedAtEpochMs = 100,
                    createdAtEpochMs = 100,
                    updatedAtEpochMs = 100,
                )
            database.assetDao().upsert(asset)

            database
                .libraryDao()
                .upsertRelationship(
                    LibraryRecordingEntity(
                        recordingId = retiredId,
                        liked = true,
                        explicitlySaved = true,
                        userEdited = false,
                        manuallyIdentified = false,
                        firstAddedAtEpochMs = 100,
                        updatedAtEpochMs = 100,
                    )
                )

            val playlistId = createUserPlaylist("Merge Test Playlist")
            val entry1 =
                PlaylistEntryEntity(
                    playlistEntryId = "entry-dup-1",
                    playlistId = playlistId.value,
                    recordingId = retiredId,
                    orderKey = 1000L,
                    addedAtEpochMs = 100,
                )
            val entry2 =
                PlaylistEntryEntity(
                    playlistEntryId = "entry-dup-2",
                    playlistId = playlistId.value,
                    recordingId = retiredId,
                    orderKey = 2000L,
                    addedAtEpochMs = 100,
                )
            database.playlistDao().insertEntries(listOf(entry1, entry2))

            val extId =
                app.shippy.data.db.entity.ExternalIdentifierEntity(
                    externalIdentifierId = "ext-retired-1",
                    ownerType = "RECORDING",
                    ownerId = retiredId,
                    scheme = "isrc",
                    value = "USRC17607839",
                    verified = true,
                    sourceObservationId = null,
                    createdAtEpochMs = 100,
                )
            database.recordingDao().insertExternalIdentifiers(listOf(extId))

            val historyEntry =
                app.shippy.data.db.entity.PlayHistoryEntity(
                    listeningSessionId = "session-retired-1",
                    queueEntryId = "queue-1",
                    recordingId = retiredId,
                    sourceReferenceId = "source-retired-1",
                    startedAtEpochMs = 100,
                    endedAtEpochMs = 200,
                    activeListenedMs = 100000,
                    lastPositionMs = 100000,
                    completionKind = "FINISHED",
                    chosenByUser = false,
                    scrobbleDisposition = "NOT_AUTHORIZED",
                    snapshotTitle = "Track Title",
                    snapshotArtistDisplay = "Artist",
                    snapshotArtworkLocation = null,
                )
            database.historyDao().save(historyEntry)

            repository.updateMetadataOverride(
                retiredId,
                "TITLE",
                "{\"value\":\"Retired Custom Title\"}",
            )
            repository.updateMetadataOverride(
                retiredId,
                "RELEASE",
                "{\"value\":\"Retired Custom Release\"}",
            )

            assertTrue(repository.mergeRecordings(survivorId, retiredId))

            assertEquals(survivorId, database.identityDao().resolveRecordingId(retiredId))
            assertEquals(survivorId, database.sourceDao().get("source-retired-1")?.recordingId)
            assertEquals(survivorId, database.assetDao().get("asset-retired-1")?.recordingId)
            assertEquals(
                listOf(survivorId, survivorId),
                database.playlistDao().entries(playlistId.value).map { it.recordingId },
            )
            assertEquals(
                listOf("entry-dup-1", "entry-dup-2"),
                database.playlistDao().entries(playlistId.value).map { it.playlistEntryId },
            )
            assertEquals(
                survivorId,
                database
                    .recordingDao()
                    .externalIdentifiers(survivorId)
                    .firstOrNull { it.externalIdentifierId == "ext-retired-1" }
                    ?.ownerId,
            )
            assertEquals(
                survivorId,
                database
                    .historyDao()
                    .forRecording(survivorId, 10)
                    .firstOrNull { it.listeningSessionId == "session-retired-1" }
                    ?.recordingId,
            )
            assertEquals(
                "{\"value\":\"Survivor Custom Title\"}",
                repository.getMetadataOverrides(survivorId)["TITLE"],
            )
            assertEquals(
                "{\"value\":\"Retired Custom Release\"}",
                repository.getMetadataOverrides(survivorId)["RELEASE"],
            )
            val mergedSurvivorRelationship = database.libraryDao().relationship(survivorId)
            assertTrue(mergedSurvivorRelationship?.liked == true)
            assertTrue(mergedSurvivorRelationship?.explicitlySaved == true)
            assertTrue(mergedSurvivorRelationship?.userEdited == true)
            assertTrue(mergedSurvivorRelationship?.manuallyIdentified == true)

            assertTrue(repository.unmergeRecording(survivorId))

            assertEquals(retiredId, database.identityDao().resolveRecordingId(retiredId))
            assertTrue(database.recordingDao().get(retiredId) != null)
            assertEquals(retiredId, database.sourceDao().get("source-retired-1")?.recordingId)
            assertEquals(retiredId, database.assetDao().get("asset-retired-1")?.recordingId)
            val restoredRel = database.libraryDao().relationship(retiredId)
            assertTrue(restoredRel?.liked == true)
            assertTrue(restoredRel?.explicitlySaved == true)
            val restoredSurvivorRel = database.libraryDao().relationship(survivorId)
            assertTrue(restoredSurvivorRel?.liked == false)
            assertTrue(restoredSurvivorRel?.explicitlySaved == false)
            assertTrue(restoredSurvivorRel?.userEdited == true)
            assertTrue(restoredSurvivorRel?.manuallyIdentified == true)

            val restoredEntries = database.playlistDao().entries(playlistId.value)
            assertEquals(2, restoredEntries.size)
            assertEquals(
                listOf("entry-dup-1", "entry-dup-2"),
                restoredEntries.map { it.playlistEntryId },
            )
            assertEquals(listOf(retiredId, retiredId), restoredEntries.map { it.recordingId })

            val restoredExt = database.recordingDao().externalIdentifiers(retiredId)
            assertEquals(1, restoredExt.size)
            assertEquals("ext-retired-1", restoredExt[0].externalIdentifierId)
            assertEquals(retiredId, restoredExt[0].ownerId)

            val restoredHistory = database.historyDao().forRecording(retiredId, 10)
            assertEquals(1, restoredHistory.size)
            assertEquals("session-retired-1", restoredHistory[0].listeningSessionId)

            val retiredOverrides = repository.getMetadataOverrides(retiredId)
            assertEquals("{\"value\":\"Retired Custom Title\"}", retiredOverrides["TITLE"])
            assertEquals("{\"value\":\"Retired Custom Release\"}", retiredOverrides["RELEASE"])

            val survivorOverrides = repository.getMetadataOverrides(survivorId)
            assertEquals("{\"value\":\"Survivor Custom Title\"}", survivorOverrides["TITLE"])
            assertNull(survivorOverrides["RELEASE"])
        }

    @Test
    fun `incomplete or corrupt audit produces no partial mutation and returns false`() =
        runBlocking {
            insertRecording(RECORDING_ID)
            val secondRecordingId = "00000000-0000-4000-8000-000000000002"
            insertRecording(secondRecordingId)

            val audit =
                app.shippy.data.db.entity.MergeAuditEntity(
                    mergeAuditId = "audit-corrupt-1",
                    survivorRecordingId = RECORDING_ID,
                    mergedRecordingId = secondRecordingId,
                    snapshotJson = "{}",
                    userConfirmed = true,
                    createdAtEpochMs = 100,
                    reversedAtEpochMs = null,
                )
            val redirect =
                app.shippy.data.db.entity.EntityRedirectEntity(
                    oldRecordingId = secondRecordingId,
                    canonicalRecordingId = RECORDING_ID,
                    mergeAuditId = "audit-corrupt-1",
                    createdAtEpochMs = 100,
                )
            assertTrue(database.identityDao().recordRedirect(redirect, audit))

            assertFalse(repository.unmergeRecording(RECORDING_ID))

            assertEquals(RECORDING_ID, database.identityDao().resolveRecordingId(secondRecordingId))
            val auditInDb = database.identityDao().mergeAudit("audit-corrupt-1")
            assertNull(auditInDb?.reversedAtEpochMs)
        }

    @Test
    fun `unmerge fails closed when a moved reference no longer belongs to the survivor`() =
        runBlocking {
            val retiredId = "00000000-0000-4000-8000-000000000002"
            val thirdRecordingId = "00000000-0000-4000-8000-000000000003"
            insertRecording(RECORDING_ID)
            insertRecording(retiredId)
            insertRecording(thirdRecordingId)
            database
                .sourceDao()
                .insertSource(
                    app.shippy.data.db.entity.SourceReferenceEntity(
                        sourceReferenceId = "source-preflight-1",
                        recordingId = retiredId,
                        providerId = "LOCAL",
                        sourceKind = "FILE",
                        itemType = "TRACK",
                        sourceItemId = "preflight-item-1",
                        originalUrl = null,
                        availabilityState = "AVAILABLE",
                        availabilityCheckedAtEpochMs = 100,
                        availabilityExpiresAtEpochMs = null,
                        failureKind = null,
                        failureRetryable = null,
                        identityStatus = "MATCHED",
                        rawMetadataObservationId = "obs-preflight-1",
                        createdAtEpochMs = 100,
                        updatedAtEpochMs = 100,
                    )
                )

            assertTrue(repository.mergeRecordings(RECORDING_ID, retiredId))
            assertEquals(
                1,
                database.sourceDao().reassignRecordingId("source-preflight-1", thirdRecordingId),
            )

            assertFalse(repository.unmergeRecording(RECORDING_ID))
            assertNull(database.recordingDao().get(retiredId))
            assertEquals(RECORDING_ID, database.identityDao().resolveRecordingId(retiredId))
        }

    private companion object {
        const val RECORDING_ID = "00000000-0000-4000-8000-000000000001"
    }
}
