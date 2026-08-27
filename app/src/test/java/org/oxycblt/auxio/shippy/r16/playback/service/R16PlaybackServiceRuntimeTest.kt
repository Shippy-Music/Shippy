/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackServiceRuntimeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.service

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCheckpoint
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCoreEvent
import app.shippy.core.playback.PlaybackReducer
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.r16.maintenance.R16LivePlaybackQueue
import org.oxycblt.auxio.shippy.r16.playback.BoundedPlaybackTraceRecorder
import org.oxycblt.auxio.shippy.r16.playback.PlaybackTraceEvent
import org.oxycblt.auxio.shippy.r16.playback.PlaybackTraceKind
import org.oxycblt.auxio.shippy.r16.playback.R16PlaybackAuthority

class R16PlaybackServiceRuntimeTest {
    @Test
    fun `runtime exposes the live queue to maintenance until it releases`() = runBlocking {
        val checkpoint = checkpoint()
        val liveQueue = R16LivePlaybackQueue()
        val runtime =
            R16PlaybackServiceRuntime(
                this,
                FakeAuthority(),
                FakeCheckpointRepository(checkpoint),
                BoundedPlaybackTraceRecorder(),
                checkpointDelayMs = 60_000,
                livePlaybackQueue = liveQueue,
            )

        runtime.attach()

        assertEquals(
            setOf(checkpoint.queue.baseQueue.single().recordingId),
            liveQueue.activeRecordingIdsOrNull(),
        )
        runtime.release()
        assertNull(liveQueue.activeRecordingIdsOrNull())
    }

    @Test
    fun `runtime restores routes checkpoints and releases one authority`() = runBlocking {
        val checkpoint = checkpoint()
        val checkpoints = FakeCheckpointRepository(checkpoint)
        val authority = FakeAuthority()
        val trace = BoundedPlaybackTraceRecorder(4)
        trace.record(PlaybackTraceEvent(0, 1, 1, PlaybackTraceKind.COMMAND, "PLAY", null, false))
        val runtime =
            R16PlaybackServiceRuntime(
                this,
                authority,
                checkpoints,
                trace,
                checkpointDelayMs = 60_000,
            )

        assertEquals(
            PlaybackCommandRejection.SERVICE_NOT_ATTACHED,
            (runtime.dispatch(PlaybackCommand.Play) as PlaybackCommandResult.Rejected).reason,
        )
        runtime.attach()

        assertEquals(checkpoint, authority.restored)
        assertEquals(R16PlaybackServiceLifecycle.ATTACHED, runtime.status.value.lifecycle)
        assertTrue(runtime.dispatch(PlaybackCommand.Play) is PlaybackCommandResult.Accepted)
        assertTrue(R16PlaybackForegroundPolicy.hasSession(runtime.snapshots.value))
        assertFalse(
            R16PlaybackForegroundPolicy.shouldRetainAfterTaskRemoval(
                runtime.snapshots.value,
                exitOnTaskRemoval = true,
                hasActiveCrew = false,
            )
        )
        assertTrue(runtime.exportTrace().contains("COMMAND|PLAY"))

        runtime.release()
        runtime.release()

        assertEquals(1, authority.releaseCount)
        assertEquals(checkpoint.copy(playWhenReady = false), checkpoints.saved)
        assertEquals(R16PlaybackServiceLifecycle.RELEASED, runtime.status.value.lifecycle)
    }

    @Test
    fun `corrupt checkpoint cannot prevent an empty runtime from attaching`() = runBlocking {
        val checkpoints = FakeCheckpointRepository(loadFailure = IllegalArgumentException("bad"))
        val runtime =
            R16PlaybackServiceRuntime(
                this,
                FakeAuthority(),
                checkpoints,
                BoundedPlaybackTraceRecorder(),
                checkpointDelayMs = 60_000,
            )

        runtime.attach()

        assertEquals(R16PlaybackServiceLifecycle.ATTACHED, runtime.status.value.lifecycle)
        assertNotNull(runtime.status.value.checkpointIssue)
        runtime.release()
        assertEquals(0, checkpoints.clearCount)
    }

    @Test
    fun `explicit clear retires unresolved checkpoint before a later attach`() = runBlocking {
        val checkpoints =
            FakeCheckpointRepository(
                loaded = checkpoint(),
                loadFailure = IllegalArgumentException("bad"),
            )
        val runtime =
            R16PlaybackServiceRuntime(
                this,
                FakeAuthority(),
                checkpoints,
                BoundedPlaybackTraceRecorder(),
                checkpointDelayMs = 60_000,
            )

        runtime.attach()
        assertTrue(runtime.dispatch(PlaybackCommand.Clear) is PlaybackCommandResult.Accepted)
        assertEquals(1, checkpoints.clearCount)
        runtime.release()

        checkpoints.loadFailure = null
        val recoveredAuthority = FakeAuthority()
        val recoveredRuntime =
            R16PlaybackServiceRuntime(
                this,
                recoveredAuthority,
                checkpoints,
                BoundedPlaybackTraceRecorder(),
                checkpointDelayMs = 60_000,
            )
        recoveredRuntime.attach()

        assertEquals(null, recoveredAuthority.restored)
        recoveredRuntime.release()
    }

    private class FakeAuthority : R16PlaybackAuthority {
        private val mutableSnapshots = MutableStateFlow(PlaybackSnapshot.Empty)
        override val snapshots: StateFlow<PlaybackSnapshot> = mutableSnapshots
        var restored: PlaybackCheckpoint? = null
        var releaseCount = 0

        override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult =
            PlaybackCommandResult.Accepted(
                mutableSnapshots.value.generation,
                mutableSnapshots.value.queueRevision,
            )

        override fun checkpoint(): PlaybackCheckpoint =
            PlaybackCheckpoint.capture(mutableSnapshots.value)

        override suspend fun restore(
            checkpoint: PlaybackCheckpoint,
            allowResume: Boolean,
        ): PlaybackCommandResult {
            restored = checkpoint
            mutableSnapshots.value =
                PlaybackReducer()
                    .reduce(
                        PlaybackSnapshot.Empty,
                        PlaybackCoreEvent.RestoreContext(checkpoint, allowResume),
                    )
                    .snapshot
            return PlaybackCommandResult.Accepted(
                mutableSnapshots.value.generation,
                mutableSnapshots.value.queueRevision,
            )
        }

        override suspend fun release() {
            releaseCount++
        }
    }

    private class FakeCheckpointRepository(
        private val loaded: PlaybackCheckpoint? = null,
        var loadFailure: Exception? = null,
    ) : R16PlaybackCheckpointRepository {
        var saved: PlaybackCheckpoint? = null
        var clearCount = 0
        private var durable: PlaybackCheckpoint? = loaded

        override suspend fun load(): PlaybackCheckpoint? {
            loadFailure?.let { throw it }
            return durable
        }

        override suspend fun save(checkpoint: PlaybackCheckpoint) {
            saved = checkpoint
            durable = checkpoint
        }

        override suspend fun clear() {
            clearCount++
            saved = null
            durable = null
        }
    }

    private fun checkpoint(): PlaybackCheckpoint {
        val entry =
            QueueEntry(
                id = QueueEntryId(id(1)),
                recordingId = RecordingId(id(2)),
                origin = null,
                playlistEntryId = null,
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        return PlaybackCheckpoint(
            queue = QueueState(listOf(entry), listOf(entry.id), entry.id, ShuffleState.Off),
            positionMs = 123,
            playWhenReady = true,
            repeatMode = RepeatMode.OFF,
        )
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
