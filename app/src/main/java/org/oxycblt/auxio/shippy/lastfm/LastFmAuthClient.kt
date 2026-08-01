/*
 * Copyright (c) 2026 Auxio Project
 * LastFmAuthClient.kt is part of Auxio.
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
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import org.json.JSONObject
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

internal const val LAST_FM_USER_AGENT = "Shippy/0.1 (https://github.com/Shippy-Music/Shippy)"

/**
 * Last.fm's browser authorization protocol. Callers supply application credentials at runtime; this
 * class deliberately neither stores nor logs them.
 */
class LastFmAuthClient @Inject constructor(private val transport: ProviderHttpTransport) {
    suspend fun requestToken(apiKey: String, apiSecret: String): LastFmAuthResult<String> =
        request(
            apiKey = apiKey,
            apiSecret = apiSecret,
            method = METHOD_GET_TOKEN,
            extra = emptyMap(),
        ) { document ->
            LastFmAuthJson.token(document)
        }

    fun authorizationUrl(apiKey: String, token: String): LastFmAuthResult<String> {
        val invalid = LastFmAuthInput.invalid(apiKey, token)
        if (invalid != null) return invalid

        val url = "$AUTHORIZATION_URL?api_key=${encode(apiKey)}&token=${encode(token)}"
        return try {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "www.last.fm" || uri.userInfo != null) {
                LastFmAuthResult.Failure.InvalidInput
            } else {
                LastFmAuthResult.Success(url)
            }
        } catch (_: IllegalArgumentException) {
            LastFmAuthResult.Failure.InvalidInput
        }
    }

    suspend fun exchangeAuthorizedToken(
        apiKey: String,
        apiSecret: String,
        token: String,
    ): LastFmAuthResult<LastFmCredentials> =
        request(
            apiKey = apiKey,
            apiSecret = apiSecret,
            method = METHOD_GET_SESSION,
            extra = mapOf("token" to token),
        ) { document ->
            LastFmAuthJson.session(document)?.let { (sessionKey, username) ->
                LastFmAuthResult.Success(LastFmCredentials(apiKey, apiSecret, sessionKey, username))
            } ?: LastFmAuthResult.Failure.MalformedResponse
        }

    private suspend fun <T> request(
        apiKey: String,
        apiSecret: String,
        method: String,
        extra: Map<String, String>,
        parse: (JSONObject) -> LastFmAuthResult<T>?,
    ): LastFmAuthResult<T> {
        if (!LastFmAuthInput.isValid(apiKey) || !LastFmAuthInput.isValid(apiSecret)) {
            return LastFmAuthResult.Failure.InvalidInput
        }
        if (extra.values.any { !LastFmAuthInput.isValid(it) }) {
            return LastFmAuthResult.Failure.InvalidInput
        }

        val unsigned = mapOf("method" to method, "api_key" to apiKey) + extra
        val params =
            unsigned +
                ("api_sig" to LastFmSigning.signature(unsigned, apiSecret)) +
                ("format" to "json")
        val request =
            ProviderHttpRequest(
                url = API_URL,
                method = ProviderHttpMethod.POST,
                headers =
                    mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "application/json",
                        "User-Agent" to LAST_FM_USER_AGENT,
                    ),
                body =
                    params.entries
                        .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                        .toByteArray(Charsets.UTF_8),
            )

        val response =
            try {
                transport.execute(request)
            } catch (_: IOException) {
                return LastFmAuthResult.Failure.Network
            }
        val envelope = LastFmAuthJson.parse(response)
        if (envelope is LastFmAuthJson.Envelope.ApiError) {
            return LastFmAuthResult.Failure.Api(
                envelope.code,
                envelope.message,
                LastFmAuthFailureCode.from(envelope.code),
            )
        }
        if (response.statusCode !in 200..299) {
            return LastFmAuthResult.Failure.Http(response.statusCode)
        }
        return when (envelope) {
            is LastFmAuthJson.Envelope.ApiError ->
                LastFmAuthResult.Failure.Api(
                    envelope.code,
                    envelope.message,
                    LastFmAuthFailureCode.from(envelope.code),
                )
            is LastFmAuthJson.Envelope.Ok ->
                parse(envelope.document) ?: LastFmAuthResult.Failure.MalformedResponse
            LastFmAuthJson.Envelope.Malformed -> LastFmAuthResult.Failure.MalformedResponse
        }
    }

    private companion object {
        const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        const val AUTHORIZATION_URL = "https://www.last.fm/api/auth/"
        const val METHOD_GET_TOKEN = "auth.getToken"
        const val METHOD_GET_SESSION = "auth.getSession"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

sealed interface LastFmAuthResult<out T> {
    data class Success<T>(val value: T) : LastFmAuthResult<T>

    sealed interface Failure : LastFmAuthResult<Nothing> {
        data object InvalidInput : Failure

        data object Network : Failure

        data class Http(val statusCode: Int) : Failure

        data class Api(val code: Int, val message: String?, val kind: LastFmAuthFailureCode) :
            Failure

        data object MalformedResponse : Failure
    }
}

enum class LastFmAuthFailureCode {
    AUTHENTICATION_FAILED,
    INVALID_SESSION,
    INVALID_API_KEY,
    SERVICE_OFFLINE,
    INVALID_SIGNATURE,
    INVALID_AUTH_TOKEN,
    TEMPORARY_ERROR,
    OTHER;

    companion object {
        fun from(code: Int): LastFmAuthFailureCode =
            when (code) {
                4 -> AUTHENTICATION_FAILED
                9 -> INVALID_SESSION
                10 -> INVALID_API_KEY
                11 -> SERVICE_OFFLINE
                13 -> INVALID_SIGNATURE
                14 -> INVALID_AUTH_TOKEN
                16 -> TEMPORARY_ERROR
                else -> OTHER
            }
    }
}

private object LastFmAuthInput {
    private const val MAX_INPUT_BYTES = 1024

    fun isValid(value: String): Boolean =
        value.isNotBlank() &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES &&
            value.none { it.isISOControl() }

    fun invalid(apiKey: String, token: String): LastFmAuthResult.Failure? =
        if (isValid(apiKey) && isValid(token)) null else LastFmAuthResult.Failure.InvalidInput
}

internal object LastFmAuthJson {
    private const val MAX_JSON_BYTES = 64 * 1024

    sealed interface Envelope {
        data class Ok(val document: JSONObject) : Envelope

        data class ApiError(val code: Int, val message: String?) : Envelope

        data object Malformed : Envelope
    }

    fun parse(response: ProviderHttpResponse): Envelope {
        val body = response.body
        if (body.isEmpty() || body.size > MAX_JSON_BYTES || body.any { it == 0.toByte() }) {
            return Envelope.Malformed
        }
        val document =
            runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return Envelope.Malformed
        if (document.has("error")) {
            val code = document.optInt("error", -1).takeIf { it >= 0 } ?: return Envelope.Malformed
            return Envelope.ApiError(
                code,
                document
                    .optString("message")
                    .trim()
                    .take(MAX_ERROR_MESSAGE_CHARS)
                    .takeIf(String::isNotBlank),
            )
        }
        return Envelope.Ok(document)
    }

    fun token(document: JSONObject): LastFmAuthResult<String>? {
        val token = document.optString("token").trim()
        return if (LastFmAuthInput.isValid(token)) LastFmAuthResult.Success(token)
        else LastFmAuthResult.Failure.MalformedResponse
    }

    fun session(document: JSONObject): Pair<String, String>? {
        val session = document.optJSONObject("session") ?: return null
        val key = session.optString("key").trim()
        val name = session.optString("name").trim()
        return if (LastFmAuthInput.isValid(key) && LastFmAuthInput.isValid(name)) key to name
        else null
    }

    private const val MAX_ERROR_MESSAGE_CHARS = 512
}
