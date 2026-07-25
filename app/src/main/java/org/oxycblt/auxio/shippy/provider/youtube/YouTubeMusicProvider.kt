/*
 * Copyright (c) 2026 Shippy contributors
 * YouTubeMusicProvider.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.provider.youtube

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpMethod
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

/** Anonymous, deliberately narrow YouTube Music metadata and direct-stream adapter. */
class YouTubeMusicProvider @Inject constructor(private val transport: ProviderHttpTransport) : MusicProvider {
    override val descriptor = ProviderDescriptor(ID, "YouTube Music", setOf(
        ProviderCapability.SEARCH, ProviderCapability.TRACK, ProviderCapability.STREAM,
        ProviderCapability.DOWNLOAD,
    ))

    private val bootstrapLock = Mutex()
    @Volatile private var bootstrap: Bootstrap? = null

    override fun health() = if (bootstrap == null) ProviderHealth.DEGRADED else ProviderHealth.AVAILABLE

    override suspend fun search(query: String, continuation: String?): ProviderResult<SearchPage> {
        if (query.isBlank()) return ProviderResult.Success(SearchPage(emptyList()))
        if (continuation != null) return ProviderResult.Failure(ProviderFailureKind.UNSUPPORTED, false, "YouTube Music continuation is not implemented")
        val session = bootstrap() ?: return ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, true, "YouTube Music bootstrap failed")
        val body = JSONObject().put("context", webRemixContext(session.clientVersion)).put("query", query.trim()).put("params", SONGS_FILTER).toString()
        val response = request("search", session, body) ?: return ProviderResult.Failure(ProviderFailureKind.NETWORK, true, "YouTube Music search failed")
        if (response.statusCode !in 200..299) return response.failure()
        return try {
            val tracks = parseSearch(JSONObject(response.bodyAsUtf8()))
            ProviderResult.Success(SearchPage(tracks))
        } catch (error: JSONException) {
            ProviderResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE, false, error.message)
        }
    }

    override suspend fun resolve(candidate: TrackCandidate, constraints: StreamConstraints): ProviderResult<ResolvedStream> {
        if (candidate.providerId != ID || candidate.kind != CandidateKind.PROVIDER) {
            return ProviderResult.Failure(ProviderFailureKind.UNSUPPORTED, false, "Candidate does not belong to YouTube Music")
        }
        val videoId = candidate.sourceItemId.takeIf(::validVideoId)
            ?: return ProviderResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE, false, "Missing YouTube video id")
        repeat(2) { attempt ->
            val session = bootstrap(forceRefresh = attempt == 1)
                ?: return ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, true, "YouTube Music bootstrap failed")
            val alternateClient = attempt == 1
            val response =
                request(
                    "player",
                    session,
                    playerBody(videoId, alternateClient),
                    clientHeaderName = if (alternateClient) IOS_CLIENT_HEADER else ANDROID_MUSIC_CLIENT_HEADER,
                )
                ?: return@repeat
            if (response.statusCode !in 200..299) return response.failure()
            try {
                val player = JSONObject(response.bodyAsUtf8())
                if (player.optJSONObject("playabilityStatus")?.optString("status") != "OK") {
                    if (attempt == 1) return ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, true, "YouTube video is not playable")
                    return@repeat
                }
                selectAudio(player.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats"), constraints)?.let {
                    return ProviderResult.Success(it.copy(candidateId = candidate.id))
                }
                return ProviderResult.Failure(ProviderFailureKind.UNSUPPORTED, false, "No direct HTTPS audio format is available")
            } catch (error: JSONException) {
                return ProviderResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE, false, error.message)
            }
        }
        return ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, true, "YouTube player request failed")
    }

    private suspend fun bootstrap(forceRefresh: Boolean = false): Bootstrap? = bootstrapLock.withLock {
        if (!forceRefresh) bootstrap?.takeIf { it.isFresh() }?.let { return it }
        val response = try { transport.execute(ProviderHttpRequest(HOME, headers = HOME_HEADERS)) } catch (_: IOException) { return null }
        if (response.statusCode !in 200..299) return null
        Bootstrap.extract(response.bodyAsUtf8())?.also { bootstrap = it }
    }

    private suspend fun request(
        endpoint: String,
        session: Bootstrap,
        body: String,
        clientHeaderName: String = WEB_REMIX_CLIENT_HEADER,
    ): ProviderHttpResponse? = try {
        transport.execute(ProviderHttpRequest(
            url = "$API/$endpoint?key=${URLEncoder.encode(session.apiKey, StandardCharsets.UTF_8.name())}",
            method = ProviderHttpMethod.POST,
            headers = jsonHeaders(session.visitorData, clientHeaderName),
            body = body.toByteArray(StandardCharsets.UTF_8),
        ))
    } catch (_: IOException) { null }

    private fun parseSearch(root: JSONObject): List<Track> = buildList {
        root.findObjects("musicResponsiveListItemRenderer").forEach { renderer -> parseSong(renderer)?.let(::add) }
    }

    private fun parseSong(item: JSONObject): Track? {
        val videoId = item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?.takeIf(::validVideoId) ?: return null
        val columns = item.optJSONArray("flexColumns") ?: return null
        val title = columns.optJSONObject(0)?.runsText()?.trim().orEmpty().takeIf(String::isNotBlank) ?: return null
        val subtitle =
            columns
                .optJSONObject(1)
                ?.runsTexts()
                ?.map(String::trim)
                ?.filter { it.isNotBlank() && it != "•" && it != "·" }
                ?: emptyList()
        val songIndex = subtitle.indexOfFirst { it.equals("Song", true) }
        if (songIndex == -1) return null
        val details = subtitle.drop(songIndex + 1).filterNot(::isDuration)
        val artist = details.firstOrNull() ?: return null
        val album = details.getOrNull(1)
        val duration = subtitle.lastOrNull(::isDuration)?.toDurationMs()
        val artwork = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { thumbs -> thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") }
        val trackId = TrackId("${ID.value}:$videoId")
        return Track(trackId, TrackRealm.PROVIDER, title, listOf(artist), album, duration, artwork = artwork,
            candidates = listOf(TrackCandidate(CandidateId("${ID.value}:$videoId"), trackId, CandidateKind.PROVIDER, ID.value, videoId, CandidateAvailability.RESOLVABLE, providerId = ID)))
    }

    private fun selectAudio(formats: JSONArray?, constraints: StreamConstraints): ResolvedStream? {
        val choices = buildList {
            for (index in 0 until (formats?.length() ?: 0)) {
                val format = formats?.optJSONObject(index) ?: continue
                val mime = format.optString("mimeType").substringBefore(';')
                val url = format.optString("url")
                if (mime.startsWith("audio/") && url.startsWith("https://") && !format.has("signatureCipher") && !format.has("cipher")) {
                    add(AudioChoice(url, mime, format.optInt("bitrate").takeIf { it > 0 }, format.optString("contentLength").toLongOrNull()))
                }
            }
        }
        if (choices.isEmpty()) return null
        val preferred = constraints.preferredBitrateBps
        val chosen = if (preferred == null) choices.maxByOrNull { it.bitrate ?: 0 } else choices.filter { (it.bitrate ?: 0) <= preferred }.maxByOrNull { it.bitrate ?: 0 } ?: choices.minByOrNull { it.bitrate ?: Int.MAX_VALUE }
        return chosen?.let { ResolvedStream(CandidateId("pending"), it.url, it.mime, it.bitrate, it.length, expiryFromUrl(it.url)) }
    }

    private fun JSONObject.findObjects(key: String): List<JSONObject> = buildList {
        fun walk(value: Any?) { when (value) { is JSONObject -> value.keys().forEach { name -> if (name == key) value.optJSONObject(name)?.let(::add); walk(value.opt(name)) }; is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i)) } }
        walk(this@findObjects)
    }
    private fun JSONObject.runsTexts(): List<String> = optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")?.let { runs -> List(runs.length()) { runs.optJSONObject(it)?.optString("text").orEmpty() } } ?: emptyList()
    private fun JSONObject.runsText() = runsTexts().joinToString("")
    private fun isDuration(value: String) = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$").matches(value)
    private fun String.toDurationMs(): Long? = split(':').mapNotNull(String::toLongOrNull).takeIf { it.size in 2..3 }?.let { it.fold(0L) { total, n -> total * 60 + n } * 1000 }
    private fun validVideoId(value: String) = value.matches(Regex("^[A-Za-z0-9_-]{6,}$"))
    private fun playerBody(videoId: String, alternate: Boolean) = JSONObject().put("context", if (alternate) iosContext() else androidMusicContext()).put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true).toString()
    private fun webRemixContext(version: String) = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version).put("hl", "en").put("gl", "US"))
    private fun androidMusicContext() = JSONObject().put("client", JSONObject().put("clientName", "ANDROID_MUSIC").put("clientVersion", "5.22.1").put("androidSdkVersion", 31).put("hl", "en"))
    private fun iosContext() = JSONObject().put("client", JSONObject().put("clientName", "IOS").put("clientVersion", "19.29.1").put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("hl", "en"))
    private fun jsonHeaders(visitor: String, clientHeaderName: String) =
        HOME_HEADERS +
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Origin" to HOME,
                "X-Origin" to HOME,
                "X-YouTube-Client-Name" to clientHeaderName,
                "X-Goog-Visitor-Id" to visitor,
            )
    private fun ProviderHttpResponse.failure() = when (statusCode) { 401, 403 -> ProviderResult.Failure(ProviderFailureKind.AUTHENTICATION, false, "YouTube rejected the request ($statusCode)"); 429 -> ProviderResult.Failure(ProviderFailureKind.RATE_LIMITED, true, "YouTube rate limited the request"); in 500..599 -> ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, true, "YouTube is unavailable ($statusCode)"); else -> ProviderResult.Failure(ProviderFailureKind.UNAVAILABLE, false, "YouTube request failed ($statusCode)") }

    data class Bootstrap(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String,
        private val fetchedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(nowEpochMs: Long = System.currentTimeMillis()) = nowEpochMs - fetchedAtEpochMs < TTL_MS
        companion object {
            private const val TTL_MS = 15 * 60 * 1_000L
            fun extract(html: String): Bootstrap? {
                val start = Regex("ytcfg\\.set\\s*\\(\\s*\\{").find(html)?.range?.last ?: return null
                val objectText = html.jsonObjectFrom(start) ?: return null
                val config = runCatching { JSONObject(objectText) }.getOrNull() ?: return null
                return Bootstrap(config.optString("INNERTUBE_API_KEY"), config.optString("INNERTUBE_CONTEXT_CLIENT_VERSION"), config.optString("VISITOR_DATA")).takeIf { it.apiKey.isNotBlank() && it.clientVersion.isNotBlank() && it.visitorData.isNotBlank() }
            }

            private fun String.jsonObjectFrom(start: Int): String? {
                var depth = 0; var quoted = false; var escaped = false
                for (index in start until length) {
                    val char = this[index]
                    if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false; continue }
                    when (char) { '"' -> quoted = true; '{' -> depth++; '}' -> if (--depth == 0) return substring(start, index + 1) }
                }
                return null
            }
        }
    }
    private data class AudioChoice(val url: String, val mime: String, val bitrate: Int?, val length: Long?)
    companion object {
        val ID = ProviderId("youtube_music")
        const val HOME = "https://music.youtube.com"
        const val API = "https://music.youtube.com/youtubei/v1"
        const val SONGS_FILTER = "EgWKAQIIAWoKEAUQCRADEAoYBA%3D%3D"
        const val WEB_REMIX_CLIENT_HEADER = "67"
        const val ANDROID_MUSIC_CLIENT_HEADER = "21"
        const val IOS_CLIENT_HEADER = "5"
        val HOME_HEADERS = mapOf("Accept" to "text/html,application/json", "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
        fun expiryFromUrl(url: String): Long? = runCatching { URI(url).query?.split('&')?.firstOrNull { it.substringBefore('=') == "expire" }?.substringAfter('=')?.toLongOrNull()?.times(1000) }.getOrNull()
    }
}
