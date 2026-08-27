/*
 * Copyright (c) 2026 Auxio Project
 * R16Media3DownloadVerifier.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface R16Media3ReadabilityResult {
    data object Readable : R16Media3ReadabilityResult

    data class Unreadable(val reason: R16Media3UnreadableReason) : R16Media3ReadabilityResult
}

enum class R16Media3UnreadableReason {
    INVALID_CONTENT_URI,
    UNSUPPORTED_MIME_TYPE,
    OPEN_FAILED,
    EMPTY_ASSET,
    READ_FAILED,
    HTML_RESPONSE,
    FORMAT_MISMATCH,
    CLOSE_FAILED,
}

/** Content-only Media3 probe used before a permanent download is published as available. */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class R16Media3DownloadVerifier
internal constructor(
    private val dataSourceFactory: () -> DataSource,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        dataSourceFactory = { ContentDataSource(context.applicationContext) },
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * Opens and reads a bounded header through Media3's content data source.
     *
     * Non-content locations fail before a data source is created, so this boundary can never fall
     * back to the network. Cancellation is never converted into an unreadable result.
     */
    suspend fun verify(
        finalContentUri: String,
        expectedMimeType: String,
    ): R16Media3ReadabilityResult {
        val uri =
            finalContentUri.toContentUriOrNull()
                ?: return R16Media3ReadabilityResult.Unreadable(
                    R16Media3UnreadableReason.INVALID_CONTENT_URI
                )
        if (expectedMimeType.toExpectedContainerOrNull() == null) {
            return R16Media3ReadabilityResult.Unreadable(
                R16Media3UnreadableReason.UNSUPPORTED_MIME_TYPE
            )
        }
        return withContext(ioDispatcher) { probe(uri, expectedMimeType) }
    }

    private suspend fun probe(uri: Uri, expectedMimeType: String): R16Media3ReadabilityResult {
        var source: DataSource? = null
        var phase = ProbePhase.OPEN
        var outcome: R16Media3ReadabilityResult =
            R16Media3ReadabilityResult.Unreadable(R16Media3UnreadableReason.OPEN_FAILED)
        var cancellation: CancellationException? = null

        try {
            currentCoroutineContext().ensureActive()
            source = dataSourceFactory()
            source.open(DataSpec.Builder().setUri(uri).setLength(HEADER_BYTES.toLong()).build())
            currentCoroutineContext().ensureActive()
            phase = ProbePhase.READ
            val buffer = ByteArray(HEADER_BYTES)
            var bytesRead = 0
            var readFailed = false
            while (bytesRead < buffer.size) {
                currentCoroutineContext().ensureActive()
                when (val read = source.read(buffer, bytesRead, buffer.size - bytesRead)) {
                    C.RESULT_END_OF_INPUT -> break
                    0 -> {
                        readFailed = true
                        break
                    }
                    else -> bytesRead += read
                }
            }
            if (readFailed) {
                outcome =
                    R16Media3ReadabilityResult.Unreadable(R16Media3UnreadableReason.READ_FAILED)
            } else {
                outcome =
                    if (bytesRead == 0) {
                        R16Media3ReadabilityResult.Unreadable(R16Media3UnreadableReason.EMPTY_ASSET)
                    } else {
                        r16AudioHeaderFailureOrNull(
                                header = buffer.copyOf(bytesRead),
                                expectedMimeType = expectedMimeType,
                            )
                            ?.let(R16Media3ReadabilityResult::Unreadable)
                            ?: R16Media3ReadabilityResult.Readable
                    }
            }
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            cancellation = error
        } catch (_: Exception) {
            outcome =
                R16Media3ReadabilityResult.Unreadable(
                    when (phase) {
                        ProbePhase.OPEN -> R16Media3UnreadableReason.OPEN_FAILED
                        ProbePhase.READ -> R16Media3UnreadableReason.READ_FAILED
                    }
                )
        }

        try {
            source?.close()
        } catch (error: CancellationException) {
            cancellation = cancellation ?: error
        } catch (_: Exception) {
            if (outcome == R16Media3ReadabilityResult.Readable) {
                outcome =
                    R16Media3ReadabilityResult.Unreadable(R16Media3UnreadableReason.CLOSE_FAILED)
            }
        }

        cancellation?.let { throw it }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    private fun String.toContentUriOrNull(): Uri? {
        if (isBlank() || this != trim()) return null
        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrBlank()
        }
    }

    private enum class ProbePhase {
        OPEN,
        READ,
    }

    private companion object {
        const val HEADER_BYTES = 32
    }
}

