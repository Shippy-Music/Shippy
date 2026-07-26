/*
 * Copyright (c) 2026 Shippy contributors
 * PlaylistTrackOrder.kt is part of Shippy.
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
    return trackIds.map { trackId ->
        if (trackId in visibleIds) replacement.next() else trackId
    }
}
