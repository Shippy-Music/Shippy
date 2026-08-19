/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackReducerTest.kt is part of Auxio.
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
package app.shippy.core

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackCoreEvent
import app.shippy.core.playback.PlaybackEffect
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackReducer
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.PlaybackSourceHandle
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueReducer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReducerTest {
    private val playbackReducer = PlaybackReducer()
    private val queueReducer = QueueReducer()

    @Test
    fun `replacement increments generation and discards stale preparation`() {
        val firstQueue = queueReducer.replace(listOf(entry(1)), queueEntryId(1))
        val first =
            playbackReducer.reduce(
                PlaybackSnapshot.Empty,
                PlaybackCoreEvent.ReplaceContext(firstQueue, queueEntryId(1), true),
            )
        val staleTag = (first.effects.single() as PlaybackEffect.PrepareSource).tag
        val secondQueue = queueReducer.replace(listOf(entry(2)), queueEntryId(2))
        val second =
            playbackReducer.reduce(
                first.snapshot,
                PlaybackCoreEvent.ReplaceContext(secondQueue, queueEntryId(2), true),
            )

        val staleResult =
            playbackReducer.reduce(
                second.snapshot,
                PlaybackCoreEvent.SourceReady(staleTag, source(1)),
            )

        assertEquals(2, second.snapshot.generation)
        assertTrue(staleResult.snapshot === second.snapshot)
        assertTrue(staleResult.effects.isEmpty())
    }

    @Test
    fun `current identity publishes only after the matching engine commit`() {
        val queue = queueReducer.replace(listOf(entry(1)), queueEntryId(1))
        val replacing =
            playbackReducer.reduce(
                PlaybackSnapshot.Empty,
                PlaybackCoreEvent.ReplaceContext(queue, queueEntryId(1), true),
            )
        val tag = (replacing.effects.single() as PlaybackEffect.PrepareSource).tag
        val prepared =
            playbackReducer.reduce(
                replacing.snapshot,
                PlaybackCoreEvent.SourceReady(tag, source(1)),
            )
        val duplicatePreparation =
            playbackReducer.reduce(prepared.snapshot, PlaybackCoreEvent.SourceReady(tag, source(1)))

        val wrongCommit =
            playbackReducer.reduce(
                prepared.snapshot,
                PlaybackCoreEvent.EngineCommitted(tag.copy(queueEntryId = queueEntryId(9))),
            )
        val committed =
            playbackReducer.reduce(prepared.snapshot, PlaybackCoreEvent.EngineCommitted(tag))

        assertNull(prepared.snapshot.committedQueueEntryId)
        assertTrue(duplicatePreparation.snapshot === prepared.snapshot)
        assertTrue(duplicatePreparation.effects.isEmpty())
        assertTrue(wrongCommit.snapshot === prepared.snapshot)
        assertEquals(queueEntryId(1), committed.snapshot.committedQueueEntryId)
        assertEquals(PlaybackPhase.Ready(queueEntryId(1)), committed.snapshot.phase)
    }

    @Test
    fun `removing current invalidates old callbacks and prepares deterministic replacement`() {
        val entries = listOf(entry(1), entry(2), entry(3))
        val queue = queueReducer.replace(entries, queueEntryId(2))
        val replacing =
            playbackReducer.reduce(
                PlaybackSnapshot.Empty,
                PlaybackCoreEvent.ReplaceContext(queue, queueEntryId(2), true),
            )
        val oldTag = (replacing.effects.single() as PlaybackEffect.PrepareSource).tag
        val prepared =
            playbackReducer.reduce(
                replacing.snapshot,
                PlaybackCoreEvent.SourceReady(oldTag, source(2)),
            )
        val committed =
            playbackReducer.reduce(prepared.snapshot, PlaybackCoreEvent.EngineCommitted(oldTag))
        val changedQueue = queueReducer.remove(queue, setOf(queueEntryId(2)))

        val changed =
            playbackReducer.reduce(committed.snapshot, PlaybackCoreEvent.QueueChanged(changedQueue))
        val replacementTag = (changed.effects.single() as PlaybackEffect.PrepareSource).tag
        val staleCallback =
            playbackReducer.reduce(
                changed.snapshot,
                PlaybackCoreEvent.EnginePhaseChanged(
                    generation = oldTag.generation,
                    queueEntryId = oldTag.queueEntryId,
                    phase = CommittedEnginePhase.PLAYING,
                ),
            )

        assertEquals(queueEntryId(3), replacementTag.queueEntryId)
        assertNull(changed.snapshot.committedQueueEntryId)
        assertEquals(PlaybackPhase.Preparing(queueEntryId(3)), changed.snapshot.phase)
        assertTrue(staleCallback.snapshot === changed.snapshot)
    }

    @Test
    fun `explicit selection invalidates the prior commit and scopes engine failure to its tag`() {
        val queue = queueReducer.replace(listOf(entry(1), entry(2)), queueEntryId(1))
        val replacing =
            playbackReducer.reduce(
                PlaybackSnapshot.Empty,
                PlaybackCoreEvent.ReplaceContext(queue, queueEntryId(1), true),
            )
        val firstTag = (replacing.effects.single() as PlaybackEffect.PrepareSource).tag
        val firstPrepared =
            playbackReducer.reduce(
                replacing.snapshot,
                PlaybackCoreEvent.SourceReady(firstTag, source(1)),
            )
        val firstCommitted =
            playbackReducer.reduce(
                firstPrepared.snapshot,
                PlaybackCoreEvent.EngineCommitted(firstTag),
            )
        val selecting =
            playbackReducer.reduce(
                firstCommitted.snapshot,
                PlaybackCoreEvent.SelectEntry(queueEntryId(2)),
            )
        val secondTag = (selecting.effects.single() as PlaybackEffect.PrepareSource).tag
        val secondPrepared =
            playbackReducer.reduce(
                selecting.snapshot,
                PlaybackCoreEvent.SourceReady(secondTag, source(2)),
            )
        val error = PlaybackError("ENGINE", retryable = true)
        val staleFailure =
            playbackReducer.reduce(
                secondPrepared.snapshot,
                PlaybackCoreEvent.EngineFailed(firstTag.generation, firstTag.queueEntryId, error),
            )
        val currentFailure =
            playbackReducer.reduce(
                secondPrepared.snapshot,
                PlaybackCoreEvent.EngineFailed(secondTag.generation, secondTag.queueEntryId, error),
            )

        assertNull(selecting.snapshot.committedQueueEntryId)
        assertEquals(PlaybackPhase.Preparing(queueEntryId(2)), selecting.snapshot.phase)
        assertTrue(staleFailure.snapshot === secondPrepared.snapshot)
        assertEquals(PlaybackPhase.Failed(queueEntryId(2), error), currentFailure.snapshot.phase)
    }

    private fun entry(value: Int) =
        QueueEntry(
            id = queueEntryId(value),
            recordingId = RecordingId(idValue(value)),
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun source(value: Int) =
        PlaybackSourceHandle(
            stableKey = "source-$value",
            sourceReferenceId = SourceReferenceId(idValue(value)),
            mediaAssetId = null,
        )

    private fun queueEntryId(value: Int) = QueueEntryId(idValue(value))

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
