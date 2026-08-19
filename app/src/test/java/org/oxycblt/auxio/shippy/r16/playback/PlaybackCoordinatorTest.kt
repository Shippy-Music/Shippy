/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCoordinatorTest.kt is part of Auxio.
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

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackSourceHandle
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    @Test
    fun `inline engine commit publishes the exact selected duplicate occurrence`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
        val recordingId = recordingId(7)
        val first = entry(1, recordingId)
        val selected = entry(2, recordingId)

        val result =
            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first, selected), selected.id))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == selected.id }
            }

        assertTrue(result is PlaybackCommandResult.Accepted)
        assertEquals(selected.id, committed.queue.currentQueueEntryId)
        assertEquals(selected.id, committed.committedQueueEntryId)
        assertEquals(selected.id, engine.transactions.receive().expectedCurrentEntryId)
        coordinator.release()
        assertEquals(
            app.shippy.core.playback.PlaybackCommandRejection.COORDINATOR_RELEASED,
            (coordinator.dispatch(PlaybackCommand.Play) as PlaybackCommandResult.Rejected).reason,
        )
    }

    @Test
    fun `late engine commit from replaced generation cannot overwrite newer intent`() =
        runBlocking {
            val engine = FakePlayerEngine(autoCommit = false)
            val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
            val first = entry(1, recordingId(1))
            val second = entry(2, recordingId(2))

            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first), first.id))
            val oldTransaction = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(second), second.id))
            val currentTransaction = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }

            engine.emit(PlayerObservation.CurrentItemCommitted(oldTransaction.tag))
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.generation == currentTransaction.tag.generation }
            }
            assertNull(coordinator.snapshots.value.committedQueueEntryId)

            engine.emit(PlayerObservation.CurrentItemCommitted(currentTransaction.tag))
            val committed =
                withTimeout(TEST_TIMEOUT_MS) {
                    coordinator.snapshots.first { it.committedQueueEntryId == second.id }
                }
            assertEquals(second.id, committed.committedQueueEntryId)
            coordinator.release()
        }

    @Test
    fun `shuffle and repeat navigation preserve occurrence identity and deterministic order`() =
        runBlocking {
            val engine = FakePlayerEngine(autoCommit = true)
            val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
            val entries = (1..4).map { entry(it, recordingId(it)) }

            coordinator.dispatch(
                PlaybackCommand.PlayContext(entries, entries[1].id, shuffleSeed = 42)
            )
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == entries[1].id }
            }
            val shuffledOrder = coordinator.snapshots.value.queue.traversalOrder
            coordinator.dispatch(PlaybackCommand.SetRepeat(RepeatMode.ALL))
            coordinator.dispatch(PlaybackCommand.SetShuffle(enabled = false, seed = 42))

            assertEquals(entries[1].id, coordinator.snapshots.value.committedQueueEntryId)
            assertEquals(
                entries.map(QueueEntry::id),
                coordinator.snapshots.value.queue.traversalOrder,
            )
            assertEquals(entries.map(QueueEntry::id).toSet(), shuffledOrder.toSet())
            coordinator.release()
        }

    private fun entry(index: Int, recordingId: RecordingId) =
        QueueEntry(
            id = queueEntryId(index),
            recordingId = recordingId,
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun recordingId(index: Int) = RecordingId(idValue(index + 100))

    private fun queueEntryId(index: Int) = QueueEntryId(idValue(index))

    private fun idValue(index: Int) =
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
}

private class ImmediateSourcePreparer : PlaybackSourcePreparer {
    override suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult =
        PlaybackPreparationResult.Ready(
            PlaybackSourceHandle(
                stableKey = "prepared:${request.recordingId.value}",
                sourceReferenceId = SourceReferenceId(request.recordingId.value),
                mediaAssetId = null,
            )
        )
}

private class FakePlayerEngine(private val autoCommit: Boolean) : PlayerEngine {
    private val mutableObservations = MutableSharedFlow<PlayerObservation>(extraBufferCapacity = 16)
    override val observations: Flow<PlayerObservation> = mutableObservations
    val transactions = Channel<PlayerTransaction>(Channel.UNLIMITED)

    override suspend fun apply(transaction: PlayerTransaction) {
        transactions.send(transaction)
        if (autoCommit) {
            mutableObservations.emit(PlayerObservation.CurrentItemCommitted(transaction.tag))
            mutableObservations.emit(
                PlayerObservation.PhaseChanged(
                    generation = transaction.tag.generation,
                    queueEntryId = transaction.expectedCurrentEntryId,
                    phase = CommittedEnginePhase.READY,
                )
            )
        }
    }

    suspend fun emit(observation: PlayerObservation) {
        mutableObservations.emit(observation)
    }

    override suspend fun setPlayWhenReady(value: Boolean) = Unit

    override suspend fun seek(queueEntryId: QueueEntryId, positionMs: Long) = Unit

    override suspend fun setRepeat(mode: RepeatMode) = Unit

    override suspend fun release() = Unit
}

private const val TEST_TIMEOUT_MS = 2_000L
