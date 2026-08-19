/*
 * Copyright (c) 2026 Auxio Project
 * EnrichmentCoordinatorTest.kt is part of Auxio.
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
package app.shippy.sources.enrichment

import app.shippy.core.identity.RecordingId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentCoordinatorTest {
    @Test
    fun `playback and durable intent share unique identity while durable intent upgrades work`() =
        runBlocking {
            val scheduler = FakeScheduler()
            val coordinator = EnrichmentCoordinator(scheduler)
            val recordingId = recordingId(1)

            coordinator.afterPlaybackCommit(recordingId)
            coordinator.afterDurableIntent(recordingId)

            assertEquals(2, scheduler.enqueued.size)
            assertEquals(scheduler.enqueued[0].uniqueName, scheduler.enqueued[1].uniqueName)
            assertEquals("enrich-recording:${recordingId.value}", scheduler.enqueued[0].uniqueName)
            assertFalse(scheduler.enqueued[0].replaceExisting)
            assertTrue(scheduler.enqueued[1].replaceExisting)
            assertFalse(scheduler.enqueued[1].requiresUnmeteredNetwork)
        }

    @Test
    fun `idle enrichment is deterministic bounded and unmetered`() = runBlocking {
        val scheduler = FakeScheduler()
        val coordinator = EnrichmentCoordinator(scheduler)

        coordinator.scheduleIdle(
            listOf(recordingId(3), recordingId(1), recordingId(2), recordingId(1)),
            limit = 2,
        )

        assertEquals(
            listOf(recordingId(1), recordingId(2)),
            scheduler.enqueued.map { it.recordingId },
        )
        assertTrue(scheduler.enqueued.all { it.requiresUnmeteredNetwork })
        assertTrue(scheduler.enqueued.none { it.replaceExisting })
    }

    private fun recordingId(index: Int) =
        RecordingId("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
}

private class FakeScheduler : EnrichmentWorkScheduler {
    val enqueued = mutableListOf<EnrichmentWork>()

    override suspend fun enqueue(work: EnrichmentWork) {
        enqueued += work
    }

    override suspend fun cancel(recordingId: RecordingId) = Unit

    override fun observe(recordingId: RecordingId): Flow<EnrichmentWorkState> =
        flowOf(EnrichmentWorkState.ENQUEUED)
}
