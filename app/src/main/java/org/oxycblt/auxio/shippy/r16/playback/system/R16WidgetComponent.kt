/*
 * Copyright (c) 2026 Auxio Project
 * R16WidgetComponent.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.system

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.image.BitmapProvider
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.widgets.WidgetPlaybackState
import org.oxycblt.auxio.widgets.WidgetProvider
import org.oxycblt.auxio.widgets.newWidgetArtworkTarget

/** Inactive widget lifecycle wrapper that renders only the canonical R16 system projection. */
class R16WidgetComponent(
    private val context: Context,
    parentScope: CoroutineScope,
    private val states: StateFlow<R16SystemPlaybackState>,
    private val imageSettings: ImageSettings,
    private val bitmapProvider: BitmapProvider,
    private val uiSettings: UISettings,
) : UISettings.Listener, ImageSettings.Listener {
    private val componentJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + componentJob + Dispatchers.Main.immediate)
    private val widgetProvider = WidgetProvider()
    private var attached = false
    private var released = false
    private var stateJob: Job? = null
    private var artworkRevision = 0L
    private var artworkStateInitialized = false
    private var artworkLocation: String? = null
    private var artworkBitmap: Bitmap? = null

    fun attach() {
        check(!released) { "Released R16 widget component cannot be attached" }
        if (attached) return
        attached = true
        uiSettings.registerListener(this)
        imageSettings.registerListener(this)
        stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { states.collect(::render) }
    }

    /** Re-renders current state after AppWidget surface recreation. */
    fun update() {
        if (attached) render(states.value)
    }

    fun release() {
        if (released) return
        released = true
        attached = false
        ++artworkRevision
        artworkStateInitialized = false
        artworkLocation = null
        artworkBitmap = null
        stateJob?.cancel()
        componentJob.cancel()
        bitmapProvider.release()
        imageSettings.unregisterListener(this)
        uiSettings.unregisterListener(this)
        widgetProvider.reset(context, uiSettings)
    }

    override fun onRoundModeChanged() = invalidateArtwork()

    override fun onImageSettingsChanged() = invalidateArtwork()

    private fun render(state: R16SystemPlaybackState) {
        val nowPlaying = R16SystemNowPlayingProjection.project(state)
        if (nowPlaying == null) {
            if (artworkStateInitialized) {
                ++artworkRevision
                artworkStateInitialized = false
                artworkLocation = null
                artworkBitmap = null
                bitmapProvider.release()
            }
            widgetProvider.update(context, uiSettings, null)
            return
        }

        val nextArtworkLocation =
            nowPlaying.artworkLocation?.takeIf(BitmapProvider::isValidArtworkUrl)
        if (artworkStateInitialized && artworkLocation == nextArtworkLocation) {
            publish(nowPlaying, artworkBitmap)
            return
        }

        artworkStateInitialized = true
        artworkLocation = nextArtworkLocation
        artworkBitmap = null
        val revision = ++artworkRevision
        bitmapProvider.release()
        publish(nowPlaying, null)
        nextArtworkLocation?.let { artwork ->
            bitmapProvider.loadArtwork(
                artwork,
                newWidgetArtworkTarget(context, imageSettings, uiSettings) { bitmap ->
                    if (released || revision != artworkRevision) return@newWidgetArtworkTarget
                    artworkBitmap = bitmap
                    render(states.value)
                },
            )
        }
    }

    private fun invalidateArtwork() {
        artworkStateInitialized = false
        update()
    }

    private fun publish(nowPlaying: R16SystemNowPlaying, bitmap: Bitmap?) {
        if (released) return
        widgetProvider.update(
            context,
            uiSettings,
            WidgetPlaybackState(
                title = nowPlaying.title,
                artist = nowPlaying.artist,
                album = nowPlaying.releaseTitle.orEmpty(),
                cover = bitmap,
                isPlaying = nowPlaying.isPlaying,
                repeatIconRes = nowPlaying.repeatMode.iconRes,
                isShuffled = nowPlaying.isShuffled,
            ),
        )
    }
}
