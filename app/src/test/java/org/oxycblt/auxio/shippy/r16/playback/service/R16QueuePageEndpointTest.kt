/*
 * Copyright (c) 2026 Auxio Project
 * R16QueuePageEndpointTest.kt is part of Auxio.
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
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.PositionAnchor
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16RecordingPresentation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R16QueuePageEndpointTest {
    @Test
    fun `page clamps to fifty and preserves traversal occurrence order and duplicates`() =
        runBlocking {
            val recording = recordingId(1)
            val first = entry(1, recording)
            val duplicate = entry(2, recording)
            val third = entry(3, recordingId(2))
            val snapshot =
                snapshot(
                    entries = listOf(first, duplicate, third),
                    traversal = listOf(third.id, duplicate.id, first.id),
                    current = duplicate.id,
                    revision = 7,
                )
            val presentations = RecordingPresentations()
            val endpoint =
                R16QueuePageEndpoint(MutableStateFlow(snapshot), RecordingRouter(), presentations)

            val result = endpoint.page(R16QueuePageRequest(offset = 0, limit = 500))

            val page = (result as R16QueueCommandResult.Page).page
            assertEquals(50, page.limit)
            assertEquals(
                listOf(third.id, duplicate.id, first.id),
                page.items.map { it.queueEntryId },
            )
            assertEquals(
                listOf(third.recordingId, duplicate.recordingId, first.recordingId),
                page.items.map { it.recordingId },
            )
            assertEquals(duplicate.id, page.currentQueueEntryId)
            assertEquals(1, page.currentIndex)
            assertEquals(setOf(third.recordingId, recording), presentations.requested)
        }

    @Test
    fun `null offset anchors the page around the current occurrence`() = runBlocking {
        val entries = (0 until 105).map { entry(it + 10, recordingId(10_000 + it)) }
        val current = entries[99]
        val snapshot = snapshot(entries, entries.map(QueueEntry::id), current.id, revision = 4)
        val presentations = RecordingPresentations()
        val endpoint =
            R16QueuePageEndpoint(MutableStateFlow(snapshot), RecordingRouter(), presentations)

        val page = (endpoint.page(R16QueuePageRequest()) as R16QueueCommandResult.Page).page

        assertEquals(50, page.offset)
        assertEquals(50, page.items.size)
        assertEquals(50, presentations.requested.size)
        assertEquals(49, page.currentIndex)
        assertEquals(0, page.previousOffset)
        assertEquals(100, page.nextOffset)
        assertTrue(page.hasPrevious)
        assertTrue(page.hasNext)
    }

    @Test
    fun `stale page and missing goto fail closed without routing`() = runBlocking {
        val selected = entry(20, recordingId(20))
        val snapshot = snapshot(listOf(selected), listOf(selected.id), selected.id, revision = 9)
        val router = RecordingRouter()
        val endpoint = R16QueuePageEndpoint(MutableStateFlow(snapshot), router)

        val stale = endpoint.page(R16QueuePageRequest(offset = 0, expectedQueueRevision = 8))
        val missing =
            endpoint.goTo(
                R16QueueGoToRequest(
                    queueEntryId = QueueEntryId(uuid(999)),
                    expectedQueueRevision = 9,
                )
            )

        assertEquals(
            R16QueueRejection.STALE_QUEUE_REVISION,
            (stale as R16QueueCommandResult.Rejected).reason,
        )
        assertEquals(
            R16QueueRejection.ENTRY_NOT_FOUND,
            (missing as R16QueueCommandResult.Rejected).reason,
        )
        assertTrue(router.commands.isEmpty())
    }

    @Test
    fun `goto routes exact queue occurrence`() = runBlocking {
        val first = entry(30, recordingId(30))
        val second = entry(31, first.recordingId)
        val snapshot = snapshot(listOf(first, second), listOf(second.id, first.id), second.id, 12)
        val router = RecordingRouter()
        val endpoint = R16QueuePageEndpoint(MutableStateFlow(snapshot), router)

        endpoint.goTo(R16QueueGoToRequest(second.id, expectedQueueRevision = 12))

        assertEquals(listOf(PlaybackCommand.GoTo(second.id)), router.commands)
    }

    @Test
    fun `move routes exact queue occurrence and anchors`() = runBlocking {
        val first = entry(40, recordingId(40))
        val second = entry(41, first.recordingId)
        val snapshot = snapshot(listOf(first, second), listOf(first.id, second.id), first.id, 14)
        val router = RecordingRouter()
        val endpoint = R16QueuePageEndpoint(MutableStateFlow(snapshot), router)

        val anchor = app.shippy.core.queue.QueueAnchor(before = first.id, after = null)
        endpoint.move(R16QueueMoveRequest(second.id, anchor, expectedQueueRevision = 14))

        assertEquals(listOf(PlaybackCommand.Move(second.id, anchor)), router.commands)
    }

    @Test
    fun `moveTop and moveBottom target global queue boundaries on paged queue`() = runBlocking {
        val entries = (0 until 100).map { entry(it + 100, recordingId(20_000 + it)) }
        val snapshot = snapshot(entries, entries.map(QueueEntry::id), entries[0].id, 16)
        val router = RecordingRouter()
        val endpoint = R16QueuePageEndpoint(MutableStateFlow(snapshot), router)

        val targetEntry = entries[50]
        endpoint.move(
            R16QueueMoveRequest(
                queueEntryId = targetEntry.id,
                anchor = app.shippy.core.queue.QueueAnchor(null, null),
                expectedQueueRevision = 16,
                moveTop = true,
            )
        )

        val expectedTopAnchor =
            app.shippy.core.queue.QueueAnchor(before = null, after = entries[0].id)
        assertEquals(
            listOf(PlaybackCommand.Move(targetEntry.id, expectedTopAnchor)),
            router.commands,
        )

        router.commands.clear()

        endpoint.move(
            R16QueueMoveRequest(
                queueEntryId = targetEntry.id,
                anchor = app.shippy.core.queue.QueueAnchor(null, null),
                expectedQueueRevision = 16,
                moveBottom = true,
            )
        )

        val expectedBottomAnchor =
            app.shippy.core.queue.QueueAnchor(before = entries[99].id, after = null)
        assertEquals(
            listOf(PlaybackCommand.Move(targetEntry.id, expectedBottomAnchor)),
            router.commands,
        )
    }

    @Test
    fun `remove routes exact set of queue occurrences`() = runBlocking {
        val first = entry(50, recordingId(50))
        val second = entry(51, first.recordingId)
        val snapshot = snapshot(listOf(first, second), listOf(first.id, second.id), first.id, 15)
        val router = RecordingRouter()
        val endpoint = R16QueuePageEndpoint(MutableStateFlow(snapshot), router)

        endpoint.remove(R16QueueRemoveRequest(setOf(first.id), expectedQueueRevision = 15))

        assertEquals(listOf(PlaybackCommand.Remove(setOf(first.id))), router.commands)
    }

    private class RecordingPresentations : R16PlaybackPresentationRepository {
        var requested: Set<RecordingId> = emptySet()

        override fun observe(
            recordingIds: Set<RecordingId>
        ): Flow<Map<RecordingId, R16RecordingPresentation>> {
            requested = recordingIds
            return flowOf(
                recordingIds.associateWith { id ->
                    R16RecordingPresentation(id, "Title", "Artist", null, null, null)
                }
            )
        }
    }

    private class RecordingRouter : PlaybackCommandRouter {
        val commands = mutableListOf<PlaybackCommand>()

        override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult {
            commands += command
            return PlaybackCommandResult.Accepted(generation = 2, queueRevision = 12)
        }
    }

    private fun snapshot(
        entries: List<QueueEntry>,
        traversal: List<QueueEntryId>,
        current: QueueEntryId?,
        revision: Long,
    ) =
        PlaybackSnapshot(
            generation = 1,
            queueRevision = revision,
            queue = QueueState(entries, traversal, current, ShuffleState.On(1)),
            committedQueueEntryId = current,
            phase = current?.let(PlaybackPhase::Playing) ?: PlaybackPhase.Idle,
            playWhenReady = false,
            repeatMode = RepeatMode.OFF,
            position = PositionAnchor(0, 0),
            resolvedSources = emptyMap(),
            engineWindow = current?.let(::listOf).orEmpty(),
            expectedEngineCommit = null,
            currentError = null,
        )

    private fun entry(index: Int, recordingId: RecordingId) =
        QueueEntry(
            id = QueueEntryId(uuid(index)),
            recordingId = recordingId,
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun recordingId(index: Int) = RecordingId(uuid(10_000 + index))

    private fun uuid(index: Int) =
        UUID.nameUUIDFromBytes("r16-queue-$index".toByteArray()).toString()
}
