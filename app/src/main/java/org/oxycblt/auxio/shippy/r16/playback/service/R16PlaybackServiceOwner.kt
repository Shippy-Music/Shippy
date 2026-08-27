/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackServiceOwner.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.service

import app.shippy.core.playback.PlaybackSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.r16.playback.R16PlaybackSpine
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemSurfaceRuntime

enum class R16PlaybackServiceOwnerLifecycle {
    DETACHED,
    ATTACHED,
    RELEASED,
}

/**
 * Service-level lifecycle owner for the active authority branch. It deliberately owns no
 * [android.app.Service] itself and never re-enters the legacy R15 lifecycle.
 */
class R16PlaybackServiceOwner
internal constructor(
    private val spine: R16PlaybackServiceOwnerSpine,
    private val surfaces: R16PlaybackServiceOwnerSurfaces,
) {
    constructor(
        spine: R16PlaybackSpine,
        surfaces: R16SystemSurfaceRuntime,
    ) : this(R16PlaybackSpineComponent(spine), R16SystemSurfaceComponent(surfaces))

    private val lifecycleMutex = Mutex()

    var lifecycle: R16PlaybackServiceOwnerLifecycle = R16PlaybackServiceOwnerLifecycle.DETACHED
        private set

    /** Restores/starts the one spine before exposing its derived Android system surfaces. */
    suspend fun attach(allowResume: Boolean = false) {
        lifecycleMutex.withLock {
            when (lifecycle) {
                R16PlaybackServiceOwnerLifecycle.ATTACHED -> return
                R16PlaybackServiceOwnerLifecycle.RELEASED ->
                    error("Released R16 playback service owner cannot be attached")
                R16PlaybackServiceOwnerLifecycle.DETACHED -> Unit
            }

            try {
                spine.attach(allowResume)
                surfaces.attach()
                lifecycle = R16PlaybackServiceOwnerLifecycle.ATTACHED
            } catch (error: Exception) {
                try {
                    surfaces.release()
                } finally {
                    spine.release()
                    lifecycle = R16PlaybackServiceOwnerLifecycle.RELEASED
                }
                throw error
            }
        }
    }

    /**
     * Releases the R16 owner when task removal is configured to end the current playback session.
     * The Boolean result tells the future Service branch whether it should also stop itself.
     */
    suspend fun handleTaskRemoved(exitOnTaskRemoval: Boolean, hasActiveCrew: Boolean): Boolean {
        val retain =
            R16PlaybackForegroundPolicy.shouldRetainAfterTaskRemoval(
                spine.snapshots.value,
                exitOnTaskRemoval,
                hasActiveCrew,
            )
        if (retain) return false
        release()
        return true
    }

    /** Detaches Android surfaces before releasing their one playback authority. */
    suspend fun release() {
        lifecycleMutex.withLock {
            if (lifecycle == R16PlaybackServiceOwnerLifecycle.RELEASED) return
            try {
                surfaces.release()
            } finally {
                spine.release()
                lifecycle = R16PlaybackServiceOwnerLifecycle.RELEASED
            }
        }
    }
}

/**
 * Internal seam keeps lifecycle ordering directly unit-testable without constructing Android
 * surfaces.
 */
internal interface R16PlaybackServiceOwnerSpine {
    val snapshots: StateFlow<PlaybackSnapshot>

    suspend fun attach(allowResume: Boolean)

    suspend fun release()
}

internal interface R16PlaybackServiceOwnerSurfaces {
    fun attach()

    fun release()
}

private class R16PlaybackSpineComponent(private val spine: R16PlaybackSpine) :
    R16PlaybackServiceOwnerSpine {
    override val snapshots: StateFlow<PlaybackSnapshot>
        get() = spine.snapshots

    override suspend fun attach(allowResume: Boolean) {
        spine.attach(allowResume)
    }

    override suspend fun release() {
        spine.release()
    }
}

private class R16SystemSurfaceComponent(private val surfaces: R16SystemSurfaceRuntime) :
    R16PlaybackServiceOwnerSurfaces {
    override fun attach() {
        surfaces.attach()
    }

    override fun release() {
        surfaces.release()
    }
}
