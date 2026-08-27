/*
 * Copyright (c) 2026 Auxio Project
 * R16ListeningSessionDeliveryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.listening.ActiveListeningSessionCheckpoint
import app.shippy.core.listening.FinalizedListeningSession
import app.shippy.core.listening.ListeningSessionCompletionReason
import app.shippy.data.listening.R16ListeningSessionRecord
import app.shippy.data.listening.R16ListeningSessionRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class R16ListeningSessionDeliveryTest {
    @Test
    fun `final supersedes a pending checkpoint and close and drain preserves it`() = runBlocking {
        val repository = BlockingListeningSessionRepository()
        var scheduled = 0
        val delivery = R16ListeningSessionDelivery(this, repository) { scheduled++ }
        val checkpoint =
            ActiveListeningSessionCheckpoint(
                sessionId = ListeningSessionId(idValue(1)),
                queueEntryId = QueueEntryId(idValue(2)),
                recordingId = RecordingId(idValue(3)),
                sourceReferenceId = SourceReferenceId(idValue(4)),
                startedAtWallClock = Instant.parse("2026-08-20T00:00:00Z"),
                activeListenedMs = 42_000,
                lastPositionMs = 37_000,
                chosenByUser = true,
            )
        val session =
            FinalizedListeningSession(
                sessionId = ListeningSessionId(idValue(1)),
                queueEntryId = QueueEntryId(idValue(2)),
                recordingId = RecordingId(idValue(3)),
                sourceReferenceId = SourceReferenceId(idValue(4)),
                startedAtWallClock = Instant.parse("2026-08-20T00:00:00Z"),
                endedAtWallClock = Instant.parse("2026-08-20T00:01:00Z"),
                activeListenedMs = 42_000,
                lastPositionMs = 37_000,
                completionReason = ListeningSessionCompletionReason.QUEUE_REPLACED,
                chosenByUser = true,
            )

        delivery.offerCheckpoint(checkpoint)
        withTimeout(TEST_TIMEOUT_MS) { repository.started.await() }
        delivery.offerCheckpoint(
            checkpoint.copy(activeListenedMs = 43_000, lastPositionMs = 38_000)
        )
        delivery.offer(session)
        val drain = async { delivery.closeAndDrain() }

        assertFalse(drain.isCompleted)
        repository.allow.complete(Unit)
        drain.await()

        assertEquals(2, repository.records.size)
        assertEquals(null, repository.records.first().endedAt)
        assertEquals("ACTIVE_CHECKPOINT", repository.records.first().completionKind)
        assertEquals(checkpoint.scrobbleAuthorized, repository.records.first().scrobbleAuthorized)
        assertEquals(checkpoint.accountId, repository.records.first().accountId)
        val record = repository.records.last()
        assertEquals(session.sessionId, record.sessionId)
        assertEquals(session.queueEntryId, record.queueEntryId)
        assertEquals(session.recordingId, record.recordingId)
        assertEquals(session.sourceReferenceId, record.sourceReferenceId)
        assertEquals(session.startedAtWallClock, record.startedAt)
        assertEquals(session.endedAtWallClock, record.endedAt)
        assertEquals(session.activeListenedMs, record.activeListenedMs)
        assertEquals(session.lastPositionMs, record.lastPositionMs)
        assertEquals(session.completionReason.name, record.completionKind)
        assertEquals(session.chosenByUser, record.chosenByUser)
        assertEquals(session.scrobbleAuthorized, record.scrobbleAuthorized)
        assertEquals(session.accountId, record.accountId)
        assertEquals(1, scheduled)
    }

    @Test
    fun `finalized sessions are losslessly persisted with bounded backpressure`() = runBlocking {
        val repository = BlockingListeningSessionRepository()
        repository.allow.complete(Unit)
        val delivery = R16ListeningSessionDelivery(this, repository)

        val totalSessions = 100
        for (i in 1..totalSessions) {
            val session =
                FinalizedListeningSession(
                    sessionId = ListeningSessionId(idValue(i)),
                    queueEntryId = QueueEntryId(idValue(i)),
                    recordingId = RecordingId(idValue(i)),
                    sourceReferenceId = SourceReferenceId(idValue(i)),
                    startedAtWallClock = Instant.parse("2026-08-20T00:00:00Z"),
                    endedAtWallClock = Instant.parse("2026-08-20T00:01:00Z"),
                    activeListenedMs = 42_000,
                    lastPositionMs = 37_000,
                    completionReason = ListeningSessionCompletionReason.NATURAL_END,
                    chosenByUser = true,
                )
            delivery.offer(session)
        }

        delivery.closeAndDrain()
        assertEquals(totalSessions, repository.records.size)
        assertEquals(
            (1..totalSessions).map { ListeningSessionId(idValue(it)) },
            repository.records.map { it.sessionId },
        )
    }

    private fun idValue(index: Int) =
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
}

private class BlockingListeningSessionRepository : R16ListeningSessionRepository {
    val started = CompletableDeferred<Unit>()
    val allow = CompletableDeferred<Unit>()
    val records = mutableListOf<R16ListeningSessionRecord>()

    override suspend fun persist(
        session: R16ListeningSessionRecord
    ): app.shippy.data.listening.R16ListeningSessionPersistResult {
        records += session
        started.complete(Unit)
        allow.await()
        return app.shippy.data.listening.R16ListeningSessionPersistResult(
            scrobbleQueued = session.endedAt != null
        )
    }
}

private const val TEST_TIMEOUT_MS = 2_000L
