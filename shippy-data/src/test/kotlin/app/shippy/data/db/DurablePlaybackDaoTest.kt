/*
 * Copyright (c) 2026 Auxio Project
 * DurablePlaybackDaoTest.kt is part of Auxio.
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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.EntityRedirectEntity
import app.shippy.data.db.entity.IdentityDecisionEntity
import app.shippy.data.db.entity.IdentityRejectionEntity
import app.shippy.data.db.entity.MergeAuditEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DurablePlaybackDaoTest {
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
    fun `redirect ledger is idempotent acyclic and reversible`() = runBlocking {
        insertRecording("recording-1")
        insertRecording("recording-2")
        val redirect = redirect("recording-2", "recording-1", "merge-1")
        val audit = mergeAudit("merge-1", "recording-2", "recording-1")

        assertTrue(database.identityDao().recordRedirect(redirect, audit))
        assertFalse(database.identityDao().recordRedirect(redirect, audit))
        assertEquals("recording-1", database.identityDao().resolveRecordingId("recording-2"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database
                    .identityDao()
                    .recordRedirect(
                        redirect("recording-1", "recording-2", "merge-cycle"),
                        mergeAudit("merge-cycle", "recording-1", "recording-2"),
                    )
            }
        }

        database
            .identityDao()
            .insertDecision(
                IdentityDecisionEntity(
                    decisionId = "decision-1",
                    subjectType = "ASSET",
                    subjectId = "asset-1",
                    targetRecordingId = "recording-1",
                    decisionKind = "USER_CONFIRMED",
                    confidence = 1.0,
                    evidenceJson = "{}",
                    userConfirmed = true,
                    createdAtEpochMs = 2,
                )
            )
        database
            .identityDao()
            .upsertRejection(
                IdentityRejectionEntity(
                    subjectType = "ASSET",
                    subjectId = "asset-1",
                    rejectedRecordingId = "recording-2",
                    reason = "Different version",
                    createdAtEpochMs = 3,
                )
            )
        assertEquals(1, database.identityDao().decisionsFor("ASSET", "asset-1").size)
        assertEquals(1, database.identityDao().rejectionsFor("ASSET", "asset-1").size)

        assertTrue(database.identityDao().reverseRedirect("merge-1", 4))
        assertFalse(database.identityDao().reverseRedirect("merge-1", 5))
        assertEquals("recording-2", database.identityDao().resolveRecordingId("recording-2"))
        assertEquals(4L, database.identityDao().mergeAudit("merge-1")?.reversedAtEpochMs)
    }

    @Test
    fun `checkpoint replacement preserves duplicate recordings by occurrence`() = runBlocking {
        val first = checkpointEntry("queue-1", 0)
        val second = checkpointEntry("queue-2", 1)
        database.playbackCheckpointDao().replace(checkpoint("queue-2"), listOf(first, second))

        val stored = checkNotNull(database.playbackCheckpointDao().load(ACTIVE_SLOT))
        assertEquals(listOf("queue-1", "queue-2"), stored.entries.map { it.queueEntryId })
        assertEquals(listOf("recording-1", "recording-1"), stored.entries.map { it.recordingId })

        database
            .playbackCheckpointDao()
            .replace(checkpoint("queue-1"), listOf(first.copy(presentationFallbackJson = "{new}")))
        assertEquals(
            listOf("queue-1"),
            database.playbackCheckpointDao().load(ACTIVE_SLOT)?.entries?.map { it.queueEntryId },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.playbackCheckpointDao().replace(checkpoint("missing"), listOf(first))
            }
        }
        assertTrue(database.playbackCheckpointDao().clear(ACTIVE_SLOT))
        assertNull(database.playbackCheckpointDao().load(ACTIVE_SLOT))
    }

    @Test
    fun `local history stores monotonic session totals independently`() = runBlocking {
        insertRecording("recording-1")
        database.historyDao().save(history("session-1", 1, 10_000))
        database.historyDao().save(history("session-2", 2, 20_000))

        assertEquals(
            listOf("session-2", "session-1"),
            database.historyDao().recent(10).map { it.listeningSessionId },
        )
        assertEquals(30_000, database.historyDao().totalActiveListenedMs("recording-1"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { database.historyDao().save(history("invalid", 3, -1)) }
        }
        Unit
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

    private fun redirect(oldId: String, canonicalId: String, auditId: String) =
        EntityRedirectEntity(
            oldRecordingId = oldId,
            canonicalRecordingId = canonicalId,
            mergeAuditId = auditId,
            createdAtEpochMs = 2,
        )

    private fun mergeAudit(auditId: String, mergedId: String, survivorId: String) =
        MergeAuditEntity(
            mergeAuditId = auditId,
            survivorRecordingId = survivorId,
            mergedRecordingId = mergedId,
            snapshotJson = "{}",
            userConfirmed = true,
            createdAtEpochMs = 2,
            reversedAtEpochMs = null,
        )

    private fun checkpoint(currentQueueEntryId: String) =
        PlaybackCheckpointEntity(
            slot = ACTIVE_SLOT,
            checkpointVersion = 1,
            sessionId = "playback-session-1",
            currentQueueEntryId = currentQueueEntryId,
            positionMs = 123,
            playingIntent = true,
            repeatMode = "OFF",
            shuffleEnabled = false,
            shuffleSeed = null,
            baseOrderJson = "[]",
            traversalOrderJson = "[]",
            updatedAtEpochMs = 1,
            checksum = "checksum",
        )

    private fun checkpointEntry(queueEntryId: String, position: Int) =
        PlaybackCheckpointEntryEntity(
            slot = ACTIVE_SLOT,
            queueEntryId = queueEntryId,
            position = position,
            recordingId = "recording-1",
            originJson = null,
            contributorId = null,
            presentationFallbackJson = "{}",
        )

    private fun history(sessionId: String, startedAt: Long, activeMs: Long) =
        PlayHistoryEntity(
            listeningSessionId = sessionId,
            recordingId = "recording-1",
            queueEntryId = "queue-$sessionId",
            sourceReferenceId = null,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + activeMs,
            activeListenedMs = activeMs,
            lastPositionMs = activeMs,
            completionKind = "STOPPED",
            chosenByUser = true,
        )

    private companion object {
        const val ACTIVE_SLOT = "active"
    }
}
