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
class PlaybackCacheManager
internal constructor(
    private val cache: SimpleCache,
    private val context: Context? = null,
    private val settings: PlaybackCacheSettings? = null,
) {
    private val protectedKeys: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: PlaybackCacheSettings,
    ) : this(
        SimpleCache(
            File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() },
            // The evictor is the absolute upper bound. The selected lower bound is enforced by
            // maintenance immediately after a preference change and by periodic maintenance.
            LeastRecentlyUsedCacheEvictor(MAX_CONFIGURED_BYTES),
            StandaloneDatabaseProvider(context),
        ),
        context,
        settings,
    )

    fun dataSourceFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
        val cachedFactory =
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        return DataSource.Factory {
            ProviderCacheDataSource(
                cachedFactory,
                upstreamFactory,
                { key -> protectedKeys.add(key) },
                { key -> protectedKeys.remove(key) },
            )
        }
    }

    fun protectKey(key: MediaObjectKey) {
        protectedKeys.add(key.value)
    }

    fun unprotectKey(key: MediaObjectKey) {
        protectedKeys.remove(key.value)
    }

    fun isProtected(key: MediaObjectKey): Boolean = protectedKeys.contains(key.value)

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

            protectKey(key)
            try {
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
                            source.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), length - copied).toInt(),
                            )
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
            } finally {
                unprotectKey(key)
            }
        }

    /**
     * Evicts cached spans older than [maxAgeMs], respecting active eviction protections. Returns
     * total bytes evicted.
     */
    fun evictOlderThan(maxAgeMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Long {
        var evictedBytes = 0L
        for (key in cache.keys) {
            if (protectedKeys.contains(key)) continue
            val spans = cache.getCachedSpans(key)
            for (span in spans) {
                if (span.lastTouchTimestamp < nowEpochMs - maxAgeMs) {
                    val spanLength = span.length
                    cache.removeSpan(span)
                    evictedBytes += spanLength
                }
            }
        }
        return evictedBytes
    }

    /**
     * Evicts oldest unprotected spans when free space is below [minFreeBytes]. Returns total bytes
     * evicted.
     */
    fun enforceFreeSpaceFloor(minFreeBytes: Long, getUsableSpace: () -> Long): Long {
        var evictedBytes = 0L
        if (getUsableSpace() >= minFreeBytes) return 0L

        val allSpans = mutableListOf<androidx.media3.datasource.cache.CacheSpan>()
        for (key in cache.keys) {
            if (protectedKeys.contains(key)) continue
            allSpans.addAll(cache.getCachedSpans(key))
        }
        allSpans.sortBy { it.lastTouchTimestamp }

        for (span in allSpans) {
            if (getUsableSpace() >= minFreeBytes) break
            val len = span.length
            cache.removeSpan(span)
            evictedBytes += len
        }
        return evictedBytes
    }

    /** Evicts oldest unprotected spans until the selected cache target is satisfied. */
    fun enforceMaximumSize(maxBytes: Long): Long {
        require(maxBytes > 0L) { "Cache maximum must be positive" }
        if (cache.cacheSpace <= maxBytes) return 0L
        var evictedBytes = 0L
        val spans =
            cache.keys
                .asSequence()
                .filterNot(protectedKeys::contains)
                .flatMap { cache.getCachedSpans(it).asSequence() }
                .sortedBy { it.lastTouchTimestamp }
                .toList()
        for (span in spans) {
            if (cache.cacheSpace <= maxBytes) break
            cache.removeSpan(span)
            evictedBytes += span.length
        }
        return evictedBytes
    }

    /**
     * Clears only unprotected resources in the bounded playback-object cache. Protected active
     * items, durable downloads, local files, and Crew temporary media are never affected.
     */
    suspend fun clear() =
        withContext(Dispatchers.IO) {
            for (key in cache.keys) {
                if (protectedKeys.contains(key)) continue
                cache.removeResource(key)
            }
        }

    /**
     * Runs bounded cache maintenance using Media3 span access timestamps for optional age eviction.
     * Protected active items are preserved throughout, even when that leaves the cache above
     * target.
     */
    fun performMaintenance(
        maxAgeMs: Long? = DEFAULT_MAX_AGE_MS,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
        getUsableSpace: () -> Long = { context?.cacheDir?.usableSpace ?: Long.MAX_VALUE },
    ): Long {
        var evictedBytes = 0L
        if (maxAgeMs != null) {
            evictedBytes += evictOlderThan(maxAgeMs)
        }
        evictedBytes += enforceMaximumSize(maxBytes)
        evictedBytes += enforceFreeSpaceFloor(minFreeBytes, getUsableSpace)
        return evictedBytes
    }

    fun performConfiguredMaintenance(): Long =
        performMaintenance(
            maxAgeMs = if (settings == null) DEFAULT_MAX_AGE_MS else settings.unusedMaxAgeMs,
            maxBytes = settings?.maximumBytes ?: DEFAULT_MAX_BYTES,
        )

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
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L // 2 GB
        const val MAX_CONFIGURED_BYTES = 5L * 1024L * 1024L * 1024L // 5 GB
        const val DEFAULT_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
        const val DEFAULT_MIN_FREE_BYTES = 500L * 1024L * 1024L // 500 MB
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** Caches only explicitly identified provider objects, never Local/Download/Crew temporary URIs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ProviderCacheDataSource(
    private val cachedFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val protectKey: (String) -> Unit,
    private val unprotectKey: (String) -> Unit,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var protectedKey: String? = null

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
        val cacheKey = dataSpec.key?.takeIf { it.startsWith(MediaObjectKey.CACHE_KEY_PREFIX) }
        cacheKey?.let(protectKey)
        protectedKey = cacheKey
        return try {
            factory
                .createDataSource()
                .also { source ->
                    listeners.forEach(source::addTransferListener)
                    delegate = source
                }
                .open(dataSpec)
        } catch (error: IOException) {
            delegate = null
            protectedKey?.let(unprotectKey)
            protectedKey = null
            throw error
        } catch (error: RuntimeException) {
            delegate = null
            protectedKey?.let(unprotectKey)
            protectedKey = null
            throw error
        }
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
        try {
            open.close()
        } finally {
            protectedKey?.let(unprotectKey)
            protectedKey = null
        }
    }
}
