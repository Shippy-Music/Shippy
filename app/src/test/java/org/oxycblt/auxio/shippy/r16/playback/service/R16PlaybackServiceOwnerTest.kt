/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackServiceOwnerTest.kt is part of Auxio.
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
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class R16PlaybackServiceOwnerTest {
    @Test
    fun `owner attaches spine before surfaces and releases in reverse order once`() = runBlocking {
        val events = mutableListOf<String>()
        val spine = FakeSpine(events)
        val surfaces = FakeSurfaces(events)
        val owner = R16PlaybackServiceOwner(spine, surfaces)

        owner.attach(allowResume = true)
        owner.attach()
        owner.release()
        owner.release()

        assertEquals(
            listOf("spine.attach:true", "surfaces.attach", "surfaces.release", "spine.release"),
            events,
        )
        assertEquals(R16PlaybackServiceOwnerLifecycle.RELEASED, owner.lifecycle)
    }

    @Test
    fun `task removal retains an active session and releases an inactive one`() = runBlocking {
        val activeEvents = mutableListOf<String>()
        val activeEntry =
            QueueEntry(
                id = QueueEntryId(ID),
                recordingId = RecordingId(RECORDING_ID),
                origin = null,
                playlistEntryId = null,
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        val activeSpine =
            FakeSpine(activeEvents).apply {
                snapshots.value =
                    PlaybackSnapshot.Empty.copy(
                        queue =
                            QueueState(
                                listOf(activeEntry),
                                listOf(activeEntry.id),
                                activeEntry.id,
                                ShuffleState.Off,
                            ),
                        committedQueueEntryId = activeEntry.id,
                        phase = PlaybackPhase.Playing(activeEntry.id),
                        playWhenReady = true,
                    )
            }
        val activeOwner = R16PlaybackServiceOwner(activeSpine, FakeSurfaces(activeEvents))
        activeOwner.attach()

        assertFalse(activeOwner.handleTaskRemoved(exitOnTaskRemoval = false, hasActiveCrew = false))
        assertEquals(R16PlaybackServiceOwnerLifecycle.ATTACHED, activeOwner.lifecycle)

        val inactiveEvents = mutableListOf<String>()
        val inactiveOwner =
            R16PlaybackServiceOwner(FakeSpine(inactiveEvents), FakeSurfaces(inactiveEvents))
        inactiveOwner.attach()

        assertTrue(inactiveOwner.handleTaskRemoved(exitOnTaskRemoval = true, hasActiveCrew = false))
        assertEquals(
            listOf("spine.attach:false", "surfaces.attach", "surfaces.release", "spine.release"),
            inactiveEvents,
        )
    }

    @Test
    fun `surface attach failure releases the spine and cannot be retried`() = runBlocking {
        val events = mutableListOf<String>()
        val owner =
            R16PlaybackServiceOwner(FakeSpine(events), FakeSurfaces(events, attachFailure = true))

        try {
            owner.attach()
            fail("Expected surface attach failure")
        } catch (_: IllegalStateException) {
            // Expected: the owner must not retain an authority after a surface attach failure.
        }

        assertEquals(
            listOf("spine.attach:false", "surfaces.attach", "surfaces.release", "spine.release"),
            events,
        )
        assertEquals(R16PlaybackServiceOwnerLifecycle.RELEASED, owner.lifecycle)
    }

    private class FakeSpine(private val events: MutableList<String>) :
        R16PlaybackServiceOwnerSpine {
        override val snapshots = MutableStateFlow(PlaybackSnapshot.Empty)

        override suspend fun attach(allowResume: Boolean) {
            events += "spine.attach:$allowResume"
        }

        override suspend fun release() {
            events += "spine.release"
        }
    }

    private class FakeSurfaces(
        private val events: MutableList<String>,
        private val attachFailure: Boolean = false,
    ) : R16PlaybackServiceOwnerSurfaces {
        override fun attach() {
            events += "surfaces.attach"
            if (attachFailure) throw IllegalStateException("surface failure")
        }

        override fun release() {
            events += "surfaces.release"
        }
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val RECORDING_ID = "00000000-0000-0000-0000-000000000002"
    }
}
