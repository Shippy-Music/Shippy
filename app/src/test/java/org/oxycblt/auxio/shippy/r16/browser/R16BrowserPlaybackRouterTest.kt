/*
 * Copyright (c) 2026 Auxio Project
 * R16BrowserPlaybackRouterTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.browser

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.queue.QueueEntry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R16BrowserPlaybackRouterTest {
    @Test
    fun `media id shuffle seed reaches play context without changing selected occurrence`() =
        runBlocking {
            val resolution = ready("playlist")
            val dispatched = CompletableDeferred<PlaybackCommand.PlayContext>()
            val router =
                R16BrowserPlaybackRouter(
                    parentScope = this,
                    resolveMediaId = { resolution },
                    resolveSearch = { resolution },
                    commands =
                        object : PlaybackCommandRouter {
                            override suspend fun dispatch(
                                command: PlaybackCommand
                            ): PlaybackCommandResult {
                                dispatched.complete(command as PlaybackCommand.PlayContext)
                                return PlaybackCommandResult.Accepted(1, 1)
                            }
                        },
                )

            router.playFromMediaId("playlist", shuffleSeed = 7331L)
            val command = withTimeout(2_000) { dispatched.await() }

            assertEquals(7331L, command.shuffleSeed)
            assertEquals(resolution.selectedEntryId, command.selectedEntryId)
            router.release()
        }

    @Test
    fun `latest callback wins and dispatches exact selected occurrence`() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val dispatched = mutableListOf<PlaybackCommand>()
        val results = mutableListOf<R16BrowserPlaybackRoutingResult>()
        val router =
            R16BrowserPlaybackRouter(
                parentScope = this,
                resolveMediaId = { mediaId ->
                    if (mediaId == "slow") slow.await()
                    ready(mediaId)
                },
                resolveSearch = { ready(it) },
                commands =
                    object : PlaybackCommandRouter {
                        override suspend fun dispatch(
                            command: PlaybackCommand
                        ): PlaybackCommandResult {
                            dispatched += command
                            return PlaybackCommandResult.Accepted(1, 1)
                        }
                    },
                onResult = results::add,
            )

        router.playFromMediaId("slow")
        router.playFromSearch("new")
        withTimeout(2_000) { while (results.isEmpty()) delay(1) }
        slow.complete(Unit)
        delay(10)

        assertEquals(1, dispatched.size)
        val command = dispatched.single() as PlaybackCommand.PlayContext
        val result = results.single() as R16BrowserPlaybackRoutingResult.Dispatched
        assertEquals(result.resolution.selectedEntryId, command.selectedEntryId)
        assertEquals(result.resolution.selectedRecordingId, command.entries.single().recordingId)
        router.release()
    }

    @Test
    fun `blank and rejected requests never dispatch`() = runBlocking {
        var dispatches = 0
        val results = mutableListOf<R16BrowserPlaybackRoutingResult>()
        val router =
            R16BrowserPlaybackRouter(
                parentScope = this,
                resolveMediaId = {
                    R16BrowserQueueResolution.Rejected(
                        it,
                        R16BrowserQueueRejection.MALFORMED_MEDIA_ID,
                    )
                },
                resolveSearch = { error("Blank search must not resolve") },
                commands =
                    object : PlaybackCommandRouter {
                        override suspend fun dispatch(
                            command: PlaybackCommand
                        ): PlaybackCommandResult {
                            dispatches++
                            return PlaybackCommandResult.Accepted(1, 1)
                        }
                    },
                onResult = results::add,
            )

        router.playFromSearch("  ")
        router.playFromMediaId("bad")
        withTimeout(2_000) { while (results.size < 2) delay(1) }

        assertEquals(0, dispatches)
        assertTrue(results.first() is R16BrowserPlaybackRoutingResult.InvalidRequest)
        assertTrue(results.last() is R16BrowserPlaybackRoutingResult.Rejected)
        router.release()
    }

    private fun ready(seed: String): R16BrowserQueueResolution.Ready {
        val recordingId = RecordingId(uuid("recording-$seed"))
        val entry =
            QueueEntry(
                id = QueueEntryId(uuid("entry-$seed")),
                recordingId = recordingId,
                origin = null,
                playlistEntryId = null,
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        return R16BrowserQueueResolution.Ready(seed, listOf(entry), entry.id)
    }

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}
