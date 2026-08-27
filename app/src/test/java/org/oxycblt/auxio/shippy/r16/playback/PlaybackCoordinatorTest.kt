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

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.listening.ActiveListeningSessionCheckpoint
import app.shippy.core.listening.FinalizedListeningSession
import app.shippy.core.listening.ListeningSessionCompletionReason
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.PlaybackSourceHandle
import app.shippy.core.playback.PositionAnchor
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.lastfm.LastFmAccountId

class PlaybackCoordinatorTest {
    @Test
    fun `inline engine commit publishes the exact selected duplicate occurrence`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val trace = BoundedPlaybackTraceRecorder(capacity = 16)
        val coordinator =
            PlaybackCoordinator(this, engine, ImmediateSourcePreparer(), traceSink = trace)
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
        val traceEvents = trace.snapshot()
        assertEquals(
            traceEvents.map(PlaybackTraceEvent::sequence).sorted(),
            traceEvents.map(PlaybackTraceEvent::sequence),
        )
        assertTrue(traceEvents.any { it.detail == "APPLY_ENGINE_WINDOW" })
        assertTrue(
            traceEvents.any { it.detail == "ENGINE_COMMITTED" && it.queueEntryId == selected.id }
        )
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
    fun `resume current rejects an observed entry replaced before its serialized command`() =
        runBlocking {
            val engine = FakePlayerEngine(autoCommit = true)
            val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
            val first = entry(1, recordingId(1))
            val second = entry(2, recordingId(2))

            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first, second), first.id))
            coordinator.dispatch(PlaybackCommand.Pause)
            coordinator.dispatch(PlaybackCommand.GoTo(second.id))

            val result =
                coordinator.dispatch(PlaybackCommand.ResumeCurrent(first.id, first.recordingId))

            assertEquals(
                PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND),
                result,
            )
            assertEquals(second.id, coordinator.snapshots.value.queue.currentQueueEntryId)
            assertEquals(false, coordinator.snapshots.value.playWhenReady)
            coordinator.release()
        }

    @Test
    fun `retry current rejects after next advances before its serialized command`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = false)
        val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
        val first = entry(1, recordingId(1))
        val second = entry(2, recordingId(2))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first, second), first.id))
        val transaction = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        engine.emit(
            PlayerObservation.Failed(
                transaction.tag.generation,
                first.id,
                app.shippy.core.playback.PlaybackError("SOURCE_UNAVAILABLE", retryable = true),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                it.phase is app.shippy.core.playback.PlaybackPhase.Failed
            }
        }

        assertTrue(coordinator.dispatch(PlaybackCommand.Next) is PlaybackCommandResult.Accepted)
        assertEquals(
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND),
            coordinator.dispatch(PlaybackCommand.RetryCurrent(first.id, first.recordingId)),
        )
        assertEquals(second.id, coordinator.snapshots.value.queue.currentQueueEntryId)
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

    @Test
    fun `checkpoint restore preserves queue intent but prepares a fresh source`() = runBlocking {
        val originalEngine = FakePlayerEngine(autoCommit = true)
        val original =
            PlaybackCoordinator(this, originalEngine, ImmediateSourcePreparer("original"))
        val recordingId = recordingId(9)
        val entries = listOf(entry(1, recordingId), entry(2, recordingId), entry(3, recordingId))
        original.dispatch(PlaybackCommand.PlayContext(entries, entries[1].id, shuffleSeed = 73))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                original.snapshots.first { it.committedQueueEntryId == entries[1].id }
            }
        originalEngine.emit(
            PlayerObservation.PositionChanged(
                generation = committed.generation,
                queueEntryId = entries[1].id,
                position = PositionAnchor(42_000, 7_000, playbackSpeed = 1.25, advancing = true),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            original.snapshots.first { it.position.positionMs == 42_000L }
        }
        val checkpoint = original.checkpoint()
        original.release()

        val restoredEngine = FakePlayerEngine(autoCommit = true)
        val restored =
            PlaybackCoordinator(this, restoredEngine, ImmediateSourcePreparer("restored"))
        restored.restore(checkpoint, allowResume = false)
        val transaction = withTimeout(TEST_TIMEOUT_MS) { restoredEngine.transactions.receive() }
        val restoredSnapshot =
            withTimeout(TEST_TIMEOUT_MS) {
                restored.snapshots.first { it.committedQueueEntryId == entries[1].id }
            }

        assertEquals(42_000L, transaction.startPositionMs)
        assertEquals(false, transaction.playWhenReady)
        assertEquals(checkpoint.queue.traversalOrder, restoredSnapshot.queue.traversalOrder)
        assertEquals(entries[1].id, restoredSnapshot.committedQueueEntryId)
        assertTrue(
            restoredSnapshot.resolvedSources
                .getValue(entries[1].id)
                .stableKey
                .startsWith("restored:")
        )
        restored.release()
    }

    @Test
    fun `engine window stays bounded and rebuilds around a distant occurrence`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
        val entries = (1..7).map { entry(it, recordingId(it)) }

        coordinator.dispatch(PlaybackCommand.PlayContext(entries, entries[2].id))
        withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        val initialWindow = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }

        assertEquals(
            listOf(entries[1].id, entries[2].id, entries[3].id, entries[4].id),
            initialWindow.window.map(PreparedEngineItem::queueEntryId),
        )

        coordinator.dispatch(PlaybackCommand.GoTo(entries[6].id))
        withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        val distantWindow = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }

        assertEquals(entries[6].id, distantWindow.expectedCurrentEntryId)
        assertEquals(
            listOf(entries[5].id, entries[6].id),
            distantWindow.window.map(PreparedEngineItem::queueEntryId),
        )
        coordinator.release()
    }

    @Test
    fun `automatic engine transition commits the next window occurrence`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val finalized = Channel<FinalizedListeningSession>(1)
        val coordinator =
            PlaybackCoordinator(
                this,
                engine,
                ImmediateSourcePreparer(),
                listeningSessionSink = ListeningSessionSink { finalized.trySend(it) },
            )
        val entries = (1..3).map { entry(it, recordingId(it)) }

        coordinator.dispatch(PlaybackCommand.PlayContext(entries, entries[0].id))
        withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        val expanded = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                it.engineWindow.size == 3 && it.expectedEngineCommit == null
            }
        }
        engine.emit(
            PlayerObservation.CurrentItemCommitted(
                expanded.tag.copy(queueEntryId = entries[1].id),
                automaticTransition = true,
            )
        )

        val advanced =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == entries[1].id }
            }
        assertEquals(entries[1].id, advanced.queue.currentQueueEntryId)
        assertEquals(entries[1].id, advanced.committedQueueEntryId)
        val completed = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
        assertEquals(entries[0].id, completed.queueEntryId)
        assertEquals(ListeningSessionCompletionReason.NATURAL_END, completed.completionReason)
        coordinator.release()
    }

    @Test
    fun `retryable source failure recovers on the same queue occurrence`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val preparer = RetryingSourcePreparer()
        val trace = BoundedPlaybackTraceRecorder()
        val coordinator = PlaybackCoordinator(this, engine, preparer, traceSink = trace)
        val selected = entry(1, recordingId(1))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(selected), selected.id))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == selected.id }
            }

        assertEquals(listOf(1, 2), preparer.attempts)
        assertEquals(selected.id, committed.committedQueueEntryId)
        assertTrue(trace.snapshot().any { it.detail == "SOURCE_RECOVERY_STARTED" })
        coordinator.release()
    }

    @Test
    fun `retryable engine failure refreshes the same occurrence only once`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val preparer = CountingSourcePreparer()
        val coordinator = PlaybackCoordinator(this, engine, preparer)
        val selected = entry(1, recordingId(1))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(selected), selected.id))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first {
                    it.committedQueueEntryId == selected.id && it.expectedEngineCommit == null
                }
            }
        delay(20)
        while (engine.transactions.tryReceive().isSuccess) {
            // Discard the initial and bounded-window commits.
        }

        engine.emit(
            PlayerObservation.Failed(
                committed.generation,
                selected.id,
                app.shippy.core.playback.PlaybackError("EXPIRED_STREAM", retryable = true),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                preparer.requests.size == 2 &&
                    it.committedQueueEntryId == selected.id &&
                    it.expectedEngineCommit == null
            }
        }
        val requestsAfterRecovery = preparer.requests.size

        engine.emit(
            PlayerObservation.Failed(
                committed.generation,
                selected.id,
                app.shippy.core.playback.PlaybackError("EXPIRED_STREAM", retryable = true),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                it.phase is app.shippy.core.playback.PlaybackPhase.Failed
            }
        }
        delay(20)

        assertEquals(requestsAfterRecovery, preparer.requests.size)
        assertEquals(selected.id, coordinator.snapshots.value.committedQueueEntryId)
        coordinator.release()
    }

    @Test
    fun `continuous playing ticks finalize monotonic audible time`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val clock =
            FakePlaybackClock(
                elapsedRealtimeMs = 1_000,
                wallClockValue = Instant.parse("2026-08-20T00:00:05Z"),
            )
        val finalized = Channel<FinalizedListeningSession>(1)
        val coordinator =
            PlaybackCoordinator(
                this,
                engine,
                ImmediateSourcePreparer(),
                listeningSessionSink =
                    ListeningSessionSink {
                        finalized.trySend(it)
                        Unit
                    },
                playbackClock = clock,
                listeningSessionIdFactory =
                    ListeningSessionIdFactory { ListeningSessionId(idValue(501)) },
                listeningTickIntervalMs = 10,
            )
        val selected = entry(1, recordingId(1))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(selected), selected.id))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == selected.id }
            }
        engine.emit(
            PlayerObservation.PhaseChanged(
                committed.generation,
                selected.id,
                CommittedEnginePhase.PLAYING,
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                it.phase == app.shippy.core.playback.PlaybackPhase.Playing(selected.id)
            }
        }
        clock.elapsedRealtimeMs = 6_000
        delay(30)
        clock.wallClockValue = Instant.parse("2026-08-19T00:00:05Z")
        engine.emit(
            PlayerObservation.PhaseChanged(
                committed.generation,
                selected.id,
                CommittedEnginePhase.ENDED,
            )
        )

        val session = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
        assertEquals(ListeningSessionId(idValue(501)), session.sessionId)
        assertEquals(selected.id, session.queueEntryId)
        assertEquals(recordingId(1), session.recordingId)
        assertEquals(SourceReferenceId(recordingId(1).value), session.sourceReferenceId)
        assertEquals(5_000L, session.activeListenedMs)
        assertEquals(0L, session.lastPositionMs)
        assertEquals(Instant.parse("2026-08-20T00:00:05Z"), session.endedAtWallClock)
        assertEquals(ListeningSessionCompletionReason.NATURAL_END, session.completionReason)
        coordinator.release()
    }

    @Test
    fun `listening checkpoints are interval bounded and capture a pause`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val clock = FakePlaybackClock(elapsedRealtimeMs = 1_000)
        val finalized = Channel<FinalizedListeningSession>(1)
        val checkpoints = Channel<ActiveListeningSessionCheckpoint>(Channel.UNLIMITED)
        val sink =
            object : ListeningSessionSink {
                override suspend fun offer(session: FinalizedListeningSession) {
                    finalized.trySend(session)
                }

                override fun offerCheckpoint(checkpoint: ActiveListeningSessionCheckpoint) {
                    checkpoints.trySend(checkpoint)
                }
            }
        val coordinator =
            PlaybackCoordinator(
                this,
                engine,
                ImmediateSourcePreparer(),
                listeningSessionSink = sink,
                playbackClock = clock,
                listeningSessionIdFactory =
                    ListeningSessionIdFactory { ListeningSessionId(idValue(701)) },
                listeningTickIntervalMs = 10,
                listeningCheckpointIntervalMs = 5_000,
            )
        val selected = entry(1, recordingId(1))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(selected), selected.id))
        val committed =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == selected.id }
            }
        engine.emit(
            PlayerObservation.PhaseChanged(
                committed.generation,
                selected.id,
                CommittedEnginePhase.PLAYING,
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first {
                it.phase == app.shippy.core.playback.PlaybackPhase.Playing(selected.id)
            }
        }

        clock.elapsedRealtimeMs = 4_000
        delay(40)
        assertTrue(checkpoints.tryReceive().isFailure)

        engine.emit(
            PlayerObservation.PositionChanged(
                generation = committed.generation,
                queueEntryId = selected.id,
                position = PositionAnchor(1_234, 0, playbackSpeed = 1.0, advancing = true),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first { it.position.positionMs == 1_234L }
        }
        clock.elapsedRealtimeMs = 6_100
        val intervalCheckpoint = withTimeout(TEST_TIMEOUT_MS) { checkpoints.receive() }
        assertEquals(ListeningSessionId(idValue(701)), intervalCheckpoint.sessionId)
        assertEquals(selected.id, intervalCheckpoint.queueEntryId)
        assertEquals(recordingId(1), intervalCheckpoint.recordingId)
        assertEquals(SourceReferenceId(recordingId(1).value), intervalCheckpoint.sourceReferenceId)
        assertEquals(5_100L, intervalCheckpoint.activeListenedMs)
        assertEquals(1_234L, intervalCheckpoint.lastPositionMs)
        assertTrue(checkpoints.tryReceive().isFailure)

        clock.elapsedRealtimeMs = 7_000
        engine.emit(
            PlayerObservation.PhaseChanged(
                committed.generation,
                selected.id,
                CommittedEnginePhase.READY,
            )
        )
        val pausedCheckpoint = withTimeout(TEST_TIMEOUT_MS) { checkpoints.receive() }
        assertEquals(6_000L, pausedCheckpoint.activeListenedMs)
        assertEquals(1_234L, pausedCheckpoint.lastPositionMs)
        coordinator.release()
    }

    @Test
    fun `replacement clear and release preserve close reason and queue identity`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val clock = FakePlaybackClock(elapsedRealtimeMs = 1_000)
        val finalized = Channel<FinalizedListeningSession>(Channel.UNLIMITED)
        var nextSessionId = 900
        val coordinator =
            PlaybackCoordinator(
                this,
                engine,
                ImmediateSourcePreparer(),
                listeningSessionSink = ListeningSessionSink { finalized.trySend(it) },
                playbackClock = clock,
                listeningSessionIdFactory =
                    ListeningSessionIdFactory { ListeningSessionId(idValue(nextSessionId++)) },
            )
        val first = entry(1, recordingId(1))
        val second = entry(2, recordingId(2))

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first), first.id))
        val firstCommitted =
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == first.id }
            }
        engine.emit(
            PlayerObservation.PositionChanged(
                generation = firstCommitted.generation,
                queueEntryId = first.id,
                position = PositionAnchor(27_000, 0, playbackSpeed = 1.0, advancing = false),
            )
        )
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first { it.position.positionMs == 27_000L }
        }
        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(second), second.id))
        val replaced = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
        assertEquals(ListeningSessionId(idValue(900)), replaced.sessionId)
        assertEquals(first.id, replaced.queueEntryId)
        assertEquals(recordingId(1), replaced.recordingId)
        assertEquals(27_000L, replaced.lastPositionMs)
        assertEquals(ListeningSessionCompletionReason.QUEUE_REPLACED, replaced.completionReason)

        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first { it.committedQueueEntryId == second.id }
        }
        coordinator.dispatch(PlaybackCommand.Clear)
        val cleared = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
        assertEquals(ListeningSessionId(idValue(901)), cleared.sessionId)
        assertEquals(second.id, cleared.queueEntryId)
        assertEquals(ListeningSessionCompletionReason.CLEARED, cleared.completionReason)

        coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first), first.id))
        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.snapshots.first { it.committedQueueEntryId == first.id }
        }
        coordinator.release()
        val released = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
        assertEquals(ListeningSessionId(idValue(902)), released.sessionId)
        assertEquals(first.id, released.queueEntryId)
        assertEquals(ListeningSessionCompletionReason.RELEASED, released.completionReason)
    }

    @Test
    fun `session captures identity at start and mid-track account switch does not change finalized session accountId`() =
        runBlocking {
            val engine = FakePlayerEngine(autoCommit = true)
            val finalized = Channel<FinalizedListeningSession>(Channel.UNLIMITED)
            val checkpoints = Channel<ActiveListeningSessionCheckpoint>(Channel.UNLIMITED)
            var currentIdentity =
                ListeningSessionIdentity(
                    scrobbleAuthorized = true,
                    accountId = LastFmAccountId.hash("alice"),
                )
            val coordinator =
                PlaybackCoordinator(
                    this,
                    engine,
                    ImmediateSourcePreparer(),
                    listeningSessionSink =
                        object : ListeningSessionSink {
                            override suspend fun offer(session: FinalizedListeningSession) {
                                finalized.trySend(session)
                            }

                            override fun offerCheckpoint(
                                checkpoint: ActiveListeningSessionCheckpoint
                            ) {
                                checkpoints.trySend(checkpoint)
                            }
                        },
                    listeningSessionIdentityProvider = { currentIdentity },
                )
            val first = entry(1, recordingId(1))

            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first), first.id))
            val committed =
                withTimeout(TEST_TIMEOUT_MS) {
                    coordinator.snapshots.first { it.committedQueueEntryId == first.id }
                }
            engine.emit(
                PlayerObservation.PhaseChanged(
                    committed.generation,
                    first.id,
                    CommittedEnginePhase.PLAYING,
                )
            )

            // User switches accounts mid-track
            currentIdentity =
                ListeningSessionIdentity(
                    scrobbleAuthorized = true,
                    accountId = LastFmAccountId.hash("bob"),
                )

            // Pause triggers checkpoint
            engine.emit(
                PlayerObservation.PhaseChanged(
                    committed.generation,
                    first.id,
                    CommittedEnginePhase.READY,
                )
            )
            val checkpoint = withTimeout(TEST_TIMEOUT_MS) { checkpoints.receive() }
            assertTrue(checkpoint.scrobbleAuthorized)
            assertEquals(LastFmAccountId.hash("alice"), checkpoint.accountId)

            // End session
            coordinator.release()
            val session = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
            assertTrue(session.scrobbleAuthorized)
            assertEquals(LastFmAccountId.hash("alice"), session.accountId)
        }

    @Test
    fun `unauthorized listening session captures unauthorized state and null accountId`() =
        runBlocking {
            val engine = FakePlayerEngine(autoCommit = true)
            val finalized = Channel<FinalizedListeningSession>(1)
            val coordinator =
                PlaybackCoordinator(
                    this,
                    engine,
                    ImmediateSourcePreparer(),
                    listeningSessionSink = ListeningSessionSink { finalized.trySend(it) },
                    listeningSessionIdentityProvider = NoOpListeningSessionIdentityProvider,
                )
            val first = entry(1, recordingId(1))

            coordinator.dispatch(PlaybackCommand.PlayContext(listOf(first), first.id))
            withTimeout(TEST_TIMEOUT_MS) {
                coordinator.snapshots.first { it.committedQueueEntryId == first.id }
            }

            coordinator.release()
            val session = withTimeout(TEST_TIMEOUT_MS) { finalized.receive() }
            assertFalse(session.scrobbleAuthorized)
            assertNull(session.accountId)
        }

    @Test
    fun `ten thousand entry queue prepares only the bounded engine window`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val preparer = CountingSourcePreparer()
        val coordinator = PlaybackCoordinator(this, engine, preparer)
        val entries = (1..10_000).map { entry(it, recordingId(it)) }
        val selected = entries[4_999]

        withTimeout(TEST_TIMEOUT_MS) {
            coordinator.dispatch(PlaybackCommand.PlayContext(entries, selected.id))
        }
        withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }
        val expanded = withTimeout(TEST_TIMEOUT_MS) { engine.transactions.receive() }

        assertEquals(4, preparer.requests.size)
        assertEquals(
            listOf(entries[4_998].id, selected.id, entries[5_000].id, entries[5_001].id),
            expanded.window.map(PreparedEngineItem::queueEntryId),
        )
        coordinator.release()
    }

    @Test
    fun `seeded command trace preserves queue and playback invariants`() = runBlocking {
        val engine = FakePlayerEngine(autoCommit = true)
        val coordinator = PlaybackCoordinator(this, engine, ImmediateSourcePreparer())
        val random = Random(1_603)
        var nextId = 21
        val entries = (1 until nextId).map { entry(it, recordingId(it)) }
        coordinator.dispatch(PlaybackCommand.PlayContext(entries, entries[7].id))

        withTimeout(10_000) {
            repeat(300) {
                val snapshot = coordinator.snapshots.value
                val ids = snapshot.queue.baseQueue.map(QueueEntry::id)
                val command =
                    when (random.nextInt(8)) {
                        0 ->
                            if (random.nextBoolean()) PlaybackCommand.Play
                            else PlaybackCommand.Pause
                        1 ->
                            PlaybackCommand.SetShuffle(
                                enabled = random.nextBoolean(),
                                seed = random.nextLong(),
                            )
                        2 -> PlaybackCommand.SetRepeat(RepeatMode.entries[random.nextInt(3)])
                        3 -> PlaybackCommand.GoTo(ids[random.nextInt(ids.size)])
                        4 -> {
                            val addition = entry(nextId, recordingId(nextId))
                            nextId += 1
                            PlaybackCommand.AddToEnd(listOf(addition))
                        }
                        5 ->
                            if (ids.size > 1) {
                                PlaybackCommand.Remove(setOf(ids[random.nextInt(ids.size)]))
                            } else {
                                PlaybackCommand.Play
                            }
                        6 -> PlaybackCommand.Next
                        else -> PlaybackCommand.Previous
                    }
                coordinator.dispatch(command)
                assertSnapshotInvariants(coordinator.snapshots.value)
            }
        }
        coordinator.release()
    }

    private fun assertSnapshotInvariants(snapshot: PlaybackSnapshot) {
        val queueIds = snapshot.queue.baseQueue.map(QueueEntry::id)
        assertEquals(queueIds.toSet(), snapshot.queue.traversalOrder.toSet())
        assertEquals(queueIds.size, snapshot.queue.traversalOrder.size)
        assertTrue(
            snapshot.queue.currentQueueEntryId == null ||
                snapshot.queue.currentQueueEntryId in queueIds
        )
        assertTrue(
            snapshot.committedQueueEntryId == null || snapshot.committedQueueEntryId in queueIds
        )
        assertTrue(snapshot.resolvedSources.keys.all { it in queueIds })
        assertTrue(snapshot.engineWindow.size <= 4)
        assertTrue(snapshot.engineWindow.all { it in queueIds })
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

private class ImmediateSourcePreparer(private val prefix: String = "prepared") :
    PlaybackSourcePreparer {
    override suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult =
        PlaybackPreparationResult.Ready(
            PlaybackSourceHandle(
                stableKey = "$prefix:${request.recordingId.value}",
                sourceReferenceId = SourceReferenceId(request.recordingId.value),
                mediaAssetId = null,
            )
        )
}

private class RetryingSourcePreparer : PlaybackSourcePreparer {
    val attempts = mutableListOf<Int>()

    override suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult {
        attempts += request.attempt
        return if (request.attempt == 1) {
            PlaybackPreparationResult.Unavailable(
                app.shippy.core.playback.PlaybackError("EXPIRED_SOURCE", retryable = true)
            )
        } else {
            PlaybackPreparationResult.Ready(
                PlaybackSourceHandle(
                    stableKey = "recovered:${request.recordingId.value}",
                    sourceReferenceId = SourceReferenceId(request.recordingId.value),
                    mediaAssetId = null,
                )
            )
        }
    }
}

private class CountingSourcePreparer : PlaybackSourcePreparer {
    val requests = mutableListOf<PlaybackPreparationRequest>()

    override suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult {
        requests += request
        return PlaybackPreparationResult.Ready(
            PlaybackSourceHandle(
                stableKey = "counted:${request.recordingId.value}",
                sourceReferenceId = SourceReferenceId(request.recordingId.value),
                mediaAssetId = null,
            )
        )
    }
}

private class FakePlaybackClock(
    var elapsedRealtimeMs: Long,
    var wallClockValue: Instant = Instant.EPOCH,
) : PlaybackClock {
    override fun elapsedRealtimeMs(): Long = elapsedRealtimeMs

    override fun wallClock(): Instant = wallClockValue
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
