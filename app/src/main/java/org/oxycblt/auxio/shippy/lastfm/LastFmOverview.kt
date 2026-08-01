/*
 * Copyright (c) 2026 Auxio Project
 * LastFmOverview.kt is part of Auxio.
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

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

data class LastFmOverview(
    val username: String,
    val playCount: Long,
    val topTracks: List<LastFmOverviewTrack>,
) {
    init {
        require(username.isBoundedText())
        require(playCount >= 0)
        require(topTracks.size <= MAX_TRACKS)
    }

    companion object {
        const val MAX_TRACKS = 8
    }
}

data class LastFmOverviewTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val playCount: Long?,
) {
    init {
        require(title.isBoundedText() && artist.isBoundedText())
        require(album == null || album.isBoundedText())
        require(artworkUrl == null || artworkUrl.isHttpsUrl())
        require(playCount == null || playCount >= 0)
    }
}

sealed interface LastFmOverviewResult {
    data class Success(val overview: LastFmOverview) : LastFmOverviewResult

    sealed interface Failure : LastFmOverviewResult {
        data object Network : Failure

        data class Http(val statusCode: Int) : Failure

        data class Api(val code: Int, val message: String?) : Failure

        data object Malformed : Failure
    }
}

class LastFmOverviewClient @Inject constructor(private val transport: ProviderHttpTransport) {
    suspend fun load(credentials: LastFmCredentials): LastFmOverviewResult {
        val info =
            request("user.getInfo", credentials) ?: return LastFmOverviewResult.Failure.Network
        val infoEnvelope = LastFmOverviewJson.envelope(info)
        if (infoEnvelope is LastFmOverviewJson.Envelope.Api) return infoEnvelope.failure
        if (info.statusCode !in 200..299) return LastFmOverviewResult.Failure.Http(info.statusCode)
        val profile =
            (infoEnvelope as? LastFmOverviewJson.Envelope.Ok)?.profile
                ?: return LastFmOverviewResult.Failure.Malformed
        val tracks =
            request("user.getTopTracks", credentials) ?: return LastFmOverviewResult.Failure.Network
        val tracksEnvelope = LastFmOverviewJson.envelope(tracks)
        if (tracksEnvelope is LastFmOverviewJson.Envelope.Api) return tracksEnvelope.failure
        if (tracks.statusCode !in 200..299)
            return LastFmOverviewResult.Failure.Http(tracks.statusCode)
        val topTracks =
            (tracksEnvelope as? LastFmOverviewJson.Envelope.Ok)?.tracks
                ?: return LastFmOverviewResult.Failure.Malformed
        return LastFmOverviewResult.Success(
            LastFmOverview(profile.username, profile.playCount, topTracks)
        )
    }

    private suspend fun request(
        method: String,
        credentials: LastFmCredentials,
    ): ProviderHttpResponse? =
        try {
            transport.execute(
                ProviderHttpRequest(
                    "$API_URL?method=$method&api_key=${encode(credentials.apiKey)}" +
                        "&user=${encode(credentials.username)}" +
                        "&limit=${LastFmOverview.MAX_TRACKS}&format=json",
                    ProviderHttpMethod.GET,
                    headers =
                        mapOf("Accept" to "application/json", "User-Agent" to LAST_FM_USER_AGENT),
                )
            )
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val API_URL = "https://ws.audioscrobbler.com/2.0/"

        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}

internal object LastFmOverviewJson {
    private const val MAX_BODY_BYTES = 96 * 1024
    private const val MAX_ERROR_CHARS = 256

    data class Profile(val username: String, val playCount: Long)

    sealed interface Envelope {
        data class Ok(val profile: Profile? = null, val tracks: List<LastFmOverviewTrack>? = null) :
            Envelope

        data class Api(val failure: LastFmOverviewResult.Failure.Api) : Envelope

        data object Malformed : Envelope
    }

    fun profile(body: ByteArray): LastFmOverviewResult =
        when (val parsed = parse(body)) {
            is Envelope.Api -> parsed.failure
            is Envelope.Malformed -> LastFmOverviewResult.Failure.Malformed
            is Envelope.Ok ->
                parsed.profile?.let {
                    LastFmOverviewResult.Success(
                        LastFmOverview(it.username, it.playCount, emptyList())
                    )
                } ?: LastFmOverviewResult.Failure.Malformed
        }

    fun tracks(body: ByteArray): LastFmOverviewResult =
        when (val parsed = parse(body)) {
            is Envelope.Api -> parsed.failure
            is Envelope.Malformed -> LastFmOverviewResult.Failure.Malformed
            is Envelope.Ok ->
                parsed.tracks?.let { LastFmOverviewResult.Success(LastFmOverview("cache", 0, it)) }
                    ?: LastFmOverviewResult.Failure.Malformed
        }

    fun envelope(response: ProviderHttpResponse): Envelope = parse(response.body)

    private fun parse(body: ByteArray): Envelope {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES || body.any { it == 0.toByte() })
            return Envelope.Malformed
        val root =
            runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return Envelope.Malformed
        root.optJSONObject("error")?.let {
            return Envelope.Api(
                LastFmOverviewResult.Failure.Api(
                    it.optInt("code", -1),
                    it.optString("message").take(MAX_ERROR_CHARS).takeIf(String::isNotBlank),
                )
            )
        }
        root.opt("error")?.let {
            return Envelope.Api(
                LastFmOverviewResult.Failure.Api(
                    root.optInt("error", -1),
                    root.optString("message").take(MAX_ERROR_CHARS).takeIf(String::isNotBlank),
                )
            )
        }
        root.optJSONObject("user")?.let { user ->
            val username = user.optString("name").trim()
            val plays = user.optString("playcount").toLongOrNull()
            return if (username.isBoundedText() && plays != null && plays >= 0)
                Envelope.Ok(profile = Profile(username, plays))
            else Envelope.Malformed
        }
        val tracks =
            root.optJSONObject("toptracks")?.optJSONArray("track") ?: return Envelope.Malformed
        return Envelope.Ok(tracks = parseTracks(tracks) ?: return Envelope.Malformed)
    }

    private fun parseTracks(array: JSONArray): List<LastFmOverviewTrack>? = buildList {
        for (index in 0 until minOf(array.length(), LastFmOverview.MAX_TRACKS)) {
            val item = array.optJSONObject(index) ?: return null
            val title = item.optString("name").trim()
            val artist = item.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
            if (!title.isBoundedText() || !artist.isBoundedText()) return null
            val album =
                item
                    .optJSONObject("album")
                    ?.optString("#text")
                    ?.trim()
                    ?.takeIf(String::isBoundedText)
            val image =
                item.optJSONArray("image")?.let { images ->
                    (0 until images.length())
                        .mapNotNull(images::optJSONObject)
                        .map { it.optString("#text").trim() }
                        .lastOrNull(String::isHttpsUrl)
                }
            val plays = item.optString("playcount").toLongOrNull()?.takeIf { it >= 0 }
            add(LastFmOverviewTrack(title, artist, album, image, plays))
        }
    }
}

interface LastFmOverviewCache {
    suspend fun load(): LastFmOverview?

    suspend fun save(overview: LastFmOverview)

    suspend fun clear()
}

@Singleton
class AtomicLastFmOverviewCache @Inject constructor(@ApplicationContext context: Context) :
    LastFmOverviewCache {
    private val backing = File(context.filesDir, "lastfm-overview.json")
    private val file = AtomicFile(backing)

    override suspend fun load(): LastFmOverview? {
        if (!backing.exists()) return null
        return runCatching {
                require(backing.length() in 1..LastFmOverviewCacheCodec.MAX_CACHE_BYTES.toLong())
                LastFmOverviewCacheCodec.decode(
                    DataInputStream(file.openRead()).use { input ->
                        ByteArray(backing.length().toInt()).also(input::readFully)
                    }
                )
            }
            .getOrElse {
                file.delete()
                null
            }
    }

    override suspend fun save(overview: LastFmOverview) {
        val output = file.startWrite()
        try {
            output.write(LastFmOverviewCacheCodec.encode(overview))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    override suspend fun clear() {
        file.delete()
    }
}

internal object LastFmOverviewCacheCodec {
    const val MAX_CACHE_BYTES = 32 * 1024
    private const val VERSION = 1

    fun encode(value: LastFmOverview): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("u", value.username)
            .put("p", value.playCount)
            .put(
                "t",
                JSONArray().apply {
                    value.topTracks.forEach {
                        put(
                            JSONObject()
                                .put("n", it.title)
                                .put("a", it.artist)
                                .put("l", it.album)
                                .put("i", it.artworkUrl)
                                .put("p", it.playCount)
                        )
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_CACHE_BYTES) }

    fun decode(bytes: ByteArray): LastFmOverview {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CACHE_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("v") == VERSION)
        val username = root.getString("u")
        val plays = root.getLong("p")
        val tracks = root.getJSONArray("t")
        require(tracks.length() <= LastFmOverview.MAX_TRACKS)
        return LastFmOverview(
            username,
            plays,
            List(tracks.length()) { index ->
                tracks.getJSONObject(index).let {
                    LastFmOverviewTrack(
                        it.getString("n"),
                        it.getString("a"),
                        it.optString("l").takeIf(String::isNotBlank),
                        it.optString("i").takeIf(String::isHttpsUrl),
                        it.optString("p").toLongOrNull(),
                    )
                }
            },
        )
    }
}

private fun String.isBoundedText() = isNotBlank() && length <= 256 && none(Char::isISOControl)

private fun String.isHttpsUrl() = startsWith("https://") && length <= 2048
