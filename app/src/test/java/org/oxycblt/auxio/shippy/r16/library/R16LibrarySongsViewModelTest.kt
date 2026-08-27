/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySongsViewModelTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import app.shippy.data.library.R16LibrarySongQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class R16LibrarySongsViewModelTest {
    @Test
    fun `blank Library query remains the normal Songs page`() {
        assertEquals(R16LibrarySongQuery(), librarySongsQuery(null))
        assertEquals(R16LibrarySongQuery(), librarySongsQuery(""))
        assertEquals(R16LibrarySongQuery(), librarySongsQuery("  \t "))
    }

    @Test
    fun `Library query is trimmed before a new Paging source is requested`() {
        assertEquals(R16LibrarySongQuery("river dreams"), librarySongsQuery("  river dreams  "))
    }

    @Test
    fun `Songs Paging starts with bounded rows and no placeholders`() {
        assertEquals(50, R16_LIBRARY_SONGS_PAGING_CONFIG.pageSize)
        assertFalse(R16_LIBRARY_SONGS_PAGING_CONFIG.enablePlaceholders)
    }
}
