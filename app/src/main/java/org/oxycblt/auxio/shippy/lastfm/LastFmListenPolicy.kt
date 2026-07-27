/*
 * Copyright (c) 2026 Auxio Project
 * LastFmListenPolicy.kt is part of Auxio.
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

/**
 * Position deltas are accepted only when they look like ordinary advancing playback, never seeks.
 */
internal class LastFmListenPolicy(private val durationMs: Long?) {
    private var previous: Long? = null
    var listenedMs = 0L
        private set

    var scrobbled = false
        private set

    fun pause() {
        previous = null
    }

    fun update(positionMs: Long): Boolean {
        val old = previous
        previous = positionMs
        if (old == null || positionMs < old || positionMs - old > 5_000L) return false
        listenedMs += positionMs - old
        val eligible = durationMs == null || durationMs > 30_000L
        val threshold = durationMs?.let { minOf(it / 2, 240_000L) } ?: 240_000L
        if (!eligible || scrobbled || listenedMs < threshold) return false
        scrobbled = true
        return true
    }
}
