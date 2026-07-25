/*
 * Copyright (c) 2026 Shippy contributors
 * LyricsRepository.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.lyrics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

data class LyricsRequest(
    val trackId: TrackId,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
) {
    init {
        require(title.isNotBlank()) { "Lyrics title cannot be blank" }
        require(artists.none(String::isBlank)) { "Lyrics artists cannot contain blank values" }
        require(durationMs == null || durationMs >= 0) {
            "Lyrics duration cannot be negative"
        }
    }
}

data class LyricsRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSeconds: Double?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
) {
    init {
        require(id >= 0) { "Lyrics ID cannot be negative" }
        require(trackName.isNotBlank()) { "Lyrics track name cannot be blank" }
        require(artistName.isNotBlank()) { "Lyrics artist name cannot be blank" }
        require(durationSeconds == null || durationSeconds >= 0) {
            "Lyrics duration cannot be negative"
        }
    }

    fun parse(): ParsedLyrics? {
        if (instrumental) return PlainLyrics("")
        syncedLyrics?.takeIf(String::isNotBlank)?.let { synced ->
            val parsed = LrcParser.parse(synced)
            if (parsed is SyncedLyrics && parsed.lines.isNotEmpty()) return parsed
        }
        return plainLyrics?.takeIf(String::isNotBlank)?.let(::PlainLyrics)
    }
}

sealed interface LyricsLookupResult {
    data class Found(
        val record: LyricsRecord,
        val lyrics: ParsedLyrics,
    ) : LyricsLookupResult

    data object NotFound : LyricsLookupResult

    data class Failure(
        val kind: LyricsFailureKind,
        val retryable: Boolean,
        val message: String? = null,
    ) : LyricsLookupResult
}

enum class LyricsFailureKind {
    NETWORK,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    MALFORMED_RESPONSE,
}

interface LyricsRepository {
    suspend fun lookup(request: LyricsRequest): LyricsLookupResult
}

interface LyricsSource {
    val id: String
    val priority: Int

    suspend fun lookup(request: LyricsRequest): LyricsLookupResult
}

@Singleton
class ChainedLyricsRepository
@Inject
constructor(
    sources: Set<@JvmSuppressWildcards LyricsSource>,
) : LyricsRepository {
    private val sources = sources.sortedWith(compareBy(LyricsSource::priority, LyricsSource::id))

    override suspend fun lookup(request: LyricsRequest): LyricsLookupResult {
        var lastFailure: LyricsLookupResult.Failure? = null
        sources.forEach { source ->
            when (val result = source.lookup(request)) {
                is LyricsLookupResult.Found -> return result
                LyricsLookupResult.NotFound -> Unit
                is LyricsLookupResult.Failure -> lastFailure = result
            }
        }
        return lastFailure ?: LyricsLookupResult.NotFound
    }
}

@Singleton
class LrclibLyricsRepository
@Inject
constructor(
    private val transport: ProviderHttpTransport,
) : LyricsSource {
    override val id = "lrclib"
    override val priority = 100

    override suspend fun lookup(request: LyricsRequest): LyricsLookupResult {
        val exactUrl = exactUrl(request)
        if (exactUrl != null) {
            when (val exact = execute(exactUrl)) {
                is HttpLyricsResult.Records -> {
                    selectBestLyrics(request, exact.records)?.toFound()?.let { return it }
                }
                HttpLyricsResult.NotFound -> Unit
                is HttpLyricsResult.Failure -> return exact.value
            }
        }

        return when (val search = execute(searchUrl(request))) {
            is HttpLyricsResult.Records ->
                selectBestLyrics(request, search.records)?.toFound()
                    ?: LyricsLookupResult.NotFound
            HttpLyricsResult.NotFound -> LyricsLookupResult.NotFound
            is HttpLyricsResult.Failure -> search.value
        }
    }

    private suspend fun execute(url: String): HttpLyricsResult {
        val response =
            try {
                transport.execute(
                    ProviderHttpRequest(
                        url = url,
                        headers =
                            mapOf(
                                "Accept" to "application/json",
                                "User-Agent" to CLIENT_USER_AGENT,
                            ),
                    )
                )
            } catch (error: IOException) {
                return HttpLyricsResult.Failure(
                    LyricsLookupResult.Failure(
                        kind = LyricsFailureKind.NETWORK,
                        retryable = true,
                        message = error.message,
                    )
                )
            }
        if (response.statusCode == 404) return HttpLyricsResult.NotFound
        if (response.statusCode !in 200..299) {
            return HttpLyricsResult.Failure(response.toFailure())
        }
        return try {
            val body = response.bodyAsUtf8().trim()
            val records =
                if (body.startsWith("[")) {
                    JSONArray(body).toRecords()
                } else {
                    listOf(JSONObject(body).toRecord())
                }
            HttpLyricsResult.Records(records)
        } catch (error: JSONException) {
            HttpLyricsResult.Failure(
                LyricsLookupResult.Failure(
                    kind = LyricsFailureKind.MALFORMED_RESPONSE,
                    retryable = false,
                    message = error.message,
                )
            )
        } catch (error: IllegalArgumentException) {
            HttpLyricsResult.Failure(
                LyricsLookupResult.Failure(
                    kind = LyricsFailureKind.MALFORMED_RESPONSE,
                    retryable = false,
                    message = error.message,
                )
            )
        }
    }

    private fun exactUrl(request: LyricsRequest): String? {
        val artist = request.artists.firstOrNull() ?: return null
        val album = request.album?.takeIf(String::isNotBlank) ?: return null
        val duration = request.durationMs?.div(1000) ?: return null
        return "$BASE_URL/api/get?" +
            query(
                "track_name" to request.title,
                "artist_name" to artist,
                "album_name" to album,
                "duration" to duration.toString(),
            )
    }

    private fun searchUrl(request: LyricsRequest): String {
        val artist = request.artists.joinToString(" ")
        return "$BASE_URL/api/search?" +
            query(
                "track_name" to request.title,
                "artist_name" to artist,
                "album_name" to request.album,
            )
    }

    private fun LyricsRecord.toFound(): LyricsLookupResult.Found? =
        parse()?.let { LyricsLookupResult.Found(this, it) }

    private fun ProviderHttpResponse.toFailure(): LyricsLookupResult.Failure =
        when (statusCode) {
            429 ->
                LyricsLookupResult.Failure(
                    LyricsFailureKind.RATE_LIMITED,
                    retryable = true,
                    message = "LRCLIB rate limited the request",
                )
            in 500..599 ->
                LyricsLookupResult.Failure(
                    LyricsFailureKind.SERVICE_UNAVAILABLE,
                    retryable = true,
                    message = "LRCLIB is unavailable ($statusCode)",
                )
            else ->
                LyricsLookupResult.Failure(
                    LyricsFailureKind.SERVICE_UNAVAILABLE,
                    retryable = false,
                    message = "LRCLIB request failed ($statusCode)",
                )
        }

    private sealed interface HttpLyricsResult {
        data class Records(val records: List<LyricsRecord>) : HttpLyricsResult

        data object NotFound : HttpLyricsResult

        data class Failure(val value: LyricsLookupResult.Failure) : HttpLyricsResult
    }

    private companion object {
        const val BASE_URL = "https://lrclib.net"
        const val CLIENT_USER_AGENT =
            "Shippy/0.1 Android (https://github.com/OxygenCobalt/Auxio)"
    }
}

internal fun selectBestLyrics(
    request: LyricsRequest,
    records: List<LyricsRecord>,
): LyricsRecord? {
    val ranked =
        records
            .map { record -> record to lyricsIdentityScore(request, record) }
            .filter { (_, score) -> score >= MIN_IDENTITY_SCORE }
            .sortedByDescending { (_, score) -> score }
    val bestScore = ranked.firstOrNull()?.second ?: return null
    return ranked
        .asSequence()
        .filter { (_, score) -> score >= bestScore - SYNCED_PREFERENCE_MARGIN }
        .sortedWith(
            compareByDescending<Pair<LyricsRecord, Double>> {
                    it.first.syncedLyrics?.isNotBlank() == true
                }
                .thenByDescending { it.second }
                .thenBy { it.first.id }
        )
        .firstOrNull()
        ?.first
}

internal fun lyricsIdentityScore(
    request: LyricsRequest,
    record: LyricsRecord,
): Double {
    val title = tokenSimilarity(request.title, record.trackName)
    val artist = tokenSimilarity(request.artists.joinToString(" "), record.artistName)
    val album =
        request.album
            ?.takeIf(String::isNotBlank)
            ?.let { tokenSimilarity(it, record.albumName.orEmpty()) }
            ?: 1.0
    val duration =
        if (request.durationMs != null && record.durationSeconds != null) {
            val difference = kotlin.math.abs(request.durationMs / 1000.0 - record.durationSeconds)
            when {
                difference <= 2 -> 1.0
                difference <= 5 -> 0.7
                difference <= 15 -> 0.25
                else -> 0.0
            }
        } else {
            0.5
        }
    return title * 0.55 + artist * 0.30 + album * 0.10 + duration * 0.05
}

private fun tokenSimilarity(left: String, right: String): Double {
    val leftTokens = normalize(left).split(' ').filter(String::isNotBlank).toSet()
    val rightTokens = normalize(right).split(' ').filter(String::isNotBlank).toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    return 2.0 * leftTokens.intersect(rightTokens).size /
        (leftTokens.size + rightTokens.size)
}

private fun normalize(value: String): String =
    value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun query(vararg values: Pair<String, String?>): String =
    values
        .mapNotNull { (key, value) ->
            value?.takeIf(String::isNotBlank)?.let { "$key=${urlEncode(it)}" }
        }
        .joinToString("&")

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun JSONArray.toRecords(): List<LyricsRecord> =
    buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { value -> runCatching(value::toRecord).getOrNull()?.let(::add) }
        }
    }

private fun JSONObject.toRecord(): LyricsRecord =
    LyricsRecord(
        id = getLong("id"),
        trackName = getString("trackName"),
        artistName = getString("artistName"),
        albumName = optNullableString("albumName"),
        durationSeconds =
            if (has("duration") && !isNull("duration")) getDouble("duration") else null,
        instrumental = optBoolean("instrumental", false),
        plainLyrics = optNullableString("plainLyrics"),
        syncedLyrics = optNullableString("syncedLyrics"),
    )

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

private const val MIN_IDENTITY_SCORE = 0.62
private const val SYNCED_PREFERENCE_MARGIN = 0.05

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {
    @Binds
    @Singleton
    abstract fun repository(repository: ChainedLyricsRepository): LyricsRepository

    @Binds
    @IntoSet
    abstract fun lrclib(repository: LrclibLyricsRepository): LyricsSource
}
