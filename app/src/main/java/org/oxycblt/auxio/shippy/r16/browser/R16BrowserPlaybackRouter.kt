/*
 * Copyright (c) 2026 Auxio Project
 * R16BrowserPlaybackRouter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.browser

import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Latest-request-wins bridge used by R16 MediaSession browse/search callbacks. */
class R16BrowserPlaybackRouter
internal constructor(
    parentScope: CoroutineScope,
    private val resolveMediaId: suspend (String) -> R16BrowserQueueResolution,
    private val resolveSearch: suspend (String) -> R16BrowserQueueResolution,
    private val commands: PlaybackCommandRouter,
    private val onResult: (R16BrowserPlaybackRoutingResult) -> Unit = {},
    private val resolveMediaIdWithContext:
        suspend (
            String, R16MediaBrowserPlaylistPlaybackContext?, String?,
        ) -> R16BrowserQueueResolution =
        { mediaId, _, _ ->
            resolveMediaId(mediaId)
        },
) {
    constructor(
        parentScope: CoroutineScope,
        resolver: R16BrowserQueueResolver,
        commands: PlaybackCommandRouter,
        onResult: (R16BrowserPlaybackRoutingResult) -> Unit = {},
    ) : this(
        parentScope,
        resolver::resolvePlay,
        { query -> resolver.resolveLibrarySearch(query) },
        commands,
        onResult,
        { mediaId, context, songsQuery -> resolver.resolvePlay(mediaId, context, songsQuery) },
    )

    internal constructor(
        parentScope: CoroutineScope,
        resolveMediaId: suspend (String) -> R16BrowserQueueResolution,
        resolveMediaIdWithContext:
            suspend (String, R16MediaBrowserPlaylistPlaybackContext?) -> R16BrowserQueueResolution,
        resolveSearch: suspend (String) -> R16BrowserQueueResolution,
        commands: PlaybackCommandRouter,
        onResult: (R16BrowserPlaybackRoutingResult) -> Unit = {},
    ) : this(
        parentScope = parentScope,
        resolveMediaId = resolveMediaId,
        resolveSearch = resolveSearch,
        commands = commands,
        onResult = onResult,
        resolveMediaIdWithContext = { mediaId, context, _ ->
            resolveMediaIdWithContext(mediaId, context)
        },
    )

    private val routerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + routerJob)
    private val revision = AtomicLong()
    private var requestJob: Job? = null

    fun playFromMediaId(
        mediaId: String?,
        shuffleSeed: Long? = null,
        playlistContext: R16MediaBrowserPlaylistPlaybackContext? = null,
        songsQuery: String? = null,
    ) {
        val request = mediaId?.trim()?.takeIf(String::isNotEmpty)
        if (request == null) {
            onResult(R16BrowserPlaybackRoutingResult.InvalidRequest)
            return
        }
        route(shuffleSeed) { resolveMediaIdWithContext(request, playlistContext, songsQuery) }
    }

    fun playFromSearch(query: String?) {
        val request = query?.trim()?.takeIf(String::isNotEmpty)
        if (request == null) {
            onResult(R16BrowserPlaybackRoutingResult.InvalidRequest)
            return
        }
        route { resolveSearch(request) }
    }

    private fun route(shuffleSeed: Long? = null, resolve: suspend () -> R16BrowserQueueResolution) {
        val requestRevision = revision.incrementAndGet()
        requestJob?.cancel()
        requestJob =
            scope.launch {
                val resolution = resolve()
                if (requestRevision != revision.get()) return@launch
                when (resolution) {
                    is R16BrowserQueueResolution.Rejected ->
                        onResult(R16BrowserPlaybackRoutingResult.Rejected(resolution))
                    is R16BrowserQueueResolution.Ready -> {
                        val result = commands.dispatch(resolution.playCommand(shuffleSeed))
                        if (requestRevision == revision.get()) {
                            onResult(R16BrowserPlaybackRoutingResult.Dispatched(resolution, result))
                        }
                    }
                }
            }
    }

    fun release() {
        revision.incrementAndGet()
        requestJob?.cancel()
        routerJob.cancel()
        scope.cancel()
    }
}

sealed interface R16BrowserPlaybackRoutingResult {
    data object InvalidRequest : R16BrowserPlaybackRoutingResult

    data class Rejected(val resolution: R16BrowserQueueResolution.Rejected) :
        R16BrowserPlaybackRoutingResult

    data class Dispatched(
        val resolution: R16BrowserQueueResolution.Ready,
        val commandResult: PlaybackCommandResult,
    ) : R16BrowserPlaybackRoutingResult
}
