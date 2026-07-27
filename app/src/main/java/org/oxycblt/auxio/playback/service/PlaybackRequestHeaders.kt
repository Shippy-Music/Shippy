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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem

@Singleton
class PlaybackRequestHeaders @Inject constructor() {
    private val headersByQueueItemId = ConcurrentHashMap<String, Map<String, String>>()

    fun replace(items: Collection<ResolvedQueueItem>) {
        headersByQueueItemId.clear()
        items.forEach { item ->
            if (item.playback.headers.isNotEmpty()) {
                headersByQueueItemId[item.item.id.value] = item.playback.headers.toMap()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = dataSpec.key?.let(headersByQueueItemId::get).orEmpty()
        return if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
    }
}
