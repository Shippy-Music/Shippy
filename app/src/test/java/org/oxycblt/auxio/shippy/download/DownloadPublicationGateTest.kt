/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadPublicationGateTest.kt is part of Shippy.
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

        val first =
            async {
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
