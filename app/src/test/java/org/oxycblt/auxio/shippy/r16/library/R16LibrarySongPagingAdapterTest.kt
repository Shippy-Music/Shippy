/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySongPagingAdapterTest.kt is part of Auxio.
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

import app.shippy.data.db.view.LibrarySongRowView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LibrarySongPagingAdapterTest {
    @Test
    fun `canonical recording ID is the Paging diff identity`() {
        val first = row(recordingId = "recording-1", title = "Before")
        val changed = first.copy(title = "After")
        val distinct = row(recordingId = "recording-2", title = "Before")

        assertTrue(R16_LIBRARY_SONG_DIFF.areItemsTheSame(first, changed))
        assertFalse(R16_LIBRARY_SONG_DIFF.areContentsTheSame(first, changed))
        assertFalse(R16_LIBRARY_SONG_DIFF.areItemsTheSame(first, distinct))
    }

    private fun row(recordingId: String, title: String) =
        LibrarySongRowView(
            recordingId = recordingId,
            title = title,
            artistDisplay = "Artist",
            releaseTitle = null,
            artworkLocation = null,
            durationMs = null,
            liked = false,
            localAssetExists = true,
            downloadAssetExists = false,
            lastPlayedAtEpochMs = null,
            dateAddedEpochMs = 1,
            titleSortKey = title.lowercase(),
            artistSortKey = "artist",
        )
}
