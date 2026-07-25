/*
 * Copyright (c) 2026 Shippy contributors
 * JioSaavnMediaUrl.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.jiosaavn

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

enum class JioSaavnQuality(val bitrateKbps: Int) {
    LOW(96),
    MEDIUM(160),
    HIGH(320),
}

object JioSaavnMediaUrl {
    // Public protocol constant used by JioSaavn clients, not an account credential.
    private const val MEDIA_URL_KEY = "38346591"
    private val qualitySuffix = Regex("_(?:96|160|320)(?=\\.[^/?#]+(?:[?#]|$))")
    private val trailingMediaData = Regex("(\\.(?:mp4|m4a)).*$", RegexOption.IGNORE_CASE)

    fun decode(encryptedMediaUrl: String): String {
        require(encryptedMediaUrl.isNotBlank()) { "Encrypted media URL cannot be blank" }

        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(MEDIA_URL_KEY.toByteArray(StandardCharsets.US_ASCII), "DES"),
        )
        val decoded =
            cipher.doFinal(Base64.decode(encryptedMediaUrl.trim()))
                .toString(StandardCharsets.UTF_8)
                .replace(trailingMediaData, "$1")
        require(decoded.startsWith("http://") || decoded.startsWith("https://")) {
            "Decoded media URL has an unsupported scheme"
        }
        return decoded.toHttps()
    }

    fun selectQuality(url: String, quality: JioSaavnQuality): String {
        require(url.isNotBlank()) { "Media URL cannot be blank" }
        return url.replace(qualitySuffix, "_${quality.bitrateKbps}")
    }

    private fun String.toHttps() =
        if (startsWith("http://")) {
            "https://${removePrefix("http://")}"
        } else {
            this
        }
}
