/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTransitionGuard.kt is part of Auxio.
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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crew owns a shared clock. Crossfade's locally scheduled overlap must never alter that clock. The
 * bridge holds this only for its bound service lifecycle.
 */
@Singleton
class PlaybackTransitionGuard @Inject constructor() {
    @Volatile
    var crewActive: Boolean = false
        private set

    fun setCrewActive(active: Boolean) {
        crewActive = active
    }
}
