/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistDetailViewModelTest.kt is part of Auxio.
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

import app.shippy.core.identity.PlaylistEntryId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LibraryPlaylistDetailViewModelTest {
    @Test
    fun `batch moves require edit order and an unfiltered playlist`() {
        assertFalse(canMoveSelectedPlaylistEntries(editOrderMode = false, isFiltering = false))
        assertFalse(canMoveSelectedPlaylistEntries(editOrderMode = true, isFiltering = true))
        assertTrue(canMoveSelectedPlaylistEntries(editOrderMode = true, isFiltering = false))
    }

    @Test
    fun `batch moves preserve loaded occurrence order including duplicate recordings`() {
        val firstOccurrence = playlistEntryId("entry-1")
        val duplicateOccurrence = playlistEntryId("entry-2")
        val thirdOccurrence = playlistEntryId("entry-3")

        assertEquals(
            listOf(firstOccurrence, duplicateOccurrence),
            selectedPlaylistEntryIdsInOrder(
                selectedEntryIds = setOf(duplicateOccurrence, firstOccurrence),
                loadedEntryIdsInOrder =
                    listOf(firstOccurrence, duplicateOccurrence, thirdOccurrence),
            ),
        )
    }

    private fun playlistEntryId(seed: String): PlaylistEntryId =
        PlaylistEntryId(UUID.nameUUIDFromBytes(seed.toByteArray()).toString())
}
