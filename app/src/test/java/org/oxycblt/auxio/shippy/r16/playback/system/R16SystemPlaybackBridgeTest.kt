/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemPlaybackBridgeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.system

import android.support.v4.media.session.PlaybackStateCompat
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.PositionAnchor
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.playback.R16RecordingPresentation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class R16SystemPlaybackBridgeTest {
    @Test
    fun `system display follows newly selected occurrence before the prior commit clears`() {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val base = snapshot(first, second)
        val transitioning =
            base.copy(
                queue = base.queue.copy(currentQueueEntryId = second.id),
                committedQueueEntryId = first.id,
                phase = PlaybackPhase.Preparing(second.id),
            )

        val state = R16SystemPlaybackProjector.project(transitioning, emptyMap())

        assertEquals(second.id, state.displayQueueEntryId)
        assertEquals(second.id, state.displayItem?.queueEntryId)
    }

    @Test
    fun `every surface projects the exact selected duplicate occurrence`() {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val snapshot = snapshot(first, second)
        val presentation =
            R16RecordingPresentation(
                recordingId = first.recordingId,
                title = "Track",
                artist = "Artist",
                releaseTitle = "Release",
                artworkLocation = "content://artwork/1",
                durationMs = 120_000,
            )

        val state =
            R16SystemPlaybackProjector.project(snapshot, mapOf(first.recordingId to presentation))
        val nowPlaying = checkNotNull(R16SystemNowPlayingProjection.project(state))

        assertEquals(listOf(second.id, first.id), state.traversal.map { it.queueEntryId })
        assertEquals(second.id, state.displayQueueEntryId)
        assertEquals(second.id, nowPlaying.queueEntryId)
        assertEquals(first.recordingId, nowPlaying.recordingId)
        assertEquals("Track", nowPlaying.title)
        assertEquals(R16QuickSettingsState.ACTIVE, R16QuickSettingsProjection.project(state))
    }

    @Test
    fun `system commands route occurrence identity without owning state`() = runBlocking {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val state = R16SystemPlaybackProjector.project(snapshot(first, second), emptyMap())
        val router = RecordingRouter()
        val commands =
            R16SystemPlaybackCommands(MutableStateFlow(state), router, shuffleSeed = { 44L })

        commands.goToQueueIndex(0)
        commands.cycleRepeat()
        commands.toggleShuffle()

        assertEquals(
            listOf(
                PlaybackCommand.GoTo(second.id),
                PlaybackCommand.SetRepeat(RepeatMode.OFF),
                PlaybackCommand.SetShuffle(enabled = false, seed = 44L),
            ),
            router.commands,
        )
        assertFalse(commands.goToQueueIndex(99) is PlaybackCommandResult.Accepted)

        val emptyCommands =
            R16SystemPlaybackCommands(
                MutableStateFlow(R16SystemPlaybackState.Empty),
                router,
                shuffleSeed = { 1L },
            )
        assertEquals(
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.EMPTY_QUEUE),
            emptyCommands.play(),
        )
        assertTrue(router.commands.none { it == PlaybackCommand.Play })
    }

    @Test
    fun `continue resumes only the exact current occurrence`() = runBlocking {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val router = RecordingRouter()
        val commands =
            R16SystemPlaybackCommands(
                MutableStateFlow(
                    R16SystemPlaybackProjector.project(snapshot(first, second), emptyMap())
                ),
                router,
                shuffleSeed = { 1L },
            )

        assertTrue(
            commands.resumeCurrent(first.id, first.recordingId) is R16ResumeCurrentResult.Rejected
        )
        assertTrue(router.commands.isEmpty())

        val accepted = commands.resumeCurrent(second.id, second.recordingId)
        assertEquals(
            R16ResumeCurrentResult.Accepted(second.id, second.recordingId, generation = 1),
            accepted,
        )
        assertEquals(
            listOf(PlaybackCommand.ResumeCurrent(second.id, second.recordingId)),
            router.commands,
        )
    }

    @Test
    fun `retry dispatches a conditional command for the exact failed occurrence`() = runBlocking {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val failedSnapshot =
            snapshot(first, second)
                .copy(
                    phase =
                        PlaybackPhase.Failed(
                            entryId = second.id,
                            error = PlaybackError("SOURCE_UNAVAILABLE", retryable = true),
                        ),
                    currentError = PlaybackError("SOURCE_UNAVAILABLE", retryable = true),
                )
        val router = RecordingRouter()
        val commands =
            R16SystemPlaybackCommands(
                MutableStateFlow(R16SystemPlaybackProjector.project(failedSnapshot, emptyMap())),
                router,
                shuffleSeed = { 1L },
            )

        assertEquals(
            PlaybackCommandResult.Accepted(generation = 1, queueRevision = 1),
            commands.retryCurrent(second.id, second.recordingId),
        )
        assertEquals(
            listOf(PlaybackCommand.RetryCurrent(second.id, second.recordingId)),
            router.commands,
        )
    }

    @Test
    fun `retry rejects stale duplicate identity and nonfailed phase`() = runBlocking {
        val first = entry(QUEUE_ONE)
        val second = entry(QUEUE_TWO)
        val router = RecordingRouter()
        val failedCommands =
            R16SystemPlaybackCommands(
                MutableStateFlow(
                    R16SystemPlaybackProjector.project(
                        snapshot(first, second)
                            .copy(
                                phase =
                                    PlaybackPhase.Failed(
                                        entryId = second.id,
                                        error =
                                            PlaybackError("SOURCE_UNAVAILABLE", retryable = true),
                                    )
                            ),
                        emptyMap(),
                    )
                ),
                router,
                shuffleSeed = { 1L },
            )

        assertEquals(
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND),
            failedCommands.retryCurrent(first.id, first.recordingId),
        )
        assertEquals(
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND),
            failedCommands.retryCurrent(
                QueueEntryId("10000000-0000-0000-0000-000000000003"),
                second.recordingId,
            ),
        )

        val nonfailedCommands =
            R16SystemPlaybackCommands(
                MutableStateFlow(
                    R16SystemPlaybackProjector.project(snapshot(first, second), emptyMap())
                ),
                router,
                shuffleSeed = { 1L },
            )
        assertEquals(
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND),
            nonfailedCommands.retryCurrent(second.id, second.recordingId),
        )
        assertEquals(emptyList<PlaybackCommand>(), router.commands)
    }

    @Test
    fun `media session advertises only implemented play controls`() {
        val actions = R16MediaSessionProjection.playbackState(R16SystemPlaybackState.Empty).actions

        assertEquals(0L, actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
        assertEquals(0L, actions and PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
        assertTrue(actions and PlaybackStateCompat.ACTION_PLAY != 0L)
    }

    @Test
    fun `media session keeps play callbacks available for future canonical routing`() {
        val mediaIdRequests = mutableListOf<String?>()
        val searchRequests = mutableListOf<String?>()
        val callback =
            R16MediaSessionCommandCallback(
                scope = CoroutineScope(Dispatchers.Unconfined),
                commands =
                    R16SystemPlaybackCommands(
                        MutableStateFlow(R16SystemPlaybackState.Empty),
                        object : PlaybackCommandRouter {
                            override suspend fun dispatch(
                                command: PlaybackCommand
                            ): PlaybackCommandResult =
                                PlaybackCommandResult.Rejected(PlaybackCommandRejection.EMPTY_QUEUE)
                        },
                        shuffleSeed = { 1L },
                    ),
                onPlayFromMediaIdRequested = { mediaId, _ -> mediaIdRequests += mediaId },
                onPlayFromSearchRequested = { query, _ -> searchRequests += query },
                onExitRequested = {},
            )

        callback.onPlayFromMediaId("recording-1", null)
        callback.onPlayFromSearch("query", null)

        assertEquals(listOf("recording-1"), mediaIdRequests)
        assertEquals(listOf("query"), searchRequests)
    }

    @Test
    fun `10k logical queue projects only engine window presentation and occurrences`() {
        val entries =
            (0 until 10_000).map { index ->
                QueueEntry(
                    id =
                        QueueEntryId(
                            UUID.nameUUIDFromBytes("queue-$index".toByteArray()).toString()
                        ),
                    recordingId =
                        RecordingId(
                            UUID.nameUUIDFromBytes("recording-$index".toByteArray()).toString()
                        ),
                    origin = null,
                    playlistEntryId = null,
                    contributor = null,
                    addedAt = Instant.EPOCH,
                )
            }
        val current = entries[5_000]
        val adjacent = listOf(entries[4_999].id, current.id, entries[5_001].id)
        val snapshot =
            PlaybackSnapshot(
                generation = 1,
                queueRevision = 1,
                queue =
                    QueueState(
                        baseQueue = entries,
                        traversalOrder = entries.map(QueueEntry::id),
                        currentQueueEntryId = current.id,
                        shuffle = ShuffleState.Off,
                    ),
                committedQueueEntryId = current.id,
                phase = PlaybackPhase.Playing(current.id),
                playWhenReady = true,
                repeatMode = RepeatMode.OFF,
                position = PositionAnchor(0, 0, advancing = true),
                resolvedSources = emptyMap(),
                engineWindow = adjacent,
                expectedEngineCommit = null,
                currentError = null,
            )

        val expectedRecordingIds =
            adjacent.map { id -> entries.first { it.id == id }.recordingId }.toSet()
        val cache = R16SystemProjectionIndexCache()
        val index = cache.forSnapshot(snapshot)
        val state = R16SystemPlaybackProjector.projectCached(snapshot, emptyMap(), index)

        assertSame(index, cache.forSnapshot(snapshot.copy(position = PositionAnchor(1, 1))))
        assertEquals(
            expectedRecordingIds,
            R16SystemPlaybackProjector.presentationRecordingIds(snapshot, index),
        )
        assertEquals(adjacent, state.traversal.map(R16SystemQueueItem::queueEntryId))
        assertEquals(
            expectedRecordingIds,
            state.traversal.map(R16SystemQueueItem::recordingId).toSet(),
        )
    }

    private fun snapshot(first: QueueEntry, second: QueueEntry) =
        PlaybackSnapshot(
            generation = 1,
            queueRevision = 2,
            queue =
                QueueState(
                    baseQueue = listOf(first, second),
                    traversalOrder = listOf(second.id, first.id),
                    currentQueueEntryId = second.id,
                    shuffle = ShuffleState.On(9),
                ),
            committedQueueEntryId = second.id,
            phase = PlaybackPhase.Playing(second.id),
            playWhenReady = true,
            repeatMode = RepeatMode.ONE,
            position = PositionAnchor(4_000, 5_000, advancing = true),
            resolvedSources = emptyMap(),
            engineWindow = listOf(second.id, first.id),
            expectedEngineCommit = null,
            currentError = null,
        )

    private fun entry(id: String) =
        QueueEntry(
            id = QueueEntryId(id),
            recordingId = RecordingId(RECORDING_ONE),
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private class RecordingRouter : PlaybackCommandRouter {
        val commands = mutableListOf<PlaybackCommand>()

        override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult {
            commands += command
            return PlaybackCommandResult.Accepted(generation = 1, queueRevision = 1)
        }
    }

    private companion object {
        const val RECORDING_ONE = "00000000-0000-0000-0000-000000000001"
        const val QUEUE_ONE = "10000000-0000-0000-0000-000000000001"
        const val QUEUE_TWO = "10000000-0000-0000-0000-000000000002"
    }
}
