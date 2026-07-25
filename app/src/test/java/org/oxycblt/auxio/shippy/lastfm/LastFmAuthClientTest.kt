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
        val transport = RecordingTransport(ok("<lfm status=\"ok\"><token>token-1</token></lfm>"))
        val result = LastFmAuthClient(transport).requestToken("api key", "secret")

        assertEquals(LastFmAuthResult.Success("token-1"), result)
        val request = transport.request!!
        assertEquals("https://ws.audioscrobbler.com/2.0/", request.url)
        assertEquals("POST", request.method.name)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val body = request.body!!.decodeToString()
        assertTrue(body.contains("method=auth.getToken"))
        assertTrue(body.contains("api_key=api+key"))
        assertTrue(body.contains("format=xml"))
        assertTrue(body.contains("api_sig=${LastFmSigning.signature(mapOf("method" to "auth.getToken", "api_key" to "api key"), "secret")}"))
        assertFalse(body.contains("secret"))
    }

    @Test
    fun `authorization URL is fixed https and encodes only supplied values`() {
        val result = LastFmAuthClient(UnusedTransport).authorizationUrl("key +", "token&x")
        assertEquals(
            LastFmAuthResult.Success("https://www.last.fm/api/auth/?api_key=key+%2B&token=token%26x"),
            result,
        )
        assertEquals(LastFmAuthResult.Failure.InvalidInput, LastFmAuthClient(UnusedTransport).authorizationUrl("key", "bad\nvalue"))
    }

    @Test
    fun `authorized token exchange parses session credentials`() = runBlocking {
        val transport = RecordingTransport(ok("<lfm status=\"ok\"><session><name>alice</name><key>session-key</key></session></lfm>"))
        val result = LastFmAuthClient(transport).exchangeAuthorizedToken("key", "secret", "token")

        assertEquals(LastFmAuthResult.Success(LastFmCredentials("key", "secret", "session-key", "alice")), result)
        val body = transport.request!!.body!!.decodeToString()
        assertTrue(body.contains("method=auth.getSession"))
        assertTrue(body.contains("token=token"))
        assertTrue(body.contains("api_sig=${LastFmSigning.signature(mapOf("method" to "auth.getSession", "api_key" to "key", "token" to "token"), "secret")}"))
    }

    @Test
    fun `api and transport failures remain explicit`() = runBlocking {
        val api = LastFmAuthClient(RecordingTransport(ok("<lfm status=\"failed\"><error code=\"14\">Unauthorized token</error></lfm>"))).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.Api(14, "Unauthorized token", LastFmAuthFailureCode.INVALID_AUTH_TOKEN), api)

        val http = LastFmAuthClient(RecordingTransport(ProviderHttpResponse(503, emptyMap(), ByteArray(0)))).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.Http(503), http)

        val apiOnHttpFailure =
            LastFmAuthClient(
                    RecordingTransport(
                        ProviderHttpResponse(
                            400,
                            emptyMap(),
                            "<lfm status=\"failed\"><error code=\"10\">Invalid API key</error></lfm>"
                                .toByteArray(),
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
    fun `malformed and oversized XML are rejected without a token`() = runBlocking {
        val malformed = LastFmAuthClient(RecordingTransport(ok("<lfm status=\"ok\"><token>abc</lfm>"))).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.MalformedResponse, malformed)

        val oversized = "<lfm status=\"ok\"><token>${"x".repeat(70 * 1024)}</token></lfm>"
        val tooLarge = LastFmAuthClient(RecordingTransport(ok(oversized))).requestToken("key", "secret")
        assertEquals(LastFmAuthResult.Failure.MalformedResponse, tooLarge)
    }

    private class RecordingTransport(private val response: ProviderHttpResponse) : ProviderHttpTransport {
        var request: ProviderHttpRequest? = null
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
            this.request = request
            return response
        }
    }

    private object FailingTransport : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse = throw IOException("offline")
    }

    private object UnusedTransport : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse = error("No request expected")
    }

    private fun ok(body: String) = ProviderHttpResponse(200, emptyMap(), body.toByteArray())
}
