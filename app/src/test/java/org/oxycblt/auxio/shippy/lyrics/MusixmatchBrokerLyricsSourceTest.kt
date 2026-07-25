/*
 * Copyright (c) 2026 Shippy contributors
 * MusixmatchBrokerLyricsSourceTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.lyrics

import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class MusixmatchBrokerLyricsSourceTest {
    @Test
    fun `unconfigured broker skips cleanly without a request`() = runBlocking {
        val transport = FakeTransport { error("Transport must not be called") }
        val source = MusixmatchBrokerLyricsSource(transport, FakeSettings(null))

        assertEquals(LyricsLookupResult.NotFound, source.lookup(request()))
        assertNull(transport.request)
    }

    @Test
    fun `broker posts normalized identity only and returns Musixmatch record`() = runBlocking {
        val transport =
            FakeTransport {
                response(
                    200,
                    """
                    {
                      "id": 42,
                      "trackName": "Song",
                      "artistName": "Artist Co",
                      "albumName": "Album",
                      "durationSeconds": 123.0,
                      "plainLyrics": "Broker lyrics",
                      "syncedLyrics": "[00:01.00] Broker lyrics"
                    }
                    """.trimIndent(),
                )
            }
        val source = MusixmatchBrokerLyricsSource(transport, FakeSettings(ENDPOINT))

        val result =
            source.lookup(
                request(
                    title = "Song!!!",
                    artists = listOf("Artist & Co"),
                    album = "Album",
                    durationMs = 123_900,
                )
            )

        val found = result as LyricsLookupResult.Found
        val http = requireNotNull(transport.request)
        val payload = JSONObject(requireNotNull(http.body).toString(StandardCharsets.UTF_8))
        assertEquals(ProviderHttpMethod.POST, http.method)
        assertEquals(ENDPOINT, http.url)
        assertEquals("song", payload.getString("title"))
        assertEquals("artist co", payload.getJSONArray("artists").getString(0))
        assertEquals("album", payload.getString("album"))
        assertEquals(123L, payload.getLong("durationSeconds"))
        assertFalse(payload.has("trackId"))
        assertFalse(payload.has("apiKey"))
        assertEquals("musixmatch", found.record.sourceId)
        assertTrue(found.lyrics is SyncedLyrics)
        assertEquals(50, source.priority)
    }

    @Test
    fun `broker maps not found rate limit and unavailable responses`() = runBlocking {
        assertEquals(
            LyricsLookupResult.NotFound,
            MusixmatchBrokerLyricsSource(
                FakeTransport { response(404, "") },
                FakeSettings(ENDPOINT),
            ).lookup(request()),
        )
        assertEquals(
            LyricsFailureKind.RATE_LIMITED,
            failureKind(
                MusixmatchBrokerLyricsSource(
                    FakeTransport { response(429, "") },
                    FakeSettings(ENDPOINT),
                ).lookup(request())
            ),
        )
        assertEquals(
            LyricsFailureKind.SERVICE_UNAVAILABLE,
            failureKind(
                MusixmatchBrokerLyricsSource(
                    FakeTransport { response(503, "") },
                    FakeSettings(ENDPOINT),
                ).lookup(request())
            ),
        )
    }

    @Test
    fun `broker maps network and malformed responses`() = runBlocking {
        assertEquals(
            LyricsFailureKind.NETWORK,
            failureKind(
                MusixmatchBrokerLyricsSource(
                    FakeTransport { throw IOException("offline") },
                    FakeSettings(ENDPOINT),
                ).lookup(request())
            ),
        )
        assertEquals(
            LyricsFailureKind.MALFORMED_RESPONSE,
            failureKind(
                MusixmatchBrokerLyricsSource(
                    FakeTransport { response(200, "not json") },
                    FakeSettings(ENDPOINT),
                ).lookup(request())
            ),
        )
    }

    @Test
    fun `rate limited endpoint short circuits until retry after expires`() = runBlocking {
        var requests = 0
        val clock = FakeClock()
        val source =
            MusixmatchBrokerLyricsSource(
                FakeTransport {
                    requests += 1
                    if (requests == 1) {
                        response(429, "", mapOf("Retry-After" to listOf("2")))
                    } else {
                        response(404, "")
                    }
                },
                FakeSettings(ENDPOINT),
                clock,
            )

        assertEquals(LyricsFailureKind.RATE_LIMITED, failureKind(source.lookup(request())))
        assertEquals(LyricsFailureKind.RATE_LIMITED, failureKind(source.lookup(request())))
        assertEquals(1, requests)

        clock.nowEpochMs = 2_000
        assertEquals(LyricsLookupResult.NotFound, source.lookup(request()))
        assertEquals(2, requests)
    }

    @Test
    fun `negative retry after uses default cooldown`() = runBlocking {
        var requests = 0
        val source =
            MusixmatchBrokerLyricsSource(
                FakeTransport {
                    requests += 1
                    response(429, "", mapOf("Retry-After" to listOf("-1")))
                },
                FakeSettings(ENDPOINT),
                FakeClock(),
            )

        assertEquals(LyricsFailureKind.RATE_LIMITED, failureKind(source.lookup(request())))
        assertEquals(LyricsFailureKind.RATE_LIMITED, failureKind(source.lookup(request())))
        assertEquals(1, requests)
    }

    @Test
    fun `broker rejects a mismatched response recording`() = runBlocking {
        val source =
            MusixmatchBrokerLyricsSource(
                FakeTransport {
                    response(
                        200,
                        """
                        {
                          "id": 99,
                          "trackName": "Different Live Remix",
                          "artistName": "Other",
                          "durationSeconds": 300.0,
                          "plainLyrics": "Wrong recording"
                        }
                        """.trimIndent(),
                    )
                },
                FakeSettings(ENDPOINT),
            )

        assertEquals(LyricsLookupResult.NotFound, source.lookup(request()))
    }

    @Test
    fun `broker endpoint accepts HTTPS and disables invalid saved values`() {
        assertEquals(ENDPOINT, validateMusixmatchBrokerEndpoint(ENDPOINT))
        assertNull(validatedMusixmatchBrokerEndpointOrNull("http://broker.example/lyrics"))
        assertNull(validatedMusixmatchBrokerEndpointOrNull("https://key@broker.example/lyrics"))
        assertNull(validatedMusixmatchBrokerEndpointOrNull("https://broker.example/lyrics?key=x"))
    }

    private fun request(
        title: String = "Song",
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        durationMs: Long? = 123_000,
    ) =
        LyricsRequest(
            trackId = TrackId("provider:song"),
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
        )

    private fun response(
        statusCode: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
    ) =
        ProviderHttpResponse(
            statusCode = statusCode,
            headers = headers,
            body = body.toByteArray(StandardCharsets.UTF_8),
        )

    private fun failureKind(result: LyricsLookupResult): LyricsFailureKind =
        (result as LyricsLookupResult.Failure).kind

    private class FakeTransport(
        private val response: suspend (ProviderHttpRequest) -> ProviderHttpResponse,
    ) : ProviderHttpTransport {
        var request: ProviderHttpRequest? = null

        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
            this.request = request
            return response(request)
        }
    }

    private class FakeSettings(
        override val endpoint: String?,
    ) : MusixmatchBrokerSettings {
        override fun setEndpoint(endpoint: String?) = Unit

        override fun registerListener(listener: Nothing) = Unit

        override fun unregisterListener(listener: Nothing) = Unit
    }

    private class FakeClock(
        var nowEpochMs: Long = 0,
    ) : LyricsCacheClock {
        override fun nowEpochMs(): Long = nowEpochMs
    }

    private companion object {
        const val ENDPOINT = "https://broker.example/lyrics"
    }
}
