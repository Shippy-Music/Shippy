/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemSurfaceRuntime.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.system

import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import org.oxycblt.auxio.ForegroundServiceNotification

/** One attach/release boundary for the inactive R16 MediaSession, widget, and receiver surfaces. */
class R16SystemSurfaceRuntime(
    private val mediaSession: R16MediaSessionHolder,
    private val widget: R16WidgetComponent,
    private val receiver: R16SystemPlaybackReceiver,
) {
    private var attached = false
    private var released = false

    val notification: ForegroundServiceNotification
        get() = mediaSession.notification

    /** The already-created MediaSession token; reading it does not attach or transfer ownership. */
    val token: MediaSessionCompat.Token
        get() = mediaSession.token

    fun attach(): MediaSessionCompat.Token {
        check(!released) { "Released R16 system surfaces cannot be attached" }
        if (attached) return mediaSession.token
        val token = mediaSession.attach()
        try {
            widget.attach()
            receiver.attach()
            attached = true
            return token
        } catch (error: Exception) {
            released = true
            receiver.release()
            widget.release()
            mediaSession.release()
            throw error
        }
    }

    fun tryMediaButtonIntent(intent: Intent): Boolean = mediaSession.tryMediaButtonIntent(intent)

    fun release() {
        if (released) return
        released = true
        attached = false
        receiver.release()
        widget.release()
        mediaSession.release()
    }
}
