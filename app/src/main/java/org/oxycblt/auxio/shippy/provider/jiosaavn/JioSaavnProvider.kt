/*
 * Copyright (c) 2026 Auxio Project
 * JioSaavnProvider.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.jiosaavn

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderBrowsePage
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class JioSaavnProvider @Inject constructor(private val transport: ProviderHttpTransport) :
    MusicProvider {
    override val descriptor =
        ProviderDescriptor(
            id = ID,
            displayName = "JioSaavn",
            capabilities =
                setOf(
                    ProviderCapability.SEARCH,
                    ProviderCapability.TRACK,
                    ProviderCapability.ALBUM,
                    ProviderCapability.ARTIST,
                    ProviderCapability.PLAYLIST,
                    ProviderCapability.STREAM,
                    ProviderCapability.DOWNLOAD,
                ),
        )

    override fun health() = ProviderHealth.AVAILABLE

    override suspend fun probeHealth(): ProviderHealth =
        when (val result = search("music", null)) {
            is ProviderResult.Success ->
                if (result.value.tracks.isEmpty()) ProviderHealth.DEGRADED
                else ProviderHealth.AVAILABLE
            is ProviderResult.Failure ->
                when (result.kind) {
                    ProviderFailureKind.NETWORK,
                    ProviderFailureKind.RATE_LIMITED -> ProviderHealth.DEGRADED
                    else -> ProviderHealth.UNAVAILABLE
                }
        }

    override suspend fun search(query: String, continuation: String?): ProviderResult<SearchPage> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return ProviderResult.Success(SearchPage(emptyList()))
        }
        val page = continuation?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        return supervisorScope {
            val entitySearch = if (page == 1) async { searchEntities(normalizedQuery) } else null
            val response =
                try {
                    request(searchUrl(normalizedQuery, page))
                } catch (error: IOException) {
                    entitySearch?.cancel()
                    return@supervisorScope networkFailure(error)
                }
            if (response.statusCode !in 200..299) {
                entitySearch?.cancel()
                return@supervisorScope response.statusFailure()
            }

            try {
                val resultArray = JSONObject(response.bodyAsUtf8()).songResults()
                val tracks = buildList {
                    for (index in 0 until resultArray.length()) {
                        val responseMap = resultArray.optJSONObject(index)?.toMap() ?: continue
                        runCatching { JioSaavnResponseMapper.mapSong(responseMap).toTrack() }
                            .getOrNull()
                            ?.let(::add)
                    }
                }
                if (resultArray.length() > 0 && tracks.isEmpty()) {
                    entitySearch?.cancel()
                    ProviderResult.Failure(
                        kind = ProviderFailureKind.MALFORMED_RESPONSE,
                        retryable = false,
                        message = "JioSaavn returned no usable song records",
                    )
                } else {
                    ProviderResult.Success(
                        SearchPage(
                            tracks = tracks,
                            entities = entitySearch?.await().orEmpty(),
                            continuation =
                                if (resultArray.length() >= PAGE_SIZE) "${page + 1}" else null,
                        )
                    )
                }
            } catch (error: JSONException) {
                entitySearch?.cancel()
                ProviderResult.Failure(
                    kind = ProviderFailureKind.MALFORMED_RESPONSE,
                    retryable = false,
                    message = error.message,
                )
            }
        }
    }

    override suspend fun browse(
        entity: ProviderEntity,
        continuation: String?,
    ): ProviderResult<ProviderBrowsePage> {
        if (entity.providerId != ID) {
            return ProviderResult.Failure(
                ProviderFailureKind.UNSUPPORTED,
                retryable = false,
                message = "Entity does not belong to JioSaavn",
            )
        }
        val page =
            continuation?.toIntOrNull()?.takeIf {
                entity.type == ProviderEntityType.ARTIST && it in 0..MAX_BROWSE_PAGE
            } ?: 0
        val response =
            try {
                request(browseUrl(entity, page))
            } catch (error: IOException) {
                return networkFailure(error)
            }
        if (response.statusCode !in 200..299) return response.statusFailure()
        return try {
            val body = JSONObject(response.bodyAsUtf8())
            val tracks =
                body.browseSongResults(entity.type).mapNotNull { responseMap ->
                    runCatching { JioSaavnResponseMapper.mapSong(responseMap).toTrack() }
                        .getOrNull()
                }
            ProviderResult.Success(
                ProviderBrowsePage(
                    entity = body.updatedEntity(entity),
                    tracks = tracks,
                    continuation =
                        if (
                            entity.type == ProviderEntityType.ARTIST &&
                                tracks.size >= PAGE_SIZE &&
                                page < MAX_BROWSE_PAGE
                        ) {
                            "${page + 1}"
                        } else {
                            null
                        },
                )
            )
        } catch (error: JSONException) {
            ProviderResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE, false, error.message)
        }
    }

    override suspend fun resolve(
        candidate: TrackCandidate,
        constraints: StreamConstraints,
    ): ProviderResult<ResolvedStream> {
        if (candidate.providerId != ID || candidate.kind != CandidateKind.PROVIDER) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.UNSUPPORTED,
                retryable = false,
                message = "Candidate does not belong to JioSaavn",
            )
        }
        val baseUrl =
            candidate.locator?.takeIf(String::isNotBlank) ?: resolveSongMediaUrl(candidate)
        if (baseUrl == null) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.UNAVAILABLE,
                retryable = true,
                message = "JioSaavn stream URL is unavailable",
            )
        }
        val quality = selectQuality(constraints.preferredBitrateBps, candidate.media?.bitrateBps)
        return ProviderResult.Success(
            ResolvedStream(
                candidateId = candidate.id,
                uri = JioSaavnMediaUrl.selectQuality(baseUrl, quality),
                mimeType = candidate.media?.mimeType ?: "audio/mp4",
                bitrateBps = quality.bitrateKbps * 1_000,
            )
        )
    }

    private fun searchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return "$API_ENDPOINT&__call=search.getResults&p=$page&q=$encoded&n=$PAGE_SIZE"
    }

    private suspend fun searchEntities(query: String): List<ProviderEntity> = supervisorScope {
        ProviderEntityType.entries
            .map { type ->
                async {
                    try {
                        val response = request(entitySearchUrl(query, type))
                        if (response.statusCode !in 200..299) return@async emptyList()
                        JSONObject(response.bodyAsUtf8())
                            .entityResults()
                            .mapNotNull { it.toProviderEntity(type) }
                            .take(ENTITY_PAGE_SIZE)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            .flatMap { it.await() }
    }

    private fun entitySearchUrl(query: String, type: ProviderEntityType): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val call =
            when (type) {
                ProviderEntityType.ALBUM -> "search.getAlbumResults"
                ProviderEntityType.ARTIST -> "search.getArtistResults"
                ProviderEntityType.PLAYLIST -> "search.getPlaylistResults"
            }
        return "$API_ENDPOINT&__call=$call&q=$encoded&n=$ENTITY_PAGE_SIZE"
    }

    private fun browseUrl(entity: ProviderEntity, page: Int): String {
        val token = URLEncoder.encode(entity.sourceItemId, StandardCharsets.UTF_8.name())
        val type = entity.type.name.lowercase()
        val extra =
            if (entity.type == ProviderEntityType.ARTIST) {
                "&p=$page&n_song=$PAGE_SIZE&n_album=0"
            } else {
                ""
            }
        return "$API_ENDPOINT&__call=webapi.get&token=$token&type=$type$extra"
    }

    private suspend fun request(url: String) =
        transport.execute(ProviderHttpRequest(url = url, headers = REQUEST_HEADERS))

    private fun networkFailure(error: IOException) =
        ProviderResult.Failure(
            kind = ProviderFailureKind.NETWORK,
            retryable = true,
            message = error.message,
        )

    /**
     * Deep links carry provenance but never an expiring media locator, so look up this exact song.
     */
    private suspend fun resolveSongMediaUrl(candidate: TrackCandidate): String? {
        val sourceItemId = candidate.sourceItemId.trim()
        if (sourceItemId.isEmpty()) return null
        val encoded = URLEncoder.encode(sourceItemId, StandardCharsets.UTF_8.name())
        val response =
            try {
                transport.execute(
                    ProviderHttpRequest(
                        url = "$API_ENDPOINT&__call=song.getDetails&pids=$encoded",
                        headers = REQUEST_HEADERS,
                    )
                )
            } catch (_: IOException) {
                return null
            }
        if (response.statusCode !in 200..299) return null
        return runCatching {
                val body = JSONObject(response.bodyAsUtf8())
                val song =
                    body.optJSONArray("songs")?.optJSONObject(0)
                        ?: body.optJSONObject("data")?.optJSONArray("songs")?.optJSONObject(0)
                        ?: body.optJSONObject(sourceItemId)
                        ?: return null
                val mapped = JioSaavnResponseMapper.mapSong(song.toMap())
                mapped.takeIf { it.id == sourceItemId }?.mediaUrl
            }
            .getOrNull()
    }

    private fun selectQuality(preferredBps: Int?, maximumBps: Int?): JioSaavnQuality {
        val requested = preferredBps ?: maximumBps ?: JioSaavnQuality.MEDIUM.bitrateKbps * 1_000
        return when {
            requested <= JioSaavnQuality.LOW.bitrateKbps * 1_000 -> JioSaavnQuality.LOW
            requested <= JioSaavnQuality.MEDIUM.bitrateKbps * 1_000 -> JioSaavnQuality.MEDIUM
            maximumBps != null && maximumBps < JioSaavnQuality.HIGH.bitrateKbps * 1_000 ->
                JioSaavnQuality.MEDIUM
            else -> JioSaavnQuality.HIGH
        }
    }

    private fun JioSaavnSong.toTrack(): Track {
        val trackId = TrackId("${ID.value}:$id")
        val maximumBitrate = if (supports320Kbps == true) 320_000 else 160_000
        return Track(
            id = trackId,
            realm = TrackRealm.PROVIDER,
            title = title,
            artists = listOf(artist),
            album = album,
            durationMs = durationSeconds?.times(1_000),
            artwork = artworkUrl,
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("${ID.value}:$id"),
                        trackId = trackId,
                        kind = CandidateKind.PROVIDER,
                        sourceId = ID.value,
                        sourceItemId = id,
                        availability =
                            if (mediaUrl == null) {
                                CandidateAvailability.UNAVAILABLE
                            } else {
                                CandidateAvailability.RESOLVABLE
                            },
                        locator = mediaUrl,
                        providerId = ID,
                        media =
                            MediaDescriptor(
                                mimeType = "audio/mp4",
                                container = "m4a",
                                bitrateBps = maximumBitrate,
                            ),
                    )
                ),
        )
    }

    private fun JSONObject.songResults(): JSONArray =
        optJSONArray("results")
            ?: optJSONObject("data")?.optJSONArray("results")
            ?: optJSONObject("songs")?.optJSONArray("results")
            ?: JSONArray()

    private fun JSONObject.entityResults(): List<Map<String, Any?>> {
        val results =
            optJSONArray("results") ?: optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
        return List(results.length()) { index -> results.optJSONObject(index)?.toMap() }
            .filterNotNull()
    }

    private fun JSONObject.browseSongResults(type: ProviderEntityType): List<Map<String, Any?>> {
        val container = optJSONObject("data") ?: this
        val results =
            when (type) {
                ProviderEntityType.ARTIST -> container.optJSONArray("topSongs")
                ProviderEntityType.ALBUM,
                ProviderEntityType.PLAYLIST -> container.optJSONArray("list")
            } ?: JSONArray()
        return List(results.length()) { index -> results.optJSONObject(index)?.toMap() }
            .filterNotNull()
    }

    private fun JSONObject.updatedEntity(fallback: ProviderEntity): ProviderEntity {
        val source = optJSONObject("data") ?: this
        val map = source.toMap()
        return map.toProviderEntity(fallback.type) ?: fallback
    }

    private fun Map<String, Any?>.toProviderEntity(type: ProviderEntityType): ProviderEntity? {
        val originalUrl = text("perma_url")?.trim()?.takeIf(::isHttpsUrl) ?: return null
        val sourceItemId = browseToken(originalUrl) ?: return null
        val title =
            sequenceOf(text("title"), text("name"), text("album"))
                .mapNotNull { it?.decodeEntities()?.takeIf(String::isNotBlank) }
                .firstOrNull() ?: return null
        val subtitle =
            sequenceOf(text("subtitle"), text("role"), text("description"), text("artist"))
                .mapNotNull { it?.decodeEntities()?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
        val artwork =
            text("image")
                ?.trim()
                ?.takeIf(::isHttpsUrl)
                ?.let(JioSaavnResponseMapper::normalizeArtworkUrl)
        return runCatching {
                ProviderEntity(
                    providerId = ID,
                    sourceItemId = sourceItemId,
                    type = type,
                    title = title,
                    subtitle = subtitle,
                    artwork = artwork,
                    originalUrl = originalUrl,
                )
            }
            .getOrNull()
    }

    private fun browseToken(url: String): String? =
        runCatching { URI(url).path.trimEnd('/').substringAfterLast('/').trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= 512 && !it.all(Char::isDigit) }

    private fun isHttpsUrl(value: String): Boolean =
        runCatching {
                URI(value).scheme.equals("https", ignoreCase = true) &&
                    !URI(value).host.isNullOrBlank()
            }
            .getOrDefault(false)

    private fun String.decodeEntities() =
        replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .trim()

    private fun Map<String, Any?>.text(key: String): String? =
        get(key)?.toString()?.takeUnless { it == "null" }

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key -> get(key).toKotlinValue() }

    private fun JSONArray.toList(): List<Any?> =
        List(length()) { index -> get(index).toKotlinValue() }

    private fun Any?.toKotlinValue(): Any? =
        when (this) {
            JSONObject.NULL -> null
            is JSONObject -> toMap()
            is JSONArray -> toList()
            else -> this
        }

    private fun org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse.statusFailure():
        ProviderResult.Failure =
        when (statusCode) {
            401,
            403 ->
                ProviderResult.Failure(
                    ProviderFailureKind.AUTHENTICATION,
                    retryable = false,
                    message = "JioSaavn rejected the request ($statusCode)",
                )
            429 ->
                ProviderResult.Failure(
                    ProviderFailureKind.RATE_LIMITED,
                    retryable = true,
                    message = "JioSaavn rate limited the request",
                )
            in 500..599 ->
                ProviderResult.Failure(
                    ProviderFailureKind.UNAVAILABLE,
                    retryable = true,
                    message = "JioSaavn is unavailable ($statusCode)",
                )
            else ->
                ProviderResult.Failure(
                    ProviderFailureKind.UNAVAILABLE,
                    retryable = false,
                    message = "JioSaavn request failed ($statusCode)",
                )
        }

    private companion object {
        val ID = ProviderId("jiosaavn")
        const val PAGE_SIZE = 20
        const val ENTITY_PAGE_SIZE = 5
        const val MAX_BROWSE_PAGE = 7
        const val API_ENDPOINT =
            "https://www.jiosaavn.com/api.php?_format=json&_marker=0&api_version=4&ctx=web6dot0"
        val REQUEST_HEADERS =
            mapOf(
                "Accept" to "application/json",
                "Cookie" to "L=Hindi",
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36",
            )
    }
}
