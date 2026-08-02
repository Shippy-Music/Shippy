/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackDisplayItem.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import javax.inject.Inject
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.LocalCandidateResolution
import org.oxycblt.auxio.shippy.domain.LocalCandidateResolver
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.musikr.Song

data class PlaybackDisplayItem(
    val queueItem: QueueItem,
    val resolvedCandidateId: CandidateId?,
    val localSong: Song?,
    val contributorDisplayName: String? = null,
)

class PlaybackDisplayMapper
@Inject
constructor(private val localCandidateResolver: LocalCandidateResolver) {
    fun map(resolvedItem: ResolvedQueueItem): PlaybackDisplayItem {
        val candidate =
            resolvedItem.item.track.candidates.firstOrNull {
                it.id == resolvedItem.playback.candidateId
            }
        val localSong =
            when (val resolution = candidate?.let(localCandidateResolver::resolve)) {
                is LocalCandidateResolution.Ready -> resolution.song
                LocalCandidateResolution.InvalidIdentity,
                LocalCandidateResolution.Missing,
                LocalCandidateResolution.NotLocal,
                null -> null
            }
        return PlaybackDisplayItem(
            queueItem = resolvedItem.item,
            resolvedCandidateId = resolvedItem.playback.candidateId,
            localSong = localSong,
        )
    }

    /** Maps canonical Crew intent even when this device has not resolved a playable source yet. */
    fun map(queueItem: QueueItem): PlaybackDisplayItem =
        PlaybackDisplayItem(queueItem = queueItem, resolvedCandidateId = null, localSong = null)
}
