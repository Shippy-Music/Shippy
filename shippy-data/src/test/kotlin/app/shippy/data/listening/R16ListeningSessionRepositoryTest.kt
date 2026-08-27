/*
 * Copyright (c) 2026 Auxio Project
 * R16ListeningSessionRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.listening

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16ListeningSessionRepositoryTest {
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
    fun `repeating a session persists one history row and one outbox row`() = runBlocking {
        insertRecording()
        val repository =
            RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }
        val session = session()

        assertTrue(repository.persist(session).scrobbleQueued)
        assertFalse(repository.persist(session).scrobbleQueued)
        assertFalse(
            repository
                .persist(session(ANOTHER_SESSION_ID, activeListenedMs = 59_999))
                .scrobbleQueued
        )

        assertEquals(2, database.historyDao().recent(10).size)
        assertEquals(1, database.lastFmOutboxDao().count())
        val outbox = database.lastFmOutboxDao().oldest(1).single()
        assertEquals(session.sessionId.value, outbox.outboxId)
        assertEquals("Canonical Artist", outbox.artist)
        assertEquals("Canonical Track", outbox.track)
    }

    @Test
    fun `invalid or filename-derived metadata records history without enqueueing`() = runBlocking {
        insertRecording(durationMs = null)
        val repository =
            RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }

        assertFalse(repository.persist(session()).scrobbleQueued)
        assertEquals(1, database.historyDao().recent(10).size)
        assertEquals(0, database.lastFmOutboxDao().count())

        insertRecording(durationMs = -1)
        assertFalse(repository.persist(session(ANOTHER_SESSION_ID)).scrobbleQueued)
        assertEquals(2, database.historyDao().recent(10).size)
        assertEquals(0, database.lastFmOutboxDao().count())

        insertRecording()
        database
            .legacyImportDao()
            .upsertProvenance(
                listOf(
                    CanonicalFieldProvenanceEntity(
                        recordingId = RECORDING_ID,
                        fieldName = "title",
                        selectedSourceType = "FILENAME",
                        selectedSourceId = null,
                        confidence = 1.0,
                        selectedAtEpochMs = 1,
                    )
                )
            )

        val result = repository.persist(session(THIRD_SESSION_ID))

        assertFalse(result.scrobbleQueued)
        assertEquals(3, database.historyDao().recent(10).size)
        assertEquals(0, database.lastFmOutboxDao().count())
    }

    @Test
    fun `authorized eligible listen produces history ENQUEUED and outbox row, UI status is PENDING then SENT when outbox removed`() =
        runBlocking {
            insertRecording()
            val repository =
                RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }
            val session = session()

            assertTrue(repository.persist(session).scrobbleQueued)

            // When outbox exists with attempt 0, UI scrobble_status is PENDING
            val recentPending = database.historyDao().recentFinishedWithPresentation(1).single()
            assertEquals("PENDING", recentPending.scrobbleStatus)

            // Simulate successful Last.fm worker delivery: outbox row is deleted
            database.lastFmOutboxDao().deleteAccepted(setOf(session.sessionId.value))

            // When outbox is deleted, history row retains ENQUEUED and UI scrobble_status becomes
            // SENT
            val recentSent = database.historyDao().recentFinishedWithPresentation(1).single()
            assertEquals("SENT", recentSent.scrobbleStatus)
        }

    @Test
    fun `disconnected listen produces history NOT_AUTHORIZED and zero outbox, UI status never says SENT`() =
        runBlocking {
            insertRecording()
            val repository =
                RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }
            val unauthorizedSession = session().copy(scrobbleAuthorized = false)

            assertFalse(repository.persist(unauthorizedSession).scrobbleQueued)
            assertEquals(0, database.lastFmOutboxDao().count())

            val recent = database.historyDao().recentFinishedWithPresentation(1).single()
            assertEquals("NOT_AUTHORIZED", recent.scrobbleStatus)
        }

    @Test
    fun `legacy migrated history with LEGACY_UNKNOWN never fabricates SENT`() = runBlocking {
        insertRecording()
        database
            .historyDao()
            .save(
                app.shippy.data.db.entity.PlayHistoryEntity(
                    listeningSessionId = "legacy-session-1",
                    recordingId = RECORDING_ID,
                    queueEntryId = QUEUE_ENTRY_ID,
                    sourceReferenceId = null,
                    startedAtEpochMs = 1_000,
                    endedAtEpochMs = 121_000,
                    activeListenedMs = 60_000,
                    lastPositionMs = 60_000,
                    completionKind = "NATURAL_END",
                    chosenByUser = true,
                    snapshotTitle = "Legacy Title",
                    snapshotArtistDisplay = "Legacy Artist",
                    snapshotArtworkLocation = null,
                    scrobbleDisposition =
                        app.shippy.data.db.entity.ScrobbleDisposition.LEGACY_UNKNOWN.name,
                )
            )

        val recent = database.historyDao().recentFinishedWithPresentation(1).single()
        assertEquals("LEGACY_UNKNOWN", recent.scrobbleStatus)
    }

    @Test
    fun `transient non-Library Recording resolves canonical presentation and creates eligible outbox`() =
        runBlocking {
            // Insert transient recording (not added to library_recording)
            insertRecording(durationMs = 180_000)
            val repository =
                RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }
            val session = session(activeListenedMs = 90_000)

            assertTrue(repository.persist(session).scrobbleQueued)

            val outbox = database.lastFmOutboxDao().oldest(1).single()
            assertEquals("Canonical Artist", outbox.artist)
            assertEquals("Canonical Track", outbox.track)
            assertEquals(180L, outbox.durationSeconds)

            val recent = database.historyDao().recentFinishedWithPresentation(1).single()
            assertEquals("Canonical Track", recent.title)
            assertEquals("Canonical Artist", recent.artist)
            assertEquals("PENDING", recent.scrobbleStatus)
        }

    private suspend fun insertRecording(durationMs: Long? = 120_000) {
        val artist =
            ArtistEntity(
                artistId = ARTIST_ID,
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
                        recordingId = RECORDING_ID,
                        canonicalTitle = "Canonical Track",
                        durationMs = durationMs,
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
                            recordingId = RECORDING_ID,
                            position = 0,
                            artistId = ARTIST_ID,
                            creditedName = "Canonical Artist",
                            joinPhrase = "",
                        )
                    ),
            )
    }

    @Test
    fun `active listening checkpoint crossing threshold creates no outbox row until session is finalized`() =
        runBlocking {
            insertRecording()
            val repository =
                RoomR16ListeningSessionRepository(database) { Instant.ofEpochMilli(10_000) }

            // Active checkpoint crossing threshold (endedAt == null)
            val activeSession = session(endedAt = null)
            val checkpointResult = repository.persist(activeSession)

            assertFalse(checkpointResult.scrobbleQueued)
            assertFalse(repository.persist(activeSession).scrobbleQueued)
            assertEquals(1, database.historyDao().recent(10).size)
            assertEquals(0, database.lastFmOutboxDao().count())

            val activeHistory = database.historyDao().recent(1).single()
            assertEquals("ACTIVE_CHECKPOINT", activeHistory.scrobbleDisposition)

            // Finalized session (endedAt != null)
            val finalizedSession = session(endedAt = Instant.ofEpochMilli(121_000))
            val finalizedResult = repository.persist(finalizedSession)

            assertTrue(finalizedResult.scrobbleQueued)
            assertEquals(1, database.historyDao().recent(10).size)
            assertEquals(1, database.lastFmOutboxDao().count())

            val finalizedHistory = database.historyDao().recent(1).single()
            assertEquals("ENQUEUED", finalizedHistory.scrobbleDisposition)

            // Repeating finalized session remains idempotent while outbox row exists
            val repeatedResult = repository.persist(finalizedSession)
            assertFalse(repeatedResult.scrobbleQueued)
            assertEquals(1, database.historyDao().recent(10).size)
            assertEquals(1, database.lastFmOutboxDao().count())

            database.lastFmOutboxDao().deleteAccepted(setOf(finalizedSession.sessionId.value))
            assertFalse(repository.persist(finalizedSession).scrobbleQueued)
            assertEquals(0, database.lastFmOutboxDao().count())
        }

    private fun session(
        sessionId: String = SESSION_ID,
        activeListenedMs: Long = 60_000,
        endedAt: Instant? = Instant.ofEpochMilli(121_000),
    ) =
        R16ListeningSessionRecord(
            sessionId = ListeningSessionId(sessionId),
            recordingId = RecordingId(RECORDING_ID),
            queueEntryId = QueueEntryId(QUEUE_ENTRY_ID),
            sourceReferenceId = null,
            startedAt = Instant.ofEpochMilli(1_000),
            endedAt = endedAt,
            activeListenedMs = activeListenedMs,
            lastPositionMs = 60_000,
            completionKind = "THRESHOLD",
            chosenByUser = true,
        )

    private companion object {
        const val ARTIST_ID = "00000000-0000-0000-0000-000000000001"
        const val RECORDING_ID = "00000000-0000-0000-0000-000000000002"
        const val QUEUE_ENTRY_ID = "00000000-0000-0000-0000-000000000003"
        const val SESSION_ID = "00000000-0000-0000-0000-000000000004"
        const val ANOTHER_SESSION_ID = "00000000-0000-0000-0000-000000000005"
        const val THIRD_SESSION_ID = "00000000-0000-0000-0000-000000000006"
    }
}
