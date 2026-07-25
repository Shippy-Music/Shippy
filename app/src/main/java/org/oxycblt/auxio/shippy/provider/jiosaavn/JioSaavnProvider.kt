/*
 * Copyright (c) 2026 Shippy contributors
 * JioSaavnProvider.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.jiosaavn

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
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
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class JioSaavnProvider
@Inject
constructor(
    private val transport: ProviderHttpTransport,
) : MusicProvider {
    override val descriptor =
        ProviderDescriptor(
            id = ID,
            displayName = "JioSaavn",
            capabilities =
                setOf(
                    ProviderCapability.SEARCH,
                    ProviderCapability.TRACK,
                    ProviderCapability.STREAM,
                    ProviderCapability.DOWNLOAD,
                ),
        )

    override fun health() = ProviderHealth.AVAILABLE

    override suspend fun search(
        query: String,
        continuation: String?,
    ): ProviderResult<SearchPage> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return ProviderResult.Success(SearchPage(emptyList()))
        }
        val page = continuation?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val response =
            try {
                transport.execute(
                    ProviderHttpRequest(
                        url = searchUrl(normalizedQuery, page),
                        headers = REQUEST_HEADERS,
                    )
                )
            } catch (error: IOException) {
                return ProviderResult.Failure(
                    kind = ProviderFailureKind.NETWORK,
                    retryable = true,
                    message = error.message,
                )
            }
        if (response.statusCode !in 200..299) {
            return response.statusFailure()
        }

        return try {
            val resultArray = JSONObject(response.bodyAsUtf8()).songResults()
            val tracks =
                buildList {
                    for (index in 0 until resultArray.length()) {
                        val responseMap = resultArray.optJSONObject(index)?.toMap() ?: continue
                        runCatching { JioSaavnResponseMapper.mapSong(responseMap).toTrack() }
                            .getOrNull()
                            ?.let(::add)
                    }
                }
            if (resultArray.length() > 0 && tracks.isEmpty()) {
                ProviderResult.Failure(
                    kind = ProviderFailureKind.MALFORMED_RESPONSE,
                    retryable = false,
                    message = "JioSaavn returned no usable song records",
                )
            } else {
                ProviderResult.Success(
                    SearchPage(
                        tracks = tracks,
                        continuation = if (resultArray.length() >= PAGE_SIZE) "${page + 1}" else null,
                    )
                )
            }
        } catch (error: JSONException) {
            ProviderResult.Failure(
                kind = ProviderFailureKind.MALFORMED_RESPONSE,
                retryable = false,
                message = error.message,
            )
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
            candidate.locator?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(
                    kind = ProviderFailureKind.UNAVAILABLE,
                    retryable = true,
                    message = "JioSaavn stream URL is unavailable",
                )
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

    private fun selectQuality(preferredBps: Int?, maximumBps: Int?): JioSaavnQuality {
        val requested = preferredBps ?: maximumBps ?: JioSaavnQuality.MEDIUM.bitrateKbps * 1_000
        return when {
            requested <= JioSaavnQuality.LOW.bitrateKbps * 1_000 -> JioSaavnQuality.LOW
            requested <= JioSaavnQuality.MEDIUM.bitrateKbps * 1_000 ->
                JioSaavnQuality.MEDIUM
            maximumBps != null &&
                maximumBps < JioSaavnQuality.HIGH.bitrateKbps * 1_000 ->
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
            401, 403 ->
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
