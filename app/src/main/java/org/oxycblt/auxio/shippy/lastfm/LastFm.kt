package org.oxycblt.auxio.shippy.lastfm

import java.security.MessageDigest
import java.util.UUID
import org.oxycblt.auxio.shippy.domain.QueueItem
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
}
interface LastFmCredentialRepository { suspend fun load(): LastFmCredentials?; suspend fun save(credentials: LastFmCredentials); suspend fun clear() }

data class LastFmTrack(val artist: String, val title: String, val album: String?, val durationMs: Long?) {
    init { require(artist.isNotBlank() && title.isNotBlank()) }
    companion object {
        fun from(item: QueueItem): LastFmTrack? =
            item.track.artists.firstOrNull(String::isNotBlank)?.let { artist ->
                LastFmTrack(artist, item.track.title, item.track.album, item.track.durationMs)
            }
    }
}

object LastFmSigning {
    fun signature(parameters: Map<String, String>, secret: String): String {
        val input = parameters.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal fun LastFmTrack.outbox(startedAtEpochSeconds: Long, nowEpochMs: Long): LastFmScrobbleEntity {
    require(startedAtEpochSeconds > 0)
    return LastFmScrobbleEntity(
        UUID.randomUUID().toString(),
        artist,
        title,
        album,
        durationMs?.div(1000)?.toInt(),
        startedAtEpochSeconds,
        nowEpochMs,
    )
}