/** Returns the exact publication failure for a bounded header, or null when it matches. */
internal fun r16AudioHeaderFailureOrNull(
    header: ByteArray,
    expectedMimeType: String,
): R16Media3UnreadableReason? {
    val expected =
        expectedMimeType.toExpectedContainerOrNull()
            ?: return R16Media3UnreadableReason.UNSUPPORTED_MIME_TYPE
    if (header.isEmpty()) return R16Media3UnreadableReason.EMPTY_ASSET
    if (header.looksLikeHtml()) return R16Media3UnreadableReason.HTML_RESPONSE
    return if (header.detectContainer() == expected) {
        null
    } else {
        R16Media3UnreadableReason.FORMAT_MISMATCH
    }
}

private enum class R16AudioContainer {
    MP4,
    MP3,
    OGG,
    FLAC,
    WEBM_MATROSKA,
    WAV,
    ADTS,
}

private fun String.toExpectedContainerOrNull(): R16AudioContainer? {
    if (isBlank() || this != trim()) return null
    return when (substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "audio/mp4",
        "audio/x-m4a",
        "video/mp4" -> R16AudioContainer.MP4
        "audio/mpeg",
        "audio/mp3" -> R16AudioContainer.MP3
        "audio/ogg",
        "audio/opus",
        "application/ogg",
        "application/x-ogg" -> R16AudioContainer.OGG
        "audio/flac",
        "audio/x-flac" -> R16AudioContainer.FLAC
        "audio/webm",
        "video/webm",
        "audio/x-matroska",
        "video/x-matroska" -> R16AudioContainer.WEBM_MATROSKA
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave" -> R16AudioContainer.WAV
        "audio/aac",
        "audio/aacp",
        "audio/adts" -> R16AudioContainer.ADTS
        else -> null
    }
}

private fun ByteArray.detectContainer(): R16AudioContainer? =
    when {
        size >= 8 && matchesAscii(4, "ftyp") -> R16AudioContainer.MP4
        matchesAscii(0, "ID3") || looksLikeMpegAudioFrame() -> R16AudioContainer.MP3
        matchesAscii(0, "OggS") -> R16AudioContainer.OGG
        matchesAscii(0, "fLaC") -> R16AudioContainer.FLAC
        startsWithBytes(0x1A, 0x45, 0xDF, 0xA3) -> R16AudioContainer.WEBM_MATROSKA
        size >= 12 && matchesAscii(0, "RIFF") && matchesAscii(8, "WAVE") -> R16AudioContainer.WAV
        looksLikeAdtsFrame() -> R16AudioContainer.ADTS
        else -> null
    }

private fun ByteArray.looksLikeHtml(): Boolean {
    var offset =
        if (startsWithBytes(0xEF, 0xBB, 0xBF)) {
            3
        } else {
            0
        }
    while (offset < size && this[offset].toInt().toChar() in " \t\r\n") offset++
    return offset < size && this[offset] == '<'.code.toByte()
}

private fun ByteArray.looksLikeMpegAudioFrame(): Boolean {
    if (size < 2 || (this[0].toInt() and 0xFF) != 0xFF) return false
    val second = this[1].toInt() and 0xFF
    val versionBits = second and 0x18
    val layerBits = second and 0x06
    return (second and 0xE0) == 0xE0 && versionBits != 0x08 && layerBits != 0
}

private fun ByteArray.looksLikeAdtsFrame(): Boolean {
    if (size < 2 || (this[0].toInt() and 0xFF) != 0xFF) return false
    return (this[1].toInt() and 0xF6) == 0xF0
}

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
    if (offset < 0 || size - offset < value.length) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean {
    if (size < expected.size) return false
    return expected.indices.all { index -> (this[index].toInt() and 0xFF) == expected[index] }
}
