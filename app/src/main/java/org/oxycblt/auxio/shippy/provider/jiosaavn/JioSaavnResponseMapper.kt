/*
 * Copyright (c) 2026 Shippy contributors
 * JioSaavnResponseMapper.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.jiosaavn

import java.util.Locale

data class JioSaavnSong(
    val id: String,
    val type: String?,
    val title: String,
    val album: String?,
    val artist: String,
    val durationSeconds: Long?,
    val language: String?,
    val supports320Kbps: Boolean?,
    val hasLyrics: Boolean?,
    val artworkUrl: String?,
    val permanentUrl: String?,
    val mediaUrl: String?,
)

object JioSaavnResponseMapper {
    fun mapSong(response: Map<String, Any?>): JioSaavnSong {
        val moreInfo = response.map("more_info")
        val id = response.text("id") ?: throw IllegalArgumentException("Song response has no id")
        val title =
            (response.text("song") ?: response.text("title"))
                ?.decodeEntities()
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Song response has no title")
        val encryptedMediaUrl =
            response.text("encrypted_media_url") ?: moreInfo?.text("encrypted_media_url")

        return JioSaavnSong(
            id = id,
            type = response.text("type"),
            title = title,
            album = (response.text("album") ?: moreInfo?.text("album"))?.decodeEntities(),
            artist = artist(response, moreInfo),
            durationSeconds =
                (response["duration"] ?: moreInfo?.get("duration")).asLongOrNull()
                    ?.takeIf { it >= 0 },
            language =
                (response.text("language") ?: moreInfo?.text("language"))
                    ?.replaceFirstChar { it.titlecase(Locale.ROOT) },
            supports320Kbps =
                (response["320kbps"] ?: moreInfo?.get("320kbps")).asBooleanOrNull(),
            hasLyrics = (response["has_lyrics"] ?: moreInfo?.get("has_lyrics")).asBooleanOrNull(),
            artworkUrl = response.text("image")?.let(::normalizeArtworkUrl),
            permanentUrl = response.text("perma_url"),
            mediaUrl = encryptedMediaUrl?.takeIf(String::isNotBlank)?.let(JioSaavnMediaUrl::decode),
        )
    }

    fun mapSongs(responses: Iterable<Map<String, Any?>>): List<JioSaavnSong> =
        responses.map(::mapSong)

    fun normalizeArtworkUrl(url: String, size: Int = 500): String {
        require(size in setOf(50, 150, 500)) { "Unsupported JioSaavn artwork size" }
        val trimmed = url.trim()
        val secure =
            if (trimmed.startsWith("http://")) {
                "https://${trimmed.removePrefix("http://")}"
            } else {
                trimmed
            }
        return secure
            .replace(Regex("(?:50|150|500)x(?:50|150|500)"), "${size}x$size")
    }

    private fun artist(
        response: Map<String, Any?>,
        moreInfo: Map<String, Any?>?,
    ): String {
        val primaryArtists =
            moreInfo?.map("artistMap")
                ?.list("primary_artists")
                .orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.get("name")?.toString()?.decodeEntities() }
                .filter(String::isNotBlank)
        if (primaryArtists.isNotEmpty()) {
            return primaryArtists.joinToString(", ")
        }

        moreInfo
            ?.text("primary_artists")
            ?.decodeEntities()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        moreInfo?.text("music")?.decodeEntities()?.takeIf(String::isNotBlank)?.let { return it }
        return response.text("subtitle")?.decodeEntities()?.takeIf(String::isNotBlank) ?: "Unknown"
    }

    private fun String.decodeEntities() =
        replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .trim()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String) = get(key) as? Map<String, Any?>

    private fun Map<String, Any?>.list(key: String) = get(key) as? List<*>

    private fun Map<String, Any?>.text(key: String) =
        get(key)?.toString()?.takeUnless { it == "null" }

    private fun Any?.asLongOrNull() =
        when (this) {
            is Number -> toLong()
            is String -> trim().toLongOrNull()
            else -> null
        }

    private fun Any?.asBooleanOrNull() =
        when (this) {
            is Boolean -> this
            is Number -> toInt() != 0
            is String ->
                when (trim().lowercase(Locale.ROOT)) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            else -> null
        }
}
