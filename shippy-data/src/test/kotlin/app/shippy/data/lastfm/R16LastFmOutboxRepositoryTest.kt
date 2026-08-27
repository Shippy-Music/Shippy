/*
 * Copyright (c) 2026 Auxio Project
 * R16LastFmOutboxRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.lastfm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16LastFmOutboxRepositoryTest {
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
    fun `oldest maps neutral entries in deterministic FIFO and caps a batch at fifty`() =
        runBlocking {
            insertRecording()
            repeat(51) { index ->
                assertTrue(database.lastFmOutboxDao().enqueue(outbox(index)) > 0)
            }

            val entries = RoomR16LastFmOutboxRepository(database).oldest(500)

            assertEquals(R16LastFmOutboxRepository.MAX_BATCH_SIZE, entries.size)
            assertEquals("outbox-000", entries.first().outboxId)
            assertEquals("outbox-049", entries.last().outboxId)
            assertEquals("session-000", entries.first().listeningSessionId)
            assertEquals(RECORDING_ID, entries.first().recordingId)
            assertEquals("Artist", entries.first().artist)
            assertEquals("Track 0", entries.first().track)
            assertEquals("Album", entries.first().album)
            assertEquals(180L, entries.first().durationSeconds)
            assertEquals(10L, entries.first().startedAtEpochSeconds)
            assertTrue(entries.first().chosenByUser)
            assertEquals(1_000L, entries.first().queuedAtEpochMs)
            assertEquals(0, entries.first().attemptCount)
            assertEquals(null, entries.first().lastAttemptAtEpochMs)
        }

    @Test
    fun `attempts and accepted deletion operate on durable rows`() = runBlocking {
        insertRecording()
        database.lastFmOutboxDao().enqueue(outbox(0))
        database.lastFmOutboxDao().enqueue(outbox(1))
        val repository = RoomR16LastFmOutboxRepository(database)

        assertTrue(repository.recordAttempt("outbox-000", 2_000L))
        assertFalse(repository.recordAttempt("missing", 2_000L))
        val attempted = repository.oldest(1).single()
        assertEquals(1, attempted.attemptCount)
        assertEquals(2_000L, attempted.lastAttemptAtEpochMs)

        assertEquals(1, repository.deleteAccepted(setOf("outbox-000")))
        assertEquals(1, repository.count())
        assertEquals(0, repository.deleteAccepted(emptySet()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.deleteAccepted(
                    (0..R16LastFmOutboxRepository.MAX_BATCH_SIZE).map { "outbox-$it" }.toSet()
                )
            }
        }
        Unit
    }

    private suspend fun insertRecording() {
        CanonicalWriteTransactions(database)
            .upsertRecordingGraph(
                recording =
                    RecordingEntity(
                        recordingId = RECORDING_ID,
                        canonicalTitle = "Track 0",
                        durationMs = 180_000,
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
                artists =
                    listOf(
                        ArtistEntity(
                            artistId = ARTIST_ID,
                            canonicalName = "Artist",
                            sortName = null,
                            disambiguation = null,
                            createdAtEpochMs = 1,
                            updatedAtEpochMs = 1,
                        )
                    ),
                credits =
                    listOf(
                        RecordingArtistCreditEntity(
                            recordingId = RECORDING_ID,
                            position = 0,
                            artistId = ARTIST_ID,
                            creditedName = "Artist",
                            joinPhrase = "",
                        )
                    ),
            )
    }

    private fun outbox(index: Int) =
        LastFmScrobbleOutboxEntity(
            outboxId = "outbox-${index.toString().padStart(3, '0')}",
            listeningSessionId = "session-${index.toString().padStart(3, '0')}",
            recordingId = RECORDING_ID,
            artist = "Artist",
            track = "Track $index",
            album = "Album",
            durationSeconds = 180,
            startedAtEpochSeconds = 10,
            chosenByUser = true,
            queuedAtEpochMs = 1_000,
            attemptCount = 0,
            lastAttemptAtEpochMs = null,
        )

    private companion object {
        const val ARTIST_ID = "00000000-0000-0000-0000-000000000001"
        const val RECORDING_ID = "00000000-0000-0000-0000-000000000002"
    }
}
