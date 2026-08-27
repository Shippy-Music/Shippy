/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackListening.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.listening.ActiveListeningSessionCheckpoint
import app.shippy.core.listening.FinalizedListeningSession
import java.time.Instant
import java.util.UUID

interface PlaybackClock {
    fun elapsedRealtimeMs(): Long

    fun wallClock(): Instant
}

object SystemPlaybackClock : PlaybackClock {
    override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000

    override fun wallClock(): Instant = Instant.now()
}

fun interface ListeningSessionIdFactory {
    fun create(): ListeningSessionId
}

object RandomListeningSessionIdFactory : ListeningSessionIdFactory {
    override fun create(): ListeningSessionId = ListeningSessionId(UUID.randomUUID().toString())
}

/** Implementations must enqueue quickly and apply bounded backpressure. */
fun interface ListeningSessionSink {
    suspend fun offer(session: FinalizedListeningSession)

    fun offerCheckpoint(checkpoint: ActiveListeningSessionCheckpoint) = Unit
}

object NoOpListeningSessionSink : ListeningSessionSink {
    override suspend fun offer(session: FinalizedListeningSession) = Unit
}

data class ListeningSessionIdentity(
    val scrobbleAuthorized: Boolean = false,
    val accountId: String? = null,
) {
    companion object {
        val Unauthorized = ListeningSessionIdentity(scrobbleAuthorized = false, accountId = null)
    }
}

fun interface ListeningSessionIdentityProvider {
    suspend fun currentIdentity(): ListeningSessionIdentity
}

object NoOpListeningSessionIdentityProvider : ListeningSessionIdentityProvider {
    override suspend fun currentIdentity(): ListeningSessionIdentity =
        ListeningSessionIdentity.Unauthorized
}
