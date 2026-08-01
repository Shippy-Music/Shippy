/*
 * Copyright (c) 2026 Auxio Project
 * LastFmClient.kt is part of Auxio.
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

import java.io.IOException
import javax.inject.Inject
import org.oxycblt.auxio.shippy.persistence.lastfm.LastFmScrobbleEntity
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

enum class LastFmDelivery {
    DELIVERED,
    RETRY,
    REAUTH,
    DROP,
}

class LastFmClient @Inject constructor(private val transport: ProviderHttpTransport) {
    suspend fun nowPlaying(track: LastFmTrack, credentials: LastFmCredentials): LastFmDelivery =
        post(signed(base("track.updateNowPlaying", credentials) + track.params(), credentials))

    suspend fun scrobble(
        entries: List<LastFmScrobbleEntity>,
        credentials: LastFmCredentials,
    ): LastFmDelivery {
        require(entries.size <= 50)
        val params = base("track.scrobble", credentials).toMutableMap()
        entries.forEachIndexed { index, entry ->
            params["artist[$index]"] = entry.artist
            params["track[$index]"] = entry.track
            params["timestamp[$index]"] = entry.startedAtEpochSeconds.toString()
            entry.album?.let { params["album[$index]"] = it }
            entry.durationSeconds?.let { params["duration[$index]"] = it.toString() }
        }
        return post(signed(params, credentials))
    }

    private fun base(method: String, c: LastFmCredentials) =
        mapOf("method" to method, "api_key" to c.apiKey, "sk" to c.sessionKey)

    private fun LastFmTrack.params() = buildMap {
        put("artist", artist)
        put("track", title)
        album?.let { put("album", it) }
        durationMs?.let { put("duration", (it / 1000).toString()) }
    }

    private fun signed(unsigned: Map<String, String>, credentials: LastFmCredentials) =
        unsigned +
            ("api_sig" to LastFmSigning.signature(unsigned, credentials.apiSecret)) +
            ("format" to "xml")

    private suspend fun post(credentials: Map<String, String>): LastFmDelivery {
        return try {
            if (credentials["api_sig"] == null) LastFmDelivery.DROP
            else
                response(
                    transport.execute(
                        ProviderHttpRequest(
                            URL,
                            ProviderHttpMethod.POST,
                            mapOf(
                                "Content-Type" to
                                    "application/x-www-form-urlencoded; charset=UTF-8",
                                "User-Agent" to LAST_FM_USER_AGENT,
                            ),
                            credentials.entries
                                .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                                .toByteArray(),
                        )
                    )
                )
        } catch (_: IOException) {
            LastFmDelivery.RETRY
        }
    }

    private fun response(
        r: org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
    ): LastFmDelivery {
        val body = r.bodyAsUtf8()
        val code =
            Regex("<error[^>]*code=\\\"(\\d+)\\\"").find(body)?.groupValues?.get(1)?.toIntOrNull()
        return when (code) {
            null ->
                if (r.statusCode in 200..299 && body.contains("<lfm status=\"ok\""))
                    LastFmDelivery.DELIVERED
                else LastFmDelivery.RETRY
            9 -> LastFmDelivery.REAUTH
            11,
            16 -> LastFmDelivery.RETRY
            else -> LastFmDelivery.DROP
        }
    }

    companion object {
        private const val URL = "https://ws.audioscrobbler.com/2.0/"

        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
