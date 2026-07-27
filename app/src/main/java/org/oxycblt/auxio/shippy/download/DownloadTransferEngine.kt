/*
 * Copyright (c) 2026 Auxio Project
 * DownloadTransferEngine.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadTransferRequest(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val expectedLength: Long? = null,
)

data class DownloadTransferProgress(val bytesTransferred: Long, val expectedBytes: Long?)

sealed interface DownloadTransferResult {
    data class Success(val bytesTransferred: Long, val declaredLength: Long?) :
        DownloadTransferResult

    data class Failure(val reason: DownloadTransferFailure) : DownloadTransferResult
}

sealed interface DownloadTransferFailure {
    val retryable: Boolean

    data object InvalidRequest : DownloadTransferFailure {
        override val retryable = false
    }

    data class UnsupportedScheme(val scheme: String?) : DownloadTransferFailure {
        override val retryable = false
    }

    data class HttpStatus(val statusCode: Int) : DownloadTransferFailure {
        override val retryable =
            statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                statusCode == 425 ||
                statusCode == 429 ||
                statusCode in 500..599
    }

    data object Timeout : DownloadTransferFailure {
        override val retryable = true
    }

    data object NetworkUnavailable : DownloadTransferFailure {
        override val retryable = true
    }

    data object SourceUnavailable : DownloadTransferFailure {
        override val retryable = false
    }

    data object TlsRejected : DownloadTransferFailure {
        override val retryable = false
    }

    data object SourceReadFailed : DownloadTransferFailure {
        override val retryable = true
    }

    data object DestinationWriteFailed : DownloadTransferFailure {
        override val retryable = false
    }

    data class PrematureEof(val expectedBytes: Long, val actualBytes: Long) :
        DownloadTransferFailure {
        override val retryable = true
    }

    data class LengthExceeded(val expectedBytes: Long, val bytesBeforeRejectedChunk: Long) :
        DownloadTransferFailure {
        override val retryable = false
    }
}

/**
 * Copies one already-resolved source into a caller-owned output.
 *
 * The engine owns and closes its source. It never closes or flushes [output]; destination
 * finalization remains the caller's responsibility.
 */
