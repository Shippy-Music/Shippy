/*
 * Copyright (c) 2026 Auxio Project
 * ProviderHttpTransport.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ProviderHttpMethod {
    GET,
    POST,
}

data class ProviderHttpRequest(
    val url: String,
    val method: ProviderHttpMethod = ProviderHttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    init {
        require(url.startsWith("https://")) { "Provider requests must use HTTPS" }
        require(method == ProviderHttpMethod.POST || body == null) {
            "Only POST requests may contain a body"
        }
    }
}

data class ProviderHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun bodyAsUtf8(): String = body.toString(StandardCharsets.UTF_8)
}

interface ProviderHttpTransport {
    @Throws(IOException::class)
    suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse
}

class DefaultProviderHttpTransport @Inject constructor() : ProviderHttpTransport {
    override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method.name
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                request.headers.forEach(connection::setRequestProperty)
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }

                val statusCode = connection.responseCode
                val stream =
                    if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                ProviderHttpResponse(
                    statusCode = statusCode,
                    headers =
                        connection.headerFields.entries
                            .mapNotNull { (key, values) ->
                                if (key != null && values != null) key to values else null
                            }
                            .toMap(),
                    body = stream?.use { it.readBounded(MAX_RESPONSE_BYTES) } ?: ByteArray(0),
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IOException("Provider response exceeded $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
