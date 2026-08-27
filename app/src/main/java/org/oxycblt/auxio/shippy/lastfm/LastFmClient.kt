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

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

enum class LastFmDelivery {
    DELIVERED,
    RETRY,
    REAUTH,
    DROP,
}

data class LastFmIgnoredScrobble(val id: String, val code: Int, val message: String)

/** Transport-neutral scrobble input shared by the legacy and R16 durable outboxes. */
data class LastFmScrobblePayload(
    val id: String,
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Long?,
    val startedAtEpochSeconds: Long,
)

sealed interface LastFmScrobbleResult {
    data class Delivered(val acceptedIds: Set<String>, val ignored: List<LastFmIgnoredScrobble>) :
        LastFmScrobbleResult

    data class Retry(val reason: String? = null) : LastFmScrobbleResult

    data object Reauth : LastFmScrobbleResult

    data class Dropped(val ids: Set<String>, val reason: String? = null) : LastFmScrobbleResult
}

class LastFmClient @Inject constructor(private val transport: ProviderHttpTransport) {
    suspend fun nowPlaying(track: LastFmTrack, credentials: LastFmCredentials): LastFmDelivery =
        post(signed(base("track.updateNowPlaying", credentials) + track.params(), credentials))

    suspend fun scrobble(
        entries: List<LastFmScrobblePayload>,
        credentials: LastFmCredentials,
    ): LastFmScrobbleResult {
        require(entries.isNotEmpty() && entries.size <= 50)
        val params = base("track.scrobble", credentials).toMutableMap()
        entries.forEachIndexed { index, entry ->
            params["artist[$index]"] = entry.artist
            params["track[$index]"] = entry.track
            params["timestamp[$index]"] = entry.startedAtEpochSeconds.toString()
            entry.album?.let { params["album[$index]"] = it }
            entry.durationSeconds?.let { params["duration[$index]"] = it.toString() }
        }
        val signed = signed(params, credentials)
        if (signed["api_sig"] == null) {
            return LastFmScrobbleResult.Dropped(
                entries.mapTo(linkedSetOf()) { it.id },
                "missing signature",
            )
        }
        return try {
            parseLastFmScrobbleResponse(
                transport.execute(
                    ProviderHttpRequest(
                        URL,
                        ProviderHttpMethod.POST,
                        mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "User-Agent" to LAST_FM_USER_AGENT,
                        ),
                        signed.entries
                            .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                            .toByteArray(),
                    )
                ),
                entries.map(LastFmScrobblePayload::id),
            )
        } catch (_: IOException) {
            LastFmScrobbleResult.Retry("network")
        }
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
    ): LastFmDelivery = parseLastFmDeliveryResponse(r)

    companion object {
        private const val URL = "https://ws.audioscrobbler.com/2.0/"

        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
}

internal fun parseLastFmDeliveryResponse(response: ProviderHttpResponse): LastFmDelivery {
    val body = response.bodyAsUtf8()
    if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return LastFmDelivery.RETRY
    val errorCode =
        Regex("<error\\b[^>]*\\bcode\\s*=\\s*['\"](\\d+)['\"]", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    return when (errorCode) {
        9 -> LastFmDelivery.REAUTH
        11,
        16,
        29 -> LastFmDelivery.RETRY
        null ->
            if (
                response.statusCode in 200..299 &&
                    Regex("<lfm\\b[^>]*\\bstatus\\s*=\\s*['\"]ok['\"]", RegexOption.IGNORE_CASE)
                        .containsMatchIn(body)
            ) {
                LastFmDelivery.DELIVERED
            } else {
                LastFmDelivery.RETRY
            }
        else -> LastFmDelivery.DROP
    }
}

internal fun parseLastFmScrobbleResponse(
    response: ProviderHttpResponse,
    entryIds: List<String>,
): LastFmScrobbleResult {
    require(entryIds.isNotEmpty() && entryIds.size <= 50)
    val body = response.bodyAsUtf8()
    val errorCode =
        Regex("<error\\b[^>]*\\bcode\\s*=\\s*['\"](\\d+)['\"]", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    if (errorCode != null || response.statusCode !in 200..299) {
        return when (errorCode) {
            9 -> LastFmScrobbleResult.Reauth
            11,
            16,
            29,
            null -> LastFmScrobbleResult.Retry("http ${response.statusCode}")
            else -> LastFmScrobbleResult.Dropped(entryIds.toSet(), "Last.fm error $errorCode")
        }
    }
    if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
        return LastFmScrobbleResult.Retry("response too large")
    }

    return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isExpandEntityReferences = false
            factory.isXIncludeAware = false
            val root =
                factory
                    .newDocumentBuilder()
                    .parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                    .documentElement
            require(root.nodeName.equals("lfm", ignoreCase = true))
            require(root.getAttribute("status").equals("ok", ignoreCase = true))
            val scrobbles = root.childElements("scrobbles").single()
            val acceptedCount = scrobbles.requireIntAttribute("accepted")
            val ignoredCount = scrobbles.requireIntAttribute("ignored")
            val entries = scrobbles.childElements("scrobble")
            require(entries.size == entryIds.size)

            val accepted = linkedSetOf<String>()
            val ignored = buildList {
                entries.forEachIndexed { index, scrobble ->
                    val ignoredMessage =
                        scrobble.childElements("ignoredMessage", "ignoredmessage").single()
                    val code = ignoredMessage.requireIntAttribute("code")
                    val id = entryIds[index]
                    if (code == 0) {
                        accepted += id
                    } else {
                        add(
                            LastFmIgnoredScrobble(
                                id = id,
                                code = code,
                                message = ignoredMessage.textContent.trim(),
                            )
                        )
                    }
                }
            }
            require(acceptedCount == accepted.size)
            require(ignoredCount == ignored.size)
            require(acceptedCount + ignoredCount == entryIds.size)
            LastFmScrobbleResult.Delivered(accepted, ignored)
        }
        .getOrElse { LastFmScrobbleResult.Retry("malformed response") }
}

private fun org.w3c.dom.Element.childElements(vararg names: String): List<org.w3c.dom.Element> =
    (0 until childNodes.length)
        .asSequence()
        .mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }
        .filter { child -> names.any { it.equals(child.nodeName, ignoreCase = true) } }
        .toList()

private fun org.w3c.dom.Element.requireIntAttribute(name: String): Int =
    getAttribute(name).toIntOrNull()?.takeIf { it >= 0 } ?: error("Missing or invalid $name")

private const val MAX_RESPONSE_BYTES = 128 * 1024
