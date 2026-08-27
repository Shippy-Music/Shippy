/*
 * Copyright (c) 2026 Auxio Project
 * R16NowPlayingUiStateMapperTest.kt is part of Auxio.
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionProjection
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16NowPlayingUiStateMapperTest {
    @Test
    fun `empty session stays hidden`() {
        assertEquals(
            R16NowPlayingUiState.Hidden,
            R16NowPlayingUiStateMapper.map(MediaMetadataCompat.Builder().build(), null, 100),
        )
    }

    @Test
    fun `session metadata maps canonical identity and smooth playing progress`() {
        val metadata =
            MediaMetadataCompat.Builder()
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Yellow")
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Coldplay")
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "Parachutes")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 300_000)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ART_URI,
                    "https://art.example/yellow.jpg",
                )
                .putString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID, "queue-1")
                .putString(R16MediaSessionProjection.KEY_RECORDING_ID, "recording-1")
                .build()
        val state =
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 1_000, 1f, 10_000)
                .build()

        val rendered = R16NowPlayingUiStateMapper.map(metadata, state, 12_500)

        assertEquals(R16NowPlayingPhase.Playing, rendered.phase)
        assertEquals("queue-1", rendered.queueEntryId)
        assertEquals("recording-1", rendered.recordingId)
        assertEquals(3_500, rendered.progressMs)
        assertEquals("Parachutes", rendered.release)
        assertEquals("https://art.example/yellow.jpg", rendered.artworkLocation)
    }

    @Test
    fun `session error exposes current failure`() {
        val metadata =
            MediaMetadataCompat.Builder()
                .putText(MediaMetadataCompat.METADATA_KEY_TITLE, "Yellow")
                .build()
        val state =
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage("Resolver failed")
                .build()

        val rendered = R16NowPlayingUiStateMapper.map(metadata, state, 0)

        assertEquals(R16NowPlayingPhase.Failed, rendered.phase)
        assertTrue(rendered.visible)
        assertEquals("Resolver failed", rendered.failureMessage)
    }
}
