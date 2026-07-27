/*
 * Copyright (c) 2026 Auxio Project
 * LocalCandidateResolver.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

import javax.inject.Inject
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

sealed interface LocalCandidateResolution {
    data class Ready(val song: Song) : LocalCandidateResolution

    data object NotLocal : LocalCandidateResolution

    data object InvalidIdentity : LocalCandidateResolution

    data object Missing : LocalCandidateResolution
}

/**
 * Resolves a Shippy local candidate back to the exact Musikr song used by Auxio's current player.
 *
 * This is the compatibility seam for the incremental migration: the player can keep accepting a
 * [Song] while Shippy UI and domain code use canonical candidates.
 */
class LocalCandidateResolver @Inject constructor(private val musicRepository: MusicRepository) {
    fun resolve(candidate: TrackCandidate): LocalCandidateResolution {
        if (candidate.kind != CandidateKind.LOCAL) {
            return LocalCandidateResolution.NotLocal
        }

        val uid =
            Music.UID.fromString(candidate.sourceItemId)
                ?: return LocalCandidateResolution.InvalidIdentity
        val song = musicRepository.find(uid) as? Song ?: return LocalCandidateResolution.Missing
        return LocalCandidateResolution.Ready(song)
    }
}
