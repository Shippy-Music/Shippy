/*
 * Copyright (c) 2026 Auxio Project
 * CrewClockSyncTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewClockSyncTest {
    @Test
    fun `probe estimates coordinator offset and excludes coordinator processing`() {
        val sample =
            requireNotNull(
                CrewClockProbe(
                        clientSentMs = 1_000,
                        coordinatorReceivedMs = 1_120,
                        coordinatorSentMs = 1_130,
                        clientReceivedMs = 1_050,
                    )
                    .sample()
            )

        assertEquals(100.0, sample.offsetMs, 0.001)
        assertEquals(40L, sample.roundTripMs)
    }

    @Test
    fun `impossible negative network round trip is discarded`() {
        assertNull(
            CrewClockProbe(
                    clientSentMs = 100,
                    coordinatorReceivedMs = 200,
                    coordinatorSentMs = 250,
                    clientReceivedMs = 120,
                )
                .sample()
        )
    }

    @Test
    fun `estimator ignores high latency outlier`() {
        val estimate =
            requireNotNull(
                CrewClockEstimator.estimate(
                    listOf(
                        probe(offsetMs = 100, networkEachWayMs = 10),
                        probe(offsetMs = 102, networkEachWayMs = 12),
                        probe(offsetMs = 98, networkEachWayMs = 14),
                        probe(offsetMs = 900, networkEachWayMs = 500),
                    )
                )
            )

        assertEquals(100.0, estimate.coordinatorMinusClientMs, 0.001)
        assertEquals(3, estimate.sampleCount)
        assertEquals(14L, estimate.uncertaintyMs)
    }

    @Test
    fun `scheduled future start waits in client monotonic time`() {
        val decision =
            schedulePlayback(
                coordinatorTargetMs = 2_000,
                basePositionMs = 0,
                clientNowMs = 1_700,
                clock = CrewClockEstimate(100.0, 10, 3),
            )

        assertEquals(ScheduledPlaybackDecision.Wait(200), decision)
    }

    @Test
    fun `late member starts at calculated live position`() {
        val decision =
            schedulePlayback(
                coordinatorTargetMs = 2_000,
                basePositionMs = 500,
                clientNowMs = 2_050,
                clock = CrewClockEstimate(100.0, 10, 3),
            )

        assertEquals(ScheduledPlaybackDecision.Start(650), decision)
    }

    @Test
    fun `drift policy ignores corrects and seeks deterministically`() {
        val policy =
            CrewDriftPolicy(
                ignoredDriftMs = 50,
                speedCorrectionLimitMs = 500,
                speedCorrectionFraction = 0.03,
            )

        assertEquals(CrewDriftDecision.InSync, correctPlaybackDrift(1_000, 1_040, true, policy))
        val behind = correctPlaybackDrift(1_000, 800, true, policy)
        assertTrue(behind is CrewDriftDecision.CorrectSpeed)
        assertEquals(1.03f, (behind as CrewDriftDecision.CorrectSpeed).playbackRate, 0.0001f)
        val ahead = correctPlaybackDrift(1_000, 1_200, true, policy)
        assertEquals(0.97f, (ahead as CrewDriftDecision.CorrectSpeed).playbackRate, 0.0001f)
        assertEquals(
            CrewDriftDecision.Seek(1_000),
            correctPlaybackDrift(1_000, 1_800, true, policy),
        )
        assertEquals(
            CrewDriftDecision.Seek(1_000),
            correctPlaybackDrift(1_000, 1_200, false, policy),
        )
    }

    private fun probe(offsetMs: Long, networkEachWayMs: Long): CrewClockProbe {
        val clientSent = 1_000L
        val coordinatorReceived = clientSent + offsetMs + networkEachWayMs
        val coordinatorSent = coordinatorReceived + 2
        val clientReceived = coordinatorSent - offsetMs + networkEachWayMs
        return CrewClockProbe(
            clientSentMs = clientSent,
            coordinatorReceivedMs = coordinatorReceived,
            coordinatorSentMs = coordinatorSent,
            clientReceivedMs = clientReceived,
        )
    }
}
