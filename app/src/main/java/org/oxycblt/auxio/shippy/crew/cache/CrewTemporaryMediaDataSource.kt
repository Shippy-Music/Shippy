/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryMediaDataSource.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.FileNotFoundException
import java.io.IOException

/** Routes opaque Crew temporary locators to a leased, progressively readable disk object. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CrewTemporaryMediaDataSourceFactory(
    private val index: CrewTemporaryMediaIndex,
    private val fallback: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RoutingDataSource(index, fallback.createDataSource())
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class RoutingDataSource(
    private val index: CrewTemporaryMediaIndex,
    private val fallback: DataSource,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "DataSource reopened without close" }
        val source =
            if (dataSpec.uri.scheme == CREW_TEMPORARY_SCHEME) {
                CrewTemporaryMediaDataSource(index)
            } else {
                fallback
            }
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = delegate ?: return
        delegate = null
        source.close()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CrewTemporaryMediaDataSource(private val index: CrewTemporaryMediaIndex) :
    DataSource {
    private var lease: CrewTemporaryMediaLease? = null
    private var openedUri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        // Private in-process disk reads do not expose network transfer telemetry.
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        check(lease == null) { "Crew DataSource reopened without close" }
        val acquired =
            index.acquire(dataSpec.uri.toString())
                ?: throw FileNotFoundException("Crew temporary media is not available")
        if (dataSpec.position < 0L || dataSpec.position > acquired.lengthBytes) {
            acquired.close()
            throw IOException("Crew media position is outside the object")
        }
        lease = acquired
        openedUri = dataSpec.uri
        position = dataSpec.position
        val available = acquired.lengthBytes - position
        remaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) available
            else minOf(dataSpec.length, available)
        return remaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count =
            try {
                checkNotNull(lease)
                    .read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
            } catch (error: Exception) {
                throw IOException("Crew temporary media read failed", error)
            }
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count
        remaining -= count
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        lease?.close()
        lease = null
        openedUri = null
        position = 0L
        remaining = 0L
    }
}
