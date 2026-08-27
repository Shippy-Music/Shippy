/*
 * Copyright (c) 2026 Auxio Project
 * R16ContinueUiStateTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.home

import android.support.v4.media.MediaMetadataCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionProjection
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16ContinueUiStateTest {
    @Test
    fun `continue keeps exact media session identities`() {
        val state =
            R16ContinueUiStateMapper.map(
                MediaMetadataCompat.Builder()
                    .putText(MediaMetadataCompat.METADATA_KEY_TITLE, "Track")
                    .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, "Artist")
                    .putString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID, "queue-1")
                    .putString(R16MediaSessionProjection.KEY_RECORDING_ID, "recording-1")
                    .build()
            )

        assertEquals("queue-1", state?.queueEntryId)
        assertEquals("recording-1", state?.recordingId)
        assertEquals("Track - Artist", state?.label)
    }

    @Test
    fun `continue hides metadata without both canonical identities`() {
        assertNull(
            R16ContinueUiStateMapper.map(
                MediaMetadataCompat.Builder()
                    .putText(MediaMetadataCompat.METADATA_KEY_TITLE, "Track")
                    .putString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID, "queue-1")
                    .build()
            )
        )
    }
}
