/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackQueueItemIdentityTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.QueueItemId

class PlaybackQueueItemIdentityTest {
    @Test
    fun staleAsyncResultForSameTrackDifferentOccurrenceIsRejected() {
        val oldOccurrence = QueueItemId("occurrence-a")
        val currentOccurrence = QueueItemId("occurrence-b")

        assertFalse(isCurrentQueueItem(currentOccurrence, oldOccurrence))
        assertTrue(isCurrentQueueItem(currentOccurrence, currentOccurrence))
    }
}
