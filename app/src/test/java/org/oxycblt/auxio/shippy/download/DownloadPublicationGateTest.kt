/*
 * Copyright (c) 2026 Auxio Project
 * DownloadPublicationGateTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadPublicationGateTest {
    @Test
    fun `publication work is serialized across stores`() = runBlocking {
        val gate = DownloadPublicationGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            gate.run {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = async { gate.run { order += "second" } }
        yield()

        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first-start", "first-end", "second"), order)
    }
}
