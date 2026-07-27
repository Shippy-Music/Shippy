/*
 * Copyright (c) 2026 Auxio Project
 * MusixmatchBrokerLyricsSource.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lyrics

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

/**
 * A credential-free client for a Shippy-controlled Musixmatch broker. Its request deliberately
 * excludes Shippy IDs, playback URLs, user IDs, and API credentials.
 */
@Singleton
class MusixmatchBrokerLyricsSource
@Inject
constructor(
    private val transport: ProviderHttpTransport,
    private val settings: MusixmatchBrokerSettings,
    private val clock: LyricsCacheClock = SystemLyricsCacheClock(),
) : LyricsSource {
    override val id = SOURCE_ID
    override val priority = 50
    private val cooldowns = ConcurrentHashMap<String, Long>()

    override suspend fun lookup(request: LyricsRequest): LyricsLookupResult {
        val endpoint =
            settings.endpoint?.let(::validatedMusixmatchBrokerEndpointOrNull)
                ?: return LyricsLookupResult.NotFound
        val nowEpochMs = clock.nowEpochMs()
        cooldowns[endpoint]?.let { expiresAtEpochMs ->
            if (nowEpochMs < expiresAtEpochMs) {
                return rateLimitedResult()
            }
            cooldowns.remove(endpoint, expiresAtEpochMs)
        }

        val response =
            try {
                transport.execute(
                    ProviderHttpRequest(
                        url = endpoint,
                        method = ProviderHttpMethod.POST,
                        headers =
                            mapOf(
                                "Accept" to "application/json",
                                "Content-Type" to "application/json; charset=utf-8",
                                "User-Agent" to CLIENT_USER_AGENT,
                            ),
                        body =
                            request.toBrokerPayload().toString().toByteArray(StandardCharsets.UTF_8),
                    )
                )
            } catch (error: IOException) {
                return LyricsLookupResult.Failure(
                    kind = LyricsFailureKind.NETWORK,
                    retryable = true,
                    message = error.message,
                )
            }

        when (response.statusCode) {
            404 -> return LyricsLookupResult.NotFound
            429 -> {
                setCooldown(endpoint, nowEpochMs + response.retryAfterMillis())
                return rateLimitedResult()
            }
            in 500..599 ->
                return LyricsLookupResult.Failure(
                    kind = LyricsFailureKind.SERVICE_UNAVAILABLE,
                    retryable = true,
                    message = "Musixmatch broker is unavailable (${response.statusCode})",
                )
            !in 200..299 ->
                return LyricsLookupResult.Failure(
                    kind = LyricsFailureKind.SERVICE_UNAVAILABLE,
                    retryable = false,
                    message = "Musixmatch broker request failed (${response.statusCode})",
                )
        }

        return response.toLookupResult(request)
    }

    private fun LyricsRequest.toBrokerPayload(): JSONObject {
        val normalizedAlbum = normalizeLyricsIdentity(album.orEmpty())
        return JSONObject().apply {
            put("title", normalizeLyricsIdentity(title))
            put("artists", JSONArray(artists.map(::normalizeLyricsIdentity)))
            put("album", normalizedAlbum.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            put("durationSeconds", durationMs?.div(1_000) ?: JSONObject.NULL)
        }
    }

    private fun ProviderHttpResponse.toLookupResult(request: LyricsRequest): LyricsLookupResult {
        if (body.size > MAX_BROKER_RESPONSE_BYTES) {
            return LyricsLookupResult.Failure(
                kind = LyricsFailureKind.MALFORMED_RESPONSE,
                retryable = false,
                message = "Musixmatch broker response was too large",
            )
        }
        return try {
            val record = JSONObject(bodyAsUtf8()).toBrokerRecord()
            selectBestLyrics(request, listOf(record))?.parse()?.let { lyrics ->
                LyricsLookupResult.Found(record, lyrics)
            } ?: LyricsLookupResult.NotFound
        } catch (error: JSONException) {
            LyricsLookupResult.Failure(
                kind = LyricsFailureKind.MALFORMED_RESPONSE,
                retryable = false,
                message = error.message,
            )
        } catch (error: IllegalArgumentException) {
            LyricsLookupResult.Failure(
                kind = LyricsFailureKind.MALFORMED_RESPONSE,
                retryable = false,
                message = error.message,
            )
        }
    }

    private fun JSONObject.toBrokerRecord(): LyricsRecord =
        LyricsRecord(
            id = getLong("id"),
            trackName = getString("trackName"),
            artistName = getString("artistName"),
            albumName = optNullableString("albumName"),
            durationSeconds =
                if (has("durationSeconds") && !isNull("durationSeconds")) {
                    getDouble("durationSeconds")
                } else {
                    null
                },
            instrumental = optBoolean("instrumental", false),
            plainLyrics = optNullableString("plainLyrics"),
            syncedLyrics = optNullableString("syncedLyrics"),
            sourceId = SOURCE_ID,
        )

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun ProviderHttpResponse.retryAfterMillis(): Long {
        val seconds =
            headers.entries
                .firstOrNull { (key, _) -> key.equals("Retry-After", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
        return (seconds?.times(1_000) ?: DEFAULT_COOLDOWN_MS).coerceAtMost(MAX_COOLDOWN_MS)
    }

    private fun setCooldown(endpoint: String, expiresAtEpochMs: Long) {
        synchronized(cooldowns) {
            if (!cooldowns.containsKey(endpoint) && cooldowns.size >= MAX_COOLDOWN_ENDPOINTS) {
                cooldowns.entries.minByOrNull { it.value }?.key?.let(cooldowns::remove)
            }
            cooldowns[endpoint] = expiresAtEpochMs
        }
    }

    private fun rateLimitedResult() =
        LyricsLookupResult.Failure(
            kind = LyricsFailureKind.RATE_LIMITED,
            retryable = true,
            message = "Musixmatch broker rate limited the request",
        )

    private companion object {
        const val SOURCE_ID = "musixmatch"
        const val CLIENT_USER_AGENT = "Shippy/0.1 Android (Musixmatch broker)"
        const val MAX_BROKER_RESPONSE_BYTES = 256 * 1024
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val MAX_RETRY_AFTER_SECONDS = 15 * 60L
        const val MAX_COOLDOWN_MS = MAX_RETRY_AFTER_SECONDS * 1_000
        const val MAX_COOLDOWN_ENDPOINTS = 8
    }
}
