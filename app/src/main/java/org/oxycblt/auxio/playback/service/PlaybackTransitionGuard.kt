/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackTransitionGuard.kt is part of Shippy.
 */

package org.oxycblt.auxio.playback.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crew owns a shared clock. Crossfade's locally scheduled overlap must never alter that clock.
 * The bridge holds this only for its bound service lifecycle.
 */
@Singleton
class PlaybackTransitionGuard @Inject constructor() {
    @Volatile var crewActive: Boolean = false
        private set

    fun setCrewActive(active: Boolean) {
        crewActive = active
    }
}
