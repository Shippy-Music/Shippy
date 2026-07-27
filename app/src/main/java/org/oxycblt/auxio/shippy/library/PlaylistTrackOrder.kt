/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistTrackOrder.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library

import org.oxycblt.auxio.shippy.domain.TrackId

/** Applies a visible row order without dropping unresolved playlist tracks. */
internal fun reorderPlaylistTrackIds(
    trackIds: List<TrackId>,
    reorderedVisibleTrackIds: List<TrackId>,
): List<TrackId> {
    require(trackIds.distinct().size == trackIds.size) { "Playlist tracks must be duplicate-free" }
    require(reorderedVisibleTrackIds.distinct().size == reorderedVisibleTrackIds.size) {
        "Visible playlist tracks must be duplicate-free"
    }
    require(reorderedVisibleTrackIds.all { it in trackIds }) {
        "Visible playlist tracks must belong to the playlist"
    }
    val visibleIds = reorderedVisibleTrackIds.toSet()
    val replacement = reorderedVisibleTrackIds.iterator()
    return trackIds.map { trackId -> if (trackId in visibleIds) replacement.next() else trackId }
}
