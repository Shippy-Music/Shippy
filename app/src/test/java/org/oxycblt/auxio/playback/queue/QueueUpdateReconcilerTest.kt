/*
 * Copyright (c) 2026 Auxio Project
 * QueueUpdateReconcilerTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.shippy.domain.QueueItemId

class QueueUpdateReconcilerTest {
    private val a = QueueItemId("a")
    private val b = QueueItemId("b")
    private val c = QueueItemId("c")
    private val d = QueueItemId("d")

    @Test
    fun canonicalAckDoesNotReplayPreviewedMove() {
        val final = listOf(d, a, b, c)

        assertNull(
            reconcileQueueUpdate(
                current = final,
                next = final,
                requested = UpdateInstructions.Move(3, 0),
            )
        )
    }

    @Test
    fun unappliedCanonicalMoveIsPreserved() {
        val move = UpdateInstructions.Move(3, 0)

        assertEquals(
            move,
            reconcileQueueUpdate(
                current = listOf(a, b, c, d),
                next = listOf(d, a, b, c),
                requested = move,
            ),
        )
    }

    @Test
    fun staleMoveFallsBackToDiff() {
        assertEquals(
            UpdateInstructions.Diff,
            reconcileQueueUpdate(
                current = listOf(a, b, c, d),
                next = listOf(a, d, b, c),
                requested = UpdateInstructions.Move(3, 0),
            ),
        )
    }

    @Test
    fun anchorsUseActualFinalIdentityPosition() {
        assertEquals(
            QueueMoveAnchors(beforeId = b, afterId = a),
            queueMoveAnchors(listOf(a, d, b, c), d),
        )
    }
}
