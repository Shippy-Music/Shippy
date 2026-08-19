/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackRequestHeadersTest.kt is part of Auxio.
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
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestHeadersTest {
    @Test
    fun `owner replacement is bounded and does not clear another playback authority`() {
        val store = PlaybackRequestHeaders()
        store.replaceOwner("legacy", mapOf("legacy-key" to mapOf("A" to "one")))
        store.replaceOwner("r16", mapOf("r16-key" to mapOf("B" to "two")))

        assertEquals(mapOf("A" to "one"), store.headersForKey("legacy-key"))
        assertEquals(mapOf("B" to "two"), store.headersForKey("r16-key"))

        store.replaceOwner("r16", mapOf("new-key" to mapOf("C" to "three")))

        assertTrue(store.headersForKey("r16-key").isEmpty())
        assertEquals(mapOf("A" to "one"), store.headersForKey("legacy-key"))
        assertEquals(mapOf("C" to "three"), store.headersForKey("new-key"))
    }

    @Test
    fun `conflicting header ownership is rejected without mutating the prior projection`() {
        val store = PlaybackRequestHeaders()
        store.replaceOwner("legacy", mapOf("same-key" to mapOf("A" to "one")))

        val error =
            runCatching {
                    store.replaceOwner("r16", mapOf("same-key" to mapOf("A" to "different")))
                }
                .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(mapOf("A" to "one"), store.headersForKey("same-key"))
    }
}