class DownloadTransferEngine
internal constructor(
    private val sourceFactory: DownloadTransferSourceFactory,
    private val monotonicTimeMs: () -> Long,
    private val progressByteInterval: Long = DEFAULT_PROGRESS_BYTE_INTERVAL,
    private val progressTimeIntervalMs: Long = DEFAULT_PROGRESS_TIME_INTERVAL_MS,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        sourceFactory = AndroidDownloadTransferSourceFactory(context.contentResolver),
        monotonicTimeMs = { System.nanoTime() / 1_000_000 },
        progressByteInterval = DEFAULT_PROGRESS_BYTE_INTERVAL,
        progressTimeIntervalMs = DEFAULT_PROGRESS_TIME_INTERVAL_MS,
    )

    suspend fun transfer(
        request: DownloadTransferRequest,
        output: OutputStream,
        onProgress: suspend (DownloadTransferProgress) -> Unit = {},
    ): DownloadTransferResult =
        withContext(Dispatchers.IO) {
            val parsedUri = validate(request) ?: return@withContext failureInvalidRequest(request)
            val source =
                try {
                    sourceFactory.open(
                        parsedUri,
                        request.headers,
                        CONNECT_TIMEOUT_MS,
                        READ_TIMEOUT_MS,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SocketTimeoutException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.Timeout
                    )
                } catch (_: UnknownHostException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.NetworkUnavailable
                    )
                } catch (_: ConnectException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.NetworkUnavailable
                    )
                } catch (_: FileNotFoundException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.SourceUnavailable
                    )
                } catch (_: SecurityException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.SourceUnavailable
                    )
                } catch (_: SSLException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.TlsRejected
                    )
                } catch (_: IllegalArgumentException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.InvalidRequest
                    )
                } catch (_: IOException) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.SourceReadFailed
                    )
                }

            source.use { opened ->
                val statusCode = opened.httpStatusCode
                if (statusCode != null && statusCode !in 200..299) {
                    return@withContext DownloadTransferResult.Failure(
                        DownloadTransferFailure.HttpStatus(statusCode)
                    )
                }

                val input =
                    try {
                        opened.openInput()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: SocketTimeoutException) {
                        return@withContext DownloadTransferResult.Failure(
                            DownloadTransferFailure.Timeout
                        )
                    } catch (_: FileNotFoundException) {
                        return@withContext DownloadTransferResult.Failure(
                            DownloadTransferFailure.SourceUnavailable
                        )
                    } catch (_: SSLException) {
                        return@withContext DownloadTransferResult.Failure(
                            DownloadTransferFailure.TlsRejected
                        )
                    } catch (_: IOException) {
                        return@withContext DownloadTransferResult.Failure(
                            DownloadTransferFailure.SourceReadFailed
                        )
                    }

                input.use {
                    copy(
                        input = it,
                        output = output,
                        expectedLength = request.expectedLength ?: opened.declaredLength,
                        declaredLength = opened.declaredLength,
                        onProgress = onProgress,
                    )
                }
            }
        }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        expectedLength: Long?,
        declaredLength: Long?,
        onProgress: suspend (DownloadTransferProgress) -> Unit,
    ): DownloadTransferResult {
        val progressExpected = expectedLength ?: declaredLength
        val buffer = ByteArray(BUFFER_SIZE)
        var transferred = 0L
        var lastProgressBytes = 0L
        var lastProgressTimeMs = monotonicTimeMs()

        while (true) {
            coroutineContext.ensureActive()
            val read =
                try {
                    input.read(buffer)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SocketTimeoutException) {
                    return DownloadTransferResult.Failure(DownloadTransferFailure.Timeout)
                } catch (_: IOException) {
                    return DownloadTransferResult.Failure(DownloadTransferFailure.SourceReadFailed)
                }
            if (read == -1) break
            if (read == 0) continue

            if (expectedLength != null && transferred + read > expectedLength) {
                return DownloadTransferResult.Failure(
                    DownloadTransferFailure.LengthExceeded(expectedLength, transferred)
                )
            }
            try {
                output.write(buffer, 0, read)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                return DownloadTransferResult.Failure(
                    DownloadTransferFailure.DestinationWriteFailed
                )
            }
            transferred += read

            val now = monotonicTimeMs()
            if (
                transferred - lastProgressBytes >= progressByteInterval ||
                    now - lastProgressTimeMs >= progressTimeIntervalMs
            ) {
                onProgress(DownloadTransferProgress(transferred, progressExpected))
                lastProgressBytes = transferred
                lastProgressTimeMs = now
            }
        }

        if (expectedLength != null && transferred < expectedLength) {
            return DownloadTransferResult.Failure(
                DownloadTransferFailure.PrematureEof(expectedLength, transferred)
            )
        }
        if (lastProgressBytes != transferred) {
            onProgress(DownloadTransferProgress(transferred, progressExpected))
        }
        return DownloadTransferResult.Success(transferred, declaredLength)
    }

    private fun validate(request: DownloadTransferRequest): URI? {
        if (request.uri.isBlank() || request.expectedLength?.let { it < 0 } == true) return null
        if (
            request.headers.any { (name, value) ->
                name.isBlank() ||
                    name.any { it == '\r' || it == '\n' } ||
                    value.any { it == '\r' || it == '\n' }
            }
        ) {
            return null
        }
        val uri =
            try {
                URI(request.uri)
            } catch (_: URISyntaxException) {
                return null
            }
        if (uri.scheme?.lowercase() !in SUPPORTED_SCHEMES) return null
        return uri
    }

    private fun failureInvalidRequest(request: DownloadTransferRequest): DownloadTransferResult {
        val scheme =
            try {
                URI(request.uri).scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
        return if (scheme != null && scheme !in SUPPORTED_SCHEMES) {
            DownloadTransferResult.Failure(DownloadTransferFailure.UnsupportedScheme(scheme))
        } else {
            DownloadTransferResult.Failure(DownloadTransferFailure.InvalidRequest)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val DEFAULT_PROGRESS_BYTE_INTERVAL = 256L * 1024
        const val DEFAULT_PROGRESS_TIME_INTERVAL_MS = 500L
        val SUPPORTED_SCHEMES = setOf("http", "https", "content", "file")
    }
}

internal interface DownloadTransferSource : Closeable {
    val httpStatusCode: Int?
    val declaredLength: Long?

    fun openInput(): InputStream
}

internal fun interface DownloadTransferSourceFactory {
    @Throws(IOException::class)
    fun open(
        uri: URI,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): DownloadTransferSource
}

private class AndroidDownloadTransferSourceFactory(private val contentResolver: ContentResolver) :
    DownloadTransferSourceFactory {
    override fun open(
        uri: URI,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): DownloadTransferSource =
        when (uri.scheme?.lowercase()) {
            "http",
            "https" -> HttpDownloadTransferSource(uri, headers, connectTimeoutMs, readTimeoutMs)
            "content" -> ContentDownloadTransferSource(contentResolver, uri)
            "file" -> FileDownloadTransferSource(uri)
            else -> throw IOException("Unsupported transfer source")
        }
}

private class HttpDownloadTransferSource(
    uri: URI,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
) : DownloadTransferSource {
    private val connection =
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    override val httpStatusCode: Int = connection.responseCode
    override val declaredLength: Long? = connection.contentLengthLong.takeIf { it >= 0 }

    override fun openInput(): InputStream = connection.inputStream

    override fun close() {
        connection.disconnect()
    }
}

private class ContentDownloadTransferSource(
    private val contentResolver: ContentResolver,
    private val uri: URI,
) : DownloadTransferSource {
    override val httpStatusCode: Int? = null
    override val declaredLength: Long? = null

    override fun openInput(): InputStream =
        contentResolver.openInputStream(Uri.parse(uri.toString())) ?: throw FileNotFoundException()

    override fun close() = Unit
}

private class FileDownloadTransferSource(uri: URI) : DownloadTransferSource {
    private val file = File(uri)

    override val httpStatusCode: Int? = null
    override val declaredLength: Long? = file.length().takeIf { file.isFile }

    override fun openInput(): InputStream = FileInputStream(file)

    override fun close() = Unit
}
