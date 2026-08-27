/*
 * Copyright (c) 2026 Auxio Project
 * LastFmAccountIdTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LastFmAccountIdTest {
    @Test
    fun `hash normalizes casing and whitespace`() {
        val expected = LastFmAccountId.hash("alice")
        assertEquals(expected, LastFmAccountId.hash("Alice"))
        assertEquals(expected, LastFmAccountId.hash("  ALICE  "))
        assertEquals(expected, LastFmAccountId.hash("alice\n"))
    }

    @Test
    fun `hash produces 64 character hex sha256`() {
        val hash = LastFmAccountId.hash("bob")
        assertEquals(64, hash.length)
        assertEquals("81b637d8fcd2c6da6359e6963113a1170de795e4b725b84d1e0b4cfd9ec58ce9", hash)
    }

    @Test
    fun `blank username is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LastFmAccountId.hash("") }
        assertThrows(IllegalArgumentException::class.java) { LastFmAccountId.hash("   ") }
    }
}
