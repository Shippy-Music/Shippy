/*
 * Copyright (c) 2026 Auxio Project
 * LastFm.kt is part of Auxio.
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

import java.security.MessageDigest
import java.util.Locale
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.persistence.lastfm.LastFmScrobbleEntity

data class LastFmCredentials(
    val apiKey: String,
    val apiSecret: String,
    val sessionKey: String,
    val username: String,
) {
    init {
        listOf(apiKey, apiSecret, sessionKey, username).forEach {
            require(it.isNotBlank() && '\u0000' !in it) { "Last.fm credentials are invalid" }
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_FIELD_BYTES) {
                "Last.fm credential field is too large"
            }
        }
    }

    private companion object {
        const val MAX_CREDENTIAL_FIELD_BYTES = 1024
    }

    override fun toString(): String =
        "LastFmCredentials(username=$username, apiKey=redacted, apiSecret=redacted, sessionKey=redacted)"
}

interface LastFmCredentialRepository {
    suspend fun load(): LastFmCredentials?

    suspend fun save(credentials: LastFmCredentials)

    suspend fun clear()
}

data class LastFmTrack(
    val artist: String,
    val title: String,
    val album: String?,
    val durationMs: Long?,
) {
    init {
        require(artist.isNotBlank() && title.isNotBlank())
    }

    companion object {
        fun from(item: QueueItem): LastFmTrack? =
            item.track.artists.firstOrNull(String::isNotBlank)?.let { artist ->
                LastFmTrack(artist, item.track.title, item.track.album, item.track.durationMs)
            }
    }
}

object LastFmAccountId {
    fun hash(username: String): String {
        val normalized = username.trim().lowercase(Locale.ROOT)
        require(normalized.isNotEmpty()) { "Last.fm username cannot be blank" }
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object LastFmSigning {
    fun signature(parameters: Map<String, String>, secret: String): String {
        val input = parameters.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun LastFmTrack.outbox(
    queueItemId: QueueItemId,
    accountId: String,
    startedAtEpochSeconds: Long,
    nowEpochMs: Long,
): LastFmScrobbleEntity {
    require(startedAtEpochSeconds > 0)
    return LastFmScrobbleEntity(
        "queue-item:${queueItemId.value}",
        accountId,
        artist,
        title,
        album,
        durationMs?.div(1000)?.toInt(),
        startedAtEpochSeconds,
        nowEpochMs,
    )
}

internal fun LastFmScrobbleEntity.payload() =
    LastFmScrobblePayload(
        id = id,
        artist = artist,
        track = track,
        album = album,
        durationSeconds = durationSeconds?.toLong(),
        startedAtEpochSeconds = startedAtEpochSeconds,
    )
