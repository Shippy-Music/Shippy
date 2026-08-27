/*
 * Copyright (c) 2026 Auxio Project
 * R16ContinueUiState.kt is part of Auxio.
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
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionProjection

/** The current item as exposed by the sole R16 MediaSession authority. */
internal data class R16ContinueUiState(
    val queueEntryId: String,
    val recordingId: String,
    val title: CharSequence,
    val artist: CharSequence,
) {
    val label: CharSequence
        get() = listOf(title, artist).filter(CharSequence::isNotBlank).joinToString(" - ")
}

internal object R16ContinueUiStateMapper {
    fun map(metadata: MediaMetadataCompat?): R16ContinueUiState? {
        val queueEntryId = metadata?.getString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID)
        val recordingId = metadata?.getString(R16MediaSessionProjection.KEY_RECORDING_ID)
        val title =
            metadata
                ?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
                ?.takeIf(CharSequence::isNotBlank)
                ?: metadata
                    ?.getText(MediaMetadataCompat.METADATA_KEY_TITLE)
                    ?.takeIf(CharSequence::isNotBlank)
                ?: return null
        if (queueEntryId.isNullOrBlank() || recordingId.isNullOrBlank()) return null
        val artist =
            metadata
                ?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                ?.takeIf(CharSequence::isNotBlank)
                ?: metadata?.getText(MediaMetadataCompat.METADATA_KEY_ARTIST)
                ?: ""
        return R16ContinueUiState(queueEntryId, recordingId, title, artist)
    }
}
