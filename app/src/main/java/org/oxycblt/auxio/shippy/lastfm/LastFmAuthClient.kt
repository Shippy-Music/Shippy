package org.oxycblt.auxio.shippy.lastfm

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.DocumentBuilderFactory
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport
import org.w3c.dom.Element
import org.xml.sax.SAXException

/**
 * Last.fm's browser authorization protocol. Callers supply application credentials at runtime;
 * this class deliberately neither stores nor logs them.
 */
class LastFmAuthClient @Inject constructor(private val transport: ProviderHttpTransport) {
    suspend fun requestToken(apiKey: String, apiSecret: String): LastFmAuthResult<String> =
        request(
            apiKey = apiKey,
            apiSecret = apiSecret,
            method = METHOD_GET_TOKEN,
            extra = emptyMap(),
        ) { document ->
            LastFmAuthXml.token(document)
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
            LastFmAuthXml.session(document)?.let { (sessionKey, username) ->
                LastFmAuthResult.Success(LastFmCredentials(apiKey, apiSecret, sessionKey, username))
            } ?: LastFmAuthResult.Failure.MalformedResponse
        }

    private suspend fun <T> request(
        apiKey: String,
        apiSecret: String,
        method: String,
        extra: Map<String, String>,
        parse: (org.w3c.dom.Document) -> LastFmAuthResult<T>?,
    ): LastFmAuthResult<T> {
        if (!LastFmAuthInput.isValid(apiKey) || !LastFmAuthInput.isValid(apiSecret)) {
            return LastFmAuthResult.Failure.InvalidInput
        }
        if (extra.values.any { !LastFmAuthInput.isValid(it) }) {
            return LastFmAuthResult.Failure.InvalidInput
        }

        val unsigned = mapOf("method" to method, "api_key" to apiKey) + extra
        val params = unsigned +
            ("api_sig" to LastFmSigning.signature(unsigned, apiSecret)) +
            ("format" to "xml")
        val request =
            ProviderHttpRequest(
                url = API_URL,
                method = ProviderHttpMethod.POST,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray(Charsets.UTF_8),
            )

        val response =
            try {
                transport.execute(request)
            } catch (_: IOException) {
                return LastFmAuthResult.Failure.Network
            }
        val envelope = LastFmAuthXml.parse(response)
        if (envelope is LastFmAuthXml.Envelope.ApiError) {
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
            is LastFmAuthXml.Envelope.ApiError ->
                LastFmAuthResult.Failure.Api(envelope.code, envelope.message, LastFmAuthFailureCode.from(envelope.code))
            is LastFmAuthXml.Envelope.Ok -> parse(envelope.document) ?: LastFmAuthResult.Failure.MalformedResponse
            LastFmAuthXml.Envelope.Malformed -> LastFmAuthResult.Failure.MalformedResponse
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
        data class Api(val code: Int, val message: String?, val kind: LastFmAuthFailureCode) : Failure
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

internal object LastFmAuthXml {
    private const val MAX_XML_BYTES = 64 * 1024

    sealed interface Envelope {
        data class Ok(val document: org.w3c.dom.Document) : Envelope
        data class ApiError(val code: Int, val message: String?) : Envelope
        data object Malformed : Envelope
    }

    fun parse(response: ProviderHttpResponse): Envelope {
        val body = response.body
        if (body.isEmpty() || body.size > MAX_XML_BYTES) return Envelope.Malformed
        if (body.any { it == 0.toByte() } || body.decodeToString().contains("<!")) return Envelope.Malformed

        val document =
            try {
                secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(body))
            } catch (_: SAXException) {
                return Envelope.Malformed
            } catch (_: IOException) {
                return Envelope.Malformed
            } catch (_: ParserConfigurationException) {
                return Envelope.Malformed
            } catch (_: RuntimeException) {
                return Envelope.Malformed
            }
        val root = document.documentElement ?: return Envelope.Malformed
        if (root.tagName != "lfm") return Envelope.Malformed
        return when (root.getAttribute("status")) {
            "ok" -> Envelope.Ok(document)
            "failed" -> {
                val error = root.directChild("error") ?: return Envelope.Malformed
                val code = error.getAttribute("code").toIntOrNull() ?: return Envelope.Malformed
                Envelope.ApiError(code, error.textContent?.trim()?.take(MAX_ERROR_MESSAGE_CHARS))
            }
            else -> Envelope.Malformed
        }
    }

    fun token(document: org.w3c.dom.Document): LastFmAuthResult<String>? {
        val root = document.documentElement ?: return null
        val token = root.directChild("token")?.textContent?.trim().orEmpty()
        return if (LastFmAuthInput.isValid(token)) LastFmAuthResult.Success(token) else LastFmAuthResult.Failure.MalformedResponse
    }

    fun session(document: org.w3c.dom.Document): Pair<String, String>? {
        val session = document.documentElement?.directChild("session") ?: return null
        val key = session.directChild("key")?.textContent?.trim().orEmpty()
        val name = session.directChild("name")?.textContent?.trim().orEmpty()
        return if (LastFmAuthInput.isValid(key) && LastFmAuthInput.isValid(name)) key to name else null
    }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun Element.directChild(name: String): Element? {
        for (index in 0 until childNodes.length) {
            val node = childNodes.item(index)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    private const val MAX_ERROR_MESSAGE_CHARS = 512
}
