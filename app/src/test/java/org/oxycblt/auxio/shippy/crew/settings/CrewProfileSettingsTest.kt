/*
 * Copyright (c) 2026 Auxio Project
 * CrewProfileSettingsTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewProfileSettingsTest {
    @Test
    fun `normalizes surrounding and repeated whitespace`() {
        assertEquals("Shippy listener", normalizeCrewDisplayName("  Shippy\t listener  "))
    }

    @Test
    fun `rejects blank display names`() {
        assertInvalid(" \n\t ")
    }

    @Test
    fun `enforces the UTF-8 byte limit`() {
        val fourByteCharacter = "\uD83D\uDE42"

        assertEquals(
            fourByteCharacter.repeat(20),
            normalizeCrewDisplayName(fourByteCharacter.repeat(20)),
        )
        assertInvalid(fourByteCharacter.repeat(21))
    }

    @Test
    fun `wraps one stable member value for each protocol version`() {
        val value = "8aa94487-d443-4ca9-9aeb-939db23b78de"

        assertEquals(
            CrewMemberId(value, ProtocolVersion(1)),
            crewMemberId(value, ProtocolVersion(1)),
        )
        assertEquals(value, crewMemberId(value, ProtocolVersion(2)).value)
    }

    private fun assertInvalid(value: String) {
        try {
            normalizeCrewDisplayName(value)
            fail("Expected display name validation to fail")
        } catch (_: IllegalArgumentException) {}
    }
}
