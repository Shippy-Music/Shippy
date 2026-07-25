/*
 * Copyright (c) 2026 Shippy contributors
 * CrewTransportTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewTransportTest {
    @Test
    fun `wire labels are unique and round trip`() {
        val labels = CrewTransportChannel.entries.map { it.wireLabel }

        assertEquals(labels.size, labels.distinct().size)
        CrewTransportChannel.entries.forEach {
            assertEquals(it, CrewTransportChannel.fromWireLabel(it.wireLabel))
        }
        assertEquals(null, CrewTransportChannel.fromWireLabel("unknown"))
    }

    @Test
    fun `control and media are reliable while transient channels are lossy`() {
        assertTrue(CrewTransportChannel.CONTROL.ordered)
        assertEquals(null, CrewTransportChannel.CONTROL.maxRetransmits)
        assertTrue(CrewTransportChannel.MEDIA.ordered)
        assertEquals(null, CrewTransportChannel.MEDIA.maxRetransmits)
        assertFalse(CrewTransportChannel.CLOCK.ordered)
        assertEquals(0, CrewTransportChannel.CLOCK.maxRetransmits)
        assertFalse(CrewTransportChannel.REACTION.ordered)
        assertEquals(0, CrewTransportChannel.REACTION.maxRetransmits)
    }

    @Test
    fun `frame snapshots mutable caller bytes and redacts payload`() {
        val source = byteArrayOf(1, 2, 3)
        val frame = CrewTransportFrame(CrewTransportChannel.CONTROL, source)
        source[0] = 9

        assertEquals(1, frame.copyPayload()[0].toInt())
        assertFalse(frame.toString().contains("[1, 2, 3]"))
        assertEquals(
            CrewTransportFrame(CrewTransportChannel.CONTROL, byteArrayOf(1, 2, 3)),
            frame,
        )
        assertNotEquals(
            CrewTransportFrame(CrewTransportChannel.CONTROL, byteArrayOf(1, 2, 4)),
            frame,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `channel payload bound is enforced`() {
        CrewTransportFrame(
            CrewTransportChannel.REACTION,
            ByteArray(CrewTransportChannel.REACTION.maxPayloadBytes + 1),
        )
    }
}
