/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCacheManager.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.media.MediaObjectKey

/**
 * The single Media3 cache for reusable provider playback bytes.
 *
 * It is intentionally distinct from permanent downloads and the active-Crew temporary store. A
 * complete cached object can be copied into download staging, but clearing this cache never changes
 * a download or a Local file.
 */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCacheManager internal constructor(private val cache: SimpleCache) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        SimpleCache(
            File(context.cacheDir, DIRECTORY_NAME),
            LeastRecentlyUsedCacheEvictor(DEFAULT_MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
    )

    fun dataSourceFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
        val cachedFactory =
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        return DataSource.Factory { ProviderCacheDataSource(cachedFactory, upstreamFactory) }
    }

    fun hasComplete(key: MediaObjectKey, expectedLength: Long? = null): Boolean {
        val length = contentLength(key, expectedLength) ?: return false
        return length > 0L && cache.isCached(key.value, 0L, length)
    }

    /**
     * Copies only a complete cache object. A cache miss or partial object is never represented as a
     * successful promotion, so the caller may safely use its normal resolver/transfer path.
     */
    suspend fun copyCompleteTo(
        key: MediaObjectKey,
        expectedLength: Long? = null,
        output: OutputStream,
    ): Long? =
        withContext(Dispatchers.IO) {
            val length = contentLength(key, expectedLength) ?: return@withContext null
            if (length <= 0L || !cache.isCached(key.value, 0L, length)) return@withContext null

            val source =
                CacheDataSource(
                    cache,
                    /* upstreamDataSource= */ null,
                    CacheDataSource.FLAG_BLOCK_ON_CACHE,
                )
            try {
                source.open(
                    DataSpec.Builder()
                        .setUri(Uri.parse("cache://shippy/${key.value}"))
                        .setKey(key.value)
                        .setLength(length)
                        .build()
                )
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (copied < length) {
                    val read =
                        source.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                    if (read == C.RESULT_END_OF_INPUT) return@withContext null
                    output.write(buffer, 0, read)
                    copied += read
                }
                output.flush()
                copied.takeIf { it == length }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    source.close()
                } catch (_: Exception) {
                    // The cache copy was already rejected above if a read failed.
                }
            }
        }

    /**
     * Clears only the bounded playback-object cache. Durable downloads, local files, and Crew
     * temporary media live elsewhere; no settings UI calls this yet.
     */
    suspend fun clear() = withContext(Dispatchers.IO) { cache.keys.forEach(cache::removeResource) }

    fun sizeBytes(): Long = cache.cacheSpace

    private fun contentLength(key: MediaObjectKey, expectedLength: Long?): Long? {
        expectedLength
            ?.takeIf { it > 0L }
            ?.let {
                return it
            }
        return ContentMetadata.getContentLength(cache.getContentMetadata(key.value)).takeIf {
            it != C.LENGTH_UNSET.toLong() && it > 0L
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "shippy-playback-cache-v1"
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** Caches only explicitly identified provider objects, never Local/Download/Crew temporary URIs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ProviderCacheDataSource(
    private val cachedFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
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
        val factory =
            if (dataSpec.key?.startsWith(MediaObjectKey.CACHE_KEY_PREFIX) == true) cachedFactory
            else upstreamFactory
        return factory
            .createDataSource()
            .also { source ->
                listeners.forEach(source::addTransferListener)
                delegate = source
            }
            .open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        val open = delegate ?: return
        delegate = null
        open.close()
    }
}
