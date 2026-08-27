/*
 * Copyright (c) 2026 Auxio Project
 * R16MiniPlayerUiStateMapperTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MiniPlayerUiStateMapperTest {
    @Test
    fun `empty session stays hidden`() {
        assertEquals(
            R16MiniPlayerUiState.Hidden,
            R16MiniPlayerUiStateMapper.map(MediaMetadataCompat.Builder().build(), null),
        )
    }

    @Test
    fun `canonical metadata renders buffering paused and playing`() {
        val metadata =
            MediaMetadataCompat.Builder()
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Yellow")
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Coldplay")
                .build()

        assertEquals(
            R16MiniPlayerPhase.Buffering,
            map(metadata, PlaybackStateCompat.STATE_BUFFERING).phase,
        )
        assertEquals(
            R16MiniPlayerPhase.Paused,
            map(metadata, PlaybackStateCompat.STATE_PAUSED).phase,
        )
        assertEquals(
            R16MiniPlayerPhase.Playing,
            map(metadata, PlaybackStateCompat.STATE_PLAYING).phase,
        )
        assertEquals("Yellow", map(metadata, PlaybackStateCompat.STATE_PLAYING).title)
        assertEquals("Coldplay", map(metadata, PlaybackStateCompat.STATE_PLAYING).artist)
    }

    private fun map(metadata: MediaMetadataCompat, state: Int): R16MiniPlayerUiState =
        R16MiniPlayerUiStateMapper.map(
            metadata,
            PlaybackStateCompat.Builder().setState(state, 0, 1f).build(),
        )
}
