/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackLocatorRegistry.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackSourceHandle
import java.util.LinkedHashMap
import org.oxycblt.auxio.playback.service.PlaybackRequestHeaders
import org.oxycblt.auxio.shippy.media.MediaObjectKey

/** Ephemeral playable locator. It is never part of a checkpoint or canonical source row. */
data class PlaybackLocator(
    val stableKey: String,
    val sourceReferenceId: SourceReferenceId?,
    val mediaAssetId: MediaAssetId?,
    val uri: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val cacheKey: String? = null,
    val expiresAtEpochMs: Long? = null,
) {
    init {
        require(stableKey.isNotBlank()) { "Playback locator key cannot be blank" }
        require(sourceReferenceId != null || mediaAssetId != null) {
            "Playback locator requires a source or asset identity"
        }
        require(uri.isNotBlank()) { "Playback locator URI cannot be blank" }
        require(headers.keys.none(String::isBlank)) { "Playback header name cannot be blank" }
        require(cacheKey == null || cacheKey.startsWith(MediaObjectKey.CACHE_KEY_PREFIX)) {
            "Playback cache key must use the Shippy media-cache namespace"
        }
        require(expiresAtEpochMs == null || expiresAtEpochMs >= 0) {
            "Playback locator expiry cannot be negative"
        }
    }

    val handle: PlaybackSourceHandle
        get() = PlaybackSourceHandle(stableKey, sourceReferenceId, mediaAssetId)
}

sealed interface PlaybackLocatorResolution {
    data class Ready(val locator: PlaybackLocator) : PlaybackLocatorResolution

    data class Unavailable(val error: PlaybackError) : PlaybackLocatorResolution
}

fun interface PlaybackLocatorResolver {
    suspend fun resolve(request: PlaybackPreparationRequest): PlaybackLocatorResolution
}

/**
 * Keeps rotating URLs and request headers outside immutable playback state. Durable identity is
 * carried by [PlaybackSourceHandle]; the registry is a small process-local materialization cache.
 */
class PlaybackLocatorRegistry(private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES) {
    private val locators =
        object : LinkedHashMap<String, PlaybackLocator>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, PlaybackLocator>
            ) = size > maximumEntries
        }

    init {
        require(maximumEntries > 0) { "Playback locator registry must retain at least one entry" }
    }

    @Synchronized
    fun publish(locator: PlaybackLocator): PlaybackSourceHandle {
        val existing = locators[locator.stableKey]
        check(
            existing == null ||
                (existing.sourceReferenceId == locator.sourceReferenceId &&
                    existing.mediaAssetId == locator.mediaAssetId)
        ) {
            "Playback locator key cannot be reassigned to another durable identity"
        }
        locators[locator.stableKey] = locator
        return locator.handle
    }

    @Synchronized
    fun require(handle: PlaybackSourceHandle): PlaybackLocator {
        val locator =
            checkNotNull(locators[handle.stableKey]) {
                "Playback locator is no longer available: ${handle.stableKey}"
            }
        check(
            locator.sourceReferenceId == handle.sourceReferenceId &&
                locator.mediaAssetId == handle.mediaAssetId
        ) {
            "Playback source handle does not match its registered durable identity"
        }
        return locator
    }

    @Synchronized
    fun headerProjection(): Map<String, Map<String, String>> =
        locators.values
            .filter { it.headers.isNotEmpty() }
            .associate { locator -> locator.media3Projection().dataSpecKey!! to locator.headers }

    @Synchronized fun clear() = locators.clear()

    private companion object {
        const val DEFAULT_MAXIMUM_ENTRIES = 64
    }
}

class RegistryPlaybackSourcePreparer(
    private val resolver: PlaybackLocatorResolver,
    private val registry: PlaybackLocatorRegistry,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val refreshMarginMs: Long = DEFAULT_REFRESH_MARGIN_MS,
) : PlaybackSourcePreparer {
    init {
        require(refreshMarginMs >= 0) { "Playback refresh margin cannot be negative" }
    }

    override suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult =
        when (val resolution = resolver.resolve(request)) {
            is PlaybackLocatorResolution.Unavailable ->
                PlaybackPreparationResult.Unavailable(resolution.error)
            is PlaybackLocatorResolution.Ready -> {
                val expiry = resolution.locator.expiresAtEpochMs
                if (expiry != null && expiry - nowEpochMs() <= refreshMarginMs) {
                    PlaybackPreparationResult.Unavailable(
                        PlaybackError("SOURCE_LOCATOR_EXPIRED", retryable = true)
                    )
                } else {
                    PlaybackPreparationResult.Ready(registry.publish(resolution.locator))
                }
            }
        }

    private companion object {
        const val DEFAULT_REFRESH_MARGIN_MS = 60_000L
    }
}

internal data class Media3LocatorProjection(
    val uri: String,
    val mimeType: String?,
    val dataSpecKey: String?,
)

internal fun PlaybackLocator.media3Projection() =
    Media3LocatorProjection(
        uri = uri,
        mimeType = mimeType,
        dataSpecKey = cacheKey ?: HEADER_KEY_PREFIX.plus(stableKey).takeIf { headers.isNotEmpty() },
    )

/** Materializes only locators already selected and registered by the R16 preparation effect. */
@OptIn(UnstableApi::class)
class RegistryMedia3ItemFactory(
    private val registry: PlaybackLocatorRegistry,
    private val requestHeaders: PlaybackRequestHeaders,
) : Media3ItemFactory {
    override fun create(item: PreparedEngineItem): MediaItem {
        val projection = registry.require(item.source).media3Projection()
        requestHeaders.replaceOwner(HEADER_OWNER, registry.headerProjection())
        return MediaItem.Builder()
            .setUri(projection.uri)
            .setMimeType(projection.mimeType)
            .setCustomCacheKey(projection.dataSpecKey)
            .build()
    }

    override fun release() {
        requestHeaders.clearOwner(HEADER_OWNER)
        registry.clear()
    }

    private companion object {
        const val HEADER_OWNER = "r16"
    }
}

private const val HEADER_KEY_PREFIX = "shippy-r16-header:"
