/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayHealthProbe.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.relay

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator

/** A bounded, unauthenticated liveness check for a configured hosted Crew relay. */
class CrewRelayHealthProbe
@Inject
constructor(@CrewRelayHttpClient private val client: OkHttpClient) {
    suspend fun probe(locator: CrewRelayLocator): CrewRelayHealth {
        val request =
            runCatching { Request.Builder().url(CrewRelayHealthUrl.derive(locator)).get().build() }
                .getOrElse {
                    return CrewRelayHealth.InvalidResponse
                }

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        if (continuation.isActive) continuation.resume(CrewRelayHealth.Unreachable)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val health =
                            runCatching {
                                    response.use {
                                        CrewRelayHealthResponseParser.parse(
                                            response.code,
                                            response.readBoundedBody(),
                                        )
                                    }
                                }
                                .getOrElse { CrewRelayHealth.InvalidResponse }
                        if (continuation.isActive) continuation.resume(health)
                    }
                }
            )
        }
    }

    private fun Response.readBoundedBody(): String? {
        val body = body ?: return null
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (output.size() + read > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024
        const val REQUEST_TIMEOUT_SECONDS = 8L
    }
}

sealed interface CrewRelayHealth {
    data class Healthy(val sessions: Int, val routes: Int, val connections: Int) : CrewRelayHealth

    data object Unreachable : CrewRelayHealth

    data object InvalidResponse : CrewRelayHealth
}

/** Produces the public liveness endpoint without retaining a relay path, query, or fragment. */
object CrewRelayHealthUrl {
    fun derive(locator: CrewRelayLocator): String {
        val endpoint = java.net.URI(locator.value)
        return java.net.URI(endpoint.scheme, endpoint.authority, "/healthz", null, null).toString()
    }
}

/** Strict parser for the small relay health response. */
object CrewRelayHealthResponseParser {
    fun parse(statusCode: Int, body: String?): CrewRelayHealth {
        if (statusCode !in 200..299 || body == null) return CrewRelayHealth.InvalidResponse
        return runCatching {
                val response = JSONObject(body)
                if (response.opt("ok") !is Boolean || response.optBoolean("ok") != true) {
                    return CrewRelayHealth.InvalidResponse
                }
                CrewRelayHealth.Healthy(
                    sessions =
                        response.boundedCount("sessions") ?: return CrewRelayHealth.InvalidResponse,
                    routes =
                        response.boundedCount("routes") ?: return CrewRelayHealth.InvalidResponse,
                    connections =
                        response.boundedCount("connections")
                            ?: return CrewRelayHealth.InvalidResponse,
                )
            }
            .getOrElse { CrewRelayHealth.InvalidResponse }
    }

    private fun JSONObject.boundedCount(key: String): Int? =
        when (val value = opt(key)) {
            is Int -> value.takeIf { it in 0..MAX_COUNT }
            is Long -> value.takeIf { it in 0..MAX_COUNT.toLong() }?.toInt()
            else -> null
        }

    private const val MAX_COUNT = 1_000_000
}
