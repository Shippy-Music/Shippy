/*
 * Copyright (c) 2026 Auxio Project
 * LastFmReauthStateTest.kt is part of Auxio.
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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmReauthStateTest {
    @Test
    fun `delivery signal is process local and clears only explicitly`() {
        val state = LastFmReauthState()

        assertFalse(state.required.value)
        state.markRequired()
        assertTrue(state.required.value)
        state.clear()
        assertFalse(state.required.value)
    }
}
