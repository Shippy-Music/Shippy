/*
 * Copyright (c) 2026 Auxio Project
 * DownloadTransferModels.kt is part of Auxio.
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

import java.net.HttpURLConnection

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

    data class RedirectRejected(val detail: String) : DownloadTransferFailure {
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
