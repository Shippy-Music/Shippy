/*
 * Copyright (c) 2026 Auxio Project
 * QueueMovePlanTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueMovePlanTest {
    @Test
    fun normalQueueMovesOnlyRequestedItemUp() {
        assertEquals(
            QueueMovePlan.MediaItem(from = 4, to = 1),
            planQueueMove(listOf(0, 1, 2, 3, 4, 5), from = 4, to = 1, shuffled = false),
        )
    }

    @Test
    fun normalQueueMovesOnlyRequestedItemDown() {
        assertEquals(
            QueueMovePlan.MediaItem(from = 1, to = 4),
            planQueueMove(listOf(0, 1, 2, 3, 4, 5), from = 1, to = 4, shuffled = false),
        )
    }

    @Test
    fun shuffledQueueMovesOnlyRequestedItemUp() {
        assertEquals(
            QueueMovePlan.ShuffleOrder(listOf(2, 1, 4, 0, 3, 5)),
            planQueueMove(listOf(2, 4, 0, 3, 1, 5), from = 4, to = 1, shuffled = true),
        )
    }

    @Test
    fun shuffledQueueMovesOnlyRequestedItemDown() {
        assertEquals(
            QueueMovePlan.ShuffleOrder(listOf(2, 0, 3, 1, 4, 5)),
            planQueueMove(listOf(2, 4, 0, 3, 1, 5), from = 1, to = 4, shuffled = true),
        )
    }

    @Test
    fun invalidOrUnchangedMoveDoesNothing() {
        val queue = listOf(0, 1, 2)

        assertNull(planQueueMove(queue, from = 1, to = 1, shuffled = false))
        assertNull(planQueueMove(queue, from = -1, to = 1, shuffled = false))
        assertNull(planQueueMove(queue, from = 1, to = 3, shuffled = true))
    }
}
