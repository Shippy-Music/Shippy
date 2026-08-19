/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackRequestHeaders.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem

@Singleton
class PlaybackRequestHeaders @Inject constructor() {
    private val headersByOwner = linkedMapOf<String, Map<String, Map<String, String>>>()
    @Volatile private var headersByDataSpecKey = emptyMap<String, Map<String, String>>()

    fun replace(items: Collection<ResolvedQueueItem>) {
        replaceOwner(
            LEGACY_OWNER,
            buildMap {
                items.forEach { item ->
                    if (item.playback.headers.isNotEmpty()) {
                        put(item.item.id.value, item.playback.headers)
                        if (item.playback.cacheEligible) {
                            put(item.playback.mediaObjectKey.value, item.playback.headers)
                        }
                    }
                }
            },
        )
    }

    /** Replaces one runtime's complete bounded header projection without disturbing another. */
    @Synchronized
    fun replaceOwner(owner: String, entries: Map<String, Map<String, String>>) {
        require(owner.isNotBlank()) { "Playback request-header owner cannot be blank" }
        require(entries.keys.none(String::isBlank)) {
            "Playback request-header key cannot be blank"
        }
        val copied =
            entries.mapValues { (_, headers) ->
                require(headers.keys.none(String::isBlank)) {
                    "Playback request-header name cannot be blank"
                }
                headers.toMap()
            }
        val nextOwners = LinkedHashMap(headersByOwner)
        if (copied.isEmpty()) nextOwners.remove(owner) else nextOwners[owner] = copied
        val nextHeaders = buildMap {
            nextOwners.values.forEach { owned ->
                owned.forEach { (key, headers) ->
                    val previous = put(key, headers)
                    check(previous == null || previous == headers) {
                        "Playback request-header key is owned with conflicting values: $key"
                    }
                }
            }
        }
        headersByOwner.clear()
        headersByOwner.putAll(nextOwners)
        headersByDataSpecKey = nextHeaders
    }

    @Synchronized
    fun clearOwner(owner: String) {
        if (headersByOwner.remove(owner) != null) {
            headersByDataSpecKey =
                headersByOwner.values.flatMap { it.entries }.associate { it.toPair() }
        }
    }

    @OptIn(UnstableApi::class)
    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = dataSpec.key?.let(headersByDataSpecKey::get).orEmpty()
        return if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
    }

    internal fun headersForKey(key: String): Map<String, String> =
        headersByDataSpecKey[key].orEmpty()

    private companion object {
        const val LEGACY_OWNER = "r15.3"
    }
}
