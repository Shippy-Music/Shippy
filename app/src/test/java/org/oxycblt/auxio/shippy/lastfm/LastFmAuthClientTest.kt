/*
 * Copyright (c) 2026 Auxio Project
 * LastFmAuthClientTest.kt is part of Auxio.
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class LastFmAuthClientTest {
    @Test
    fun `request token signs only unsigned parameters and posts form`() = runBlocking {
        val transport = RecordingTransport(ok("{\"token\":\"token-1\"}"))
        val result = LastFmAuthClient(transport).requestToken("api key", "secret")

        assertEquals(LastFmAuthResult.Success("token-1"), result)
        val request = transport.request!!
        assertEquals("https://ws.audioscrobbler.com/2.0/", request.url)
        assertEquals("POST", request.method.name)
        assertEquals(
            "application/x-www-form-urlencoded; charset=UTF-8",
            request.headers["Content-Type"],
        )
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(request.headers["User-Agent"].orEmpty().startsWith("Shippy/"))
        val body = request.body!!.decodeToString()
        assertTrue(body.contains("method=auth.getToken"))
        assertTrue(body.contains("api_key=api+key"))
        assertTrue(body.contains("format=json"))
        assertTrue(
            body.contains(
                "api_sig=${LastFmSigning.signature(mapOf("method" to "auth.getToken", "api_key" to "api key"), "secret")}"
            )
        )
        assertFalse(body.contains("secret"))
    }

    @Test
    fun `authorization URL is fixed https and encodes only supplied values`() {
        val result = LastFmAuthClient(UnusedTransport).authorizationUrl("key +", "token&x")
        assertEquals(
            LastFmAuthResult.Success(
                "https://www.last.fm/api/auth/?api_key=key+%2B&token=token%26x"
            ),
            result,
        )
        assertEquals(
            LastFmAuthResult.Failure.InvalidInput,
            LastFmAuthClient(UnusedTransport).authorizationUrl("key", "bad\nvalue"),
        )
    }

    @Test
    fun `authorized token exchange parses session credentials`() = runBlocking {
        val transport =
            RecordingTransport(
                ok("{\"session\":{\"name\":\"alice\",\"key\":\"session-key\",\"subscriber\":0}}")
            )
        val result = LastFmAuthClient(transport).exchangeAuthorizedToken("key", "secret", "token")

        assertEquals(
            LastFmAuthResult.Success(LastFmCredentials("key", "secret", "session-key", "alice")),
            result,
        )
        val body = transport.request!!.body!!.decodeToString()
        assertTrue(body.contains("method=auth.getSession"))
        assertTrue(body.contains("token=token"))
        assertTrue(
            body.contains(
                "api_sig=${LastFmSigning.signature(mapOf("method" to "auth.getSession", "api_key" to "key", "token" to "token"), "secret")}"
            )
        )
    }

    @Test
    fun `api and transport failures remain explicit`() = runBlocking {
        val api =
            LastFmAuthClient(
                    RecordingTransport(ok("{\"error\":14,\"message\":\"Unauthorized token\"}"))
                )
                .requestToken("key", "secret")
        assertEquals(
            LastFmAuthResult.Failure.Api(
                14,
                "Unauthorized token",
                LastFmAuthFailureCode.INVALID_AUTH_TOKEN,
            ),
            api,
        )

        val http =
            LastFmAuthClient(
                    RecordingTransport(ProviderHttpResponse(503, emptyMap(), ByteArray(0)))
                )
                .requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.Http(503), http)

        val apiOnHttpFailure =
            LastFmAuthClient(
                    RecordingTransport(
                        ProviderHttpResponse(
                            400,
                            emptyMap(),
                            "{\"error\":10,\"message\":\"Invalid API key\"}".toByteArray(),
                        )
                    )
                )
                .requestToken("key", "secret")
        assertEquals(
            LastFmAuthResult.Failure.Api(
                10,
                "Invalid API key",
                LastFmAuthFailureCode.INVALID_API_KEY,
            ),
            apiOnHttpFailure,
        )

        val network = LastFmAuthClient(FailingTransport).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.Network, network)
    }

    @Test
    fun `documented auth error codes retain their distinct meaning`() {
        assertEquals(LastFmAuthFailureCode.AUTHENTICATION_FAILED, LastFmAuthFailureCode.from(4))
        assertEquals(LastFmAuthFailureCode.INVALID_SESSION, LastFmAuthFailureCode.from(9))
        assertEquals(LastFmAuthFailureCode.INVALID_API_KEY, LastFmAuthFailureCode.from(10))
        assertEquals(LastFmAuthFailureCode.SERVICE_OFFLINE, LastFmAuthFailureCode.from(11))
        assertEquals(LastFmAuthFailureCode.INVALID_SIGNATURE, LastFmAuthFailureCode.from(13))
        assertEquals(LastFmAuthFailureCode.INVALID_AUTH_TOKEN, LastFmAuthFailureCode.from(14))
        assertEquals(LastFmAuthFailureCode.TEMPORARY_ERROR, LastFmAuthFailureCode.from(16))
    }

    @Test
    fun `malformed and oversized JSON are rejected without a token`() = runBlocking {
        val malformed =
            LastFmAuthClient(RecordingTransport(ok("{\"token\":\"abc\"")))
                .requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.MalformedResponse, malformed)

        val oversized = "{\"token\":\"${"x".repeat(70 * 1024)}\"}"
        val tooLarge =
            LastFmAuthClient(RecordingTransport(ok(oversized))).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.MalformedResponse, tooLarge)
    }

    private class RecordingTransport(private val response: ProviderHttpResponse) :
        ProviderHttpTransport {
        var request: ProviderHttpRequest? = null

        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
            this.request = request
            return response
        }
    }

    private object FailingTransport : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
            throw IOException("offline")
    }

    private object UnusedTransport : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
            error("No request expected")
    }

    private fun ok(body: String) = ProviderHttpResponse(200, emptyMap(), body.toByteArray())
}
