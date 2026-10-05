/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeReadRepositoryTest.kt is part of Auxio.
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

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16HomeReadRepositoryTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recently played scans only finished sessions and dedupes newest canonical recordings`() =
        runBlocking {
            repeat(9) { index -> insertRecording("recording-${index + 1}", "Track ${index + 1}") }

            repeat(64) { index ->
                val recordingId = if (index < 8) "recording-${index + 1}" else "recording-1"
                database.historyDao().save(history("session-$index", recordingId, 70_000L - index))
            }
            // This recording is the 65th newest finished session and must not enter the scan.
            database.historyDao().save(history("session-64", "recording-9", 69_936L))
            database
                .historyDao()
                .save(history("session-active", "recording-9", 69_935L).copy(endedAtEpochMs = null))

            val recent = RoomR16HomeReadRepository(database).recentlyPlayed()

            assertEquals(
                (1..8).map { "recording-$it" },
                recent.map(R16HomeHistoryItem::recordingId),
            )
            assertEquals((1..8).map { "Track $it" }, recent.map(R16HomeHistoryItem::title))
            assertTrue(recent.all { it.endedAtEpochMs != null })
            assertEquals(
                recent,
                RoomR16HomeReadRepository(database).observeRecentlyPlayed().first(),
            )
        }

    @Test
    fun `recently played emits finished sessions and metadata changes without restarting collection`() =
        runBlocking {
            insertRecording("recording-1", "First")
            insertRecording("recording-2", "Second")
            database.historyDao().save(history("session-first", "recording-1", 10))
            val updates = Channel<List<R16HomeHistoryItem>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.Default) {
                    RoomR16HomeReadRepository(database).observeRecentlyPlayed().collect {
                        updates.send(it)
                    }
                }
            suspend fun awaitSessions(vararg ids: String): List<R16HomeHistoryItem> =
                withTimeout(5_000) {
                    var rows = updates.receive()
                    while (rows.map { it.listeningSessionId } != ids.toList()) {
                        rows = updates.receive()
                    }
                    rows
                }
            try {
                awaitSessions("session-first")
                val active =
                    history("session-second", "recording-2", 20).copy(endedAtEpochMs = null)
                database.historyDao().save(active)
                database.historyDao().save(active.copy(endedAtEpochMs = 21))
                awaitSessions("session-second", "session-first")

                // A repeat replaces the older occurrence rather than adding a duplicate card.
                database.historyDao().save(history("session-repeat", "recording-1", 30))
                awaitSessions("session-repeat", "session-second")
                insertRecording("recording-1", "Renamed")
                val renamed =
                    withTimeout(5_000) {
                        var rows = updates.receive()
                        while (rows.first().title != "Renamed") rows = updates.receive()
                        rows
                    }
                assertEquals(listOf("recording-1", "recording-2"), renamed.map { it.recordingId })
            } finally {
                collector.cancelAndJoin()
                updates.close()
            }
        }

    @Test
    fun `history paging joins canonical metadata and preserves session duplicates`() = runBlocking {
        insertRecording("recording-1", "Canonical Track")
        database.historyDao().save(history("session-new", "recording-1", 20))
        database.historyDao().save(history("session-old", "recording-1", 10))

        val page = RoomR16HomeReadRepository(database).history().loadPage()

        assertEquals(listOf("session-new", "session-old"), page.map { it.listeningSessionId })
        assertEquals(listOf("recording-1", "recording-1"), page.map { it.recordingId })
        assertEquals(listOf("Canonical Track", "Canonical Track"), page.map { it.title })
    }

    @Test
    fun `history paging computes correct scrobble status badges`() = runBlocking {
        insertRecording("recording-1", "Track 1")
        insertRecording("recording-2", "Track 2")
        insertRecording("recording-3", "Track 3")
        insertRecording("recording-4", "Track 4")

        // 1. Sent: regular finished session not in outbox
        database
            .historyDao()
            .save(
                history("session-sent", "recording-1", 40)
                    .copy(
                        scrobbleDisposition =
                            app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED.name
                    )
            )

        // 2. Pending: session in outbox with attempt_count = 0
        database
            .historyDao()
            .save(
                history("session-pending", "recording-2", 30)
                    .copy(
                        scrobbleDisposition =
                            app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED.name
                    )
            )
        database
            .lastFmOutboxDao()
            .enqueue(
                app.shippy.data.db.entity.LastFmScrobbleOutboxEntity(
                    outboxId = "outbox-1",
                    accountId = "acc-1",
                    listeningSessionId = "session-pending",
                    recordingId = "recording-2",
                    artist = "Artist",
                    track = "Track 2",
                    album = null,
                    durationSeconds = 120,
                    startedAtEpochSeconds = 30,
                    chosenByUser = true,
                    queuedAtEpochMs = 30_000,
                    attemptCount = 0,
                    lastAttemptAtEpochMs = null,
                )
            )

        // 3. Retryable Failure: session in outbox with attempt_count > 0
        database
            .historyDao()
            .save(
                history("session-failed", "recording-3", 20)
                    .copy(
                        scrobbleDisposition =
                            app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED.name
                    )
            )
        database
            .lastFmOutboxDao()
            .enqueue(
                app.shippy.data.db.entity.LastFmScrobbleOutboxEntity(
                    outboxId = "outbox-2",
                    accountId = "acc-1",
                    listeningSessionId = "session-failed",
                    recordingId = "recording-3",
                    artist = "Artist",
                    track = "Track 3",
                    album = null,
                    durationSeconds = 120,
                    startedAtEpochSeconds = 20,
                    chosenByUser = true,
                    queuedAtEpochMs = 20_000,
                    attemptCount = 2,
                    lastAttemptAtEpochMs = 21_000,
                )
            )

        // 4. Not Authorized: completion_kind = NOT_AUTHORIZED
        database
            .historyDao()
            .save(
                history("session-unauth", "recording-4", 10)
                    .copy(
                        completionKind = "NOT_AUTHORIZED",
                        scrobbleDisposition =
                            app.shippy.data.db.entity.ScrobbleDisposition.NOT_AUTHORIZED.name,
                    )
            )

        val page = RoomR16HomeReadRepository(database).history().loadPage()

        assertEquals(
            listOf("session-sent", "session-pending", "session-failed", "session-unauth"),
            page.map { it.listeningSessionId },
        )
        assertEquals(
            listOf("SENT", "PENDING", "RETRYABLE_FAILURE", "NOT_AUTHORIZED"),
            page.map { it.scrobbleStatus },
        )
    }

    @Test
    fun `pinned shortcuts use only valid pinned playlist layout order and stay bounded`() =
        runBlocking {
            (1..10).forEach { index ->
                val playlistId = playlistId(index)
                database
                    .legacyImportDao()
                    .upsertPlaylist(
                        PlaylistEntity(
                            playlistId = playlistId,
                            name = "Pinned $index",
                            pinned = false,
                            libraryOrderKey = Long.MAX_VALUE,
                            artworkOverride = null,
                            displaySortMode = "CUSTOM",
                            displaySortDirection = "ASC",
                            originKind = "TEST",
                            originKey = "pinned-$index",
                            createdAtEpochMs = 1,
                            updatedAtEpochMs = 1,
                        )
                    )
                database
                    .legacyImportDao()
                    .upsertLibraryLayout(
                        LibraryLayoutEntryEntity(
                            targetType = "PLAYLIST",
                            targetId = playlistId,
                            pinned = true,
                            orderKey = index.toLong(),
                        )
                    )
            }
            database
                .legacyImportDao()
                .upsertPlaylist(
                    PlaylistEntity(
                        playlistId = playlistId(11),
                        name = "Not pinned by layout",
                        pinned = true,
                        libraryOrderKey = 0,
                        artworkOverride = null,
                        displaySortMode = "CUSTOM",
                        displaySortDirection = "ASC",
                        originKind = "TEST",
                        originKey = "unpinned",
                        createdAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    )
                )
            database
                .legacyImportDao()
                .upsertLibraryLayout(
                    LibraryLayoutEntryEntity(
                        targetType = "PLAYLIST",
                        targetId = playlistId(11),
                        pinned = false,
                        orderKey = 0,
                    )
                )
            database
                .legacyImportDao()
                .upsertLibraryLayout(
                    LibraryLayoutEntryEntity(
                        targetType = "PLAYLIST",
                        targetId = playlistId(12),
                        pinned = true,
                        orderKey = -1,
                    )
                )

            val shortcuts = RoomR16HomeReadRepository(database).pinnedPlaylistShortcuts().first()

            assertEquals(
                (1..8).map(::playlistId),
                shortcuts.map(R16HomePinnedPlaylistShortcut::playlistId),
            )
            assertEquals(
                (1..8).map { "Pinned $it" },
                shortcuts.map(R16HomePinnedPlaylistShortcut::name),
            )
        }

    private suspend fun insertRecording(recordingId: String, title: String) {
        val artist =
            ArtistEntity(
                artistId = "artist-1",
                canonicalName = "Canonical Artist",
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

    private fun playlistId(index: Int): String =
        "20000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

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
