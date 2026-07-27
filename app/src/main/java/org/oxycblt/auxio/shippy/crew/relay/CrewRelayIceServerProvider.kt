/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayIceServerProvider.kt is part of Auxio.
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.runtime.DEFAULT_CREW_REMOTE_ICE_SERVERS
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceServer

/** Retrieves short-lived relay TURN credentials without ever sending an invite secret. */
class CrewRelayIceServerProvider
@Inject
constructor(@CrewRelayHttpClient private val client: OkHttpClient) {
    private val nowEpochMs: () -> Long = System::currentTimeMillis

    suspend fun resolve(invite: CrewInvite): List<CrewIceServer> {
        val locator = invite.relayLocator ?: return DEFAULT_CREW_REMOTE_ICE_SERVERS
        val request =
            runCatching {
                    val payload =
                        JSONObject()
                            .put("protocolVersion", invite.protocolVersion.value)
                            .put("sessionLocator", invite.sessionLocator.value)
                            .put("inviteId", invite.inviteId.value)
                    Request.Builder()
                        .url(CrewRelayIceUrl.derive(locator))
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                }
                .getOrElse {
                    return DEFAULT_CREW_REMOTE_ICE_SERVERS
                }

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        if (continuation.isActive)
                            continuation.resume(DEFAULT_CREW_REMOTE_ICE_SERVERS)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val iceServers =
                            runCatching {
                                    response.use {
                                        CrewRelayIceResponseParser.parse(
                                            statusCode = response.code,
                                            body = response.readBoundedBody(),
                                            nowEpochMs = nowEpochMs(),
                                        )
                                    }
                                }
                                .getOrNull()
                        if (continuation.isActive) {
                            continuation.resume(
                                iceServers?.let { DEFAULT_CREW_REMOTE_ICE_SERVERS + it }
                                    ?: DEFAULT_CREW_REMOTE_ICE_SERVERS
                            )
                        }
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val REQUEST_TIMEOUT_SECONDS = 8L
        const val MAX_RESPONSE_BYTES = 4 * 1024L
    }
}

/** Produces the credential endpoint from only the configured relay origin. */
object CrewRelayIceUrl {
    fun derive(locator: CrewRelayLocator): String {
        val endpoint = java.net.URI(locator.value)
        return java.net.URI(endpoint.scheme, endpoint.authority, "/v1/ice", null, null).toString()
    }
}

/** Strict parser for the bounded, short-lived TURN credential response. */
object CrewRelayIceResponseParser {
    fun parse(statusCode: Int, body: String?, nowEpochMs: Long): List<CrewIceServer>? {
        if (statusCode !in 200..299 || body == null || nowEpochMs < 0L) return null
        return runCatching {
                val response = JSONObject(body)
                if (!response.hasExactlyKeys("iceServers", "expiresAtEpochMs")) return null
                val expiresAtEpochMs = response.strictEpochMs("expiresAtEpochMs") ?: return null
                if (
                    expiresAtEpochMs <= nowEpochMs ||
                        expiresAtEpochMs - nowEpochMs > MAX_CREDENTIAL_LIFETIME_MS
                ) {
                    return null
                }
                val servers = response.opt("iceServers") as? JSONArray ?: return null
                if (servers.length() !in 1..MAX_ICE_SERVERS) return null
                buildList {
                    repeat(servers.length()) { index ->
                        val server = servers.opt(index) as? JSONObject ?: return null
                        if (!server.hasExactlyKeys("urls", "username", "credential")) return null
                        val urls = server.opt("urls") as? JSONArray ?: return null
                        if (urls.length() !in 1..MAX_URLS_PER_SERVER) return null
                        val parsedUrls = buildList {
                            repeat(urls.length()) { urlIndex ->
                                val url = urls.opt(urlIndex) as? String ?: return null
                                if (
                                    url.length > MAX_URL_LENGTH ||
                                        !(url.startsWith("turn:") || url.startsWith("turns:"))
                                ) {
                                    return null
                                }
                                add(url)
                            }
                        }
                        val username =
                            server.strictString("username", MAX_USERNAME_LENGTH) ?: return null
                        val credential =
                            server.strictString("credential", MAX_CREDENTIAL_LENGTH) ?: return null
                        if (username.isEmpty() || credential.isEmpty()) return null
                        add(CrewIceServer(parsedUrls, username, credential))
                    }
                }
            }
            .getOrNull()
    }

    private fun JSONObject.strictEpochMs(key: String): Long? =
        when (val value = opt(key)) {
            is Int -> value.toLong().takeIf { it >= 0L }
            is Long -> value.takeIf { it >= 0L }
            else -> null
        }

    private fun JSONObject.strictString(key: String, maxLength: Int): String? =
        (opt(key) as? String)?.takeIf { it.length in 1..maxLength }

    private const val MAX_ICE_SERVERS = 4
    private const val MAX_URLS_PER_SERVER = 4
    private const val MAX_URL_LENGTH = 512
    private const val MAX_USERNAME_LENGTH = 256
    private const val MAX_CREDENTIAL_LENGTH = 512
    private const val MAX_CREDENTIAL_LIFETIME_MS = 60 * 60 * 1000L
}

private fun JSONObject.hasExactlyKeys(vararg expected: String): Boolean {
    val actual = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) actual += iterator.next()
    return actual == expected.toSet()
}
