/*
 * Copyright (c) 2021 Auxio Project
 * WidgetComponent.kt is part of Auxio.
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
package org.oxycblt.auxio.widgets

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.BitmapProvider
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.image.coil.RoundedRectTransformation
import org.oxycblt.auxio.image.coil.SquareCropTransformation
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.musikr.MusicParent
import timber.log.Timber as L

/**
 * A component that manages the "Now Playing" state. This is kept separate from the [WidgetProvider]
 * itself to prevent possible memory leaks and enable extension to more widgets in the future.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class WidgetComponent
private constructor(
    private val context: Context,
    private val imageSettings: ImageSettings,
    private val bitmapProvider: BitmapProvider,
    private val playbackManager: PlaybackStateManager,
    private val uiSettings: UISettings,
) : PlaybackStateManager.Listener, UISettings.Listener, ImageSettings.Listener {
    class Factory
    @Inject
    constructor(
        private val imageSettings: ImageSettings,
        private val bitmapProvider: BitmapProvider,
        private val playbackManager: PlaybackStateManager,
        private val uiSettings: UISettings,
    ) {
        fun create(context: Context) =
            WidgetComponent(context, imageSettings, bitmapProvider, playbackManager, uiSettings)
    }

    private val widgetProvider = WidgetProvider()
    private var artworkRevision = 0L

    fun attach() {
        playbackManager.addListener(this)
        uiSettings.registerListener(this)
        imageSettings.registerListener(this)
    }

    /** Update [WidgetProvider] with the current playback state. */
    fun update() {
        val item = playbackManager.currentQueueItem
        if (item == null) {
            L.d("No playback item, resetting widget")
            widgetProvider.update(context, uiSettings, null)
            return
        }
        val revision = ++artworkRevision
        val track = item.track
        val localSong = playbackManager.currentSong
        val title = localSong?.name?.resolve(context) ?: track.title
        val artist = localSong?.artists?.resolveNames(context) ?: track.artists.joinToString(", ")
        val album = localSong?.album?.name?.resolve(context) ?: track.album.orEmpty()

        // Note: Store these values here so they remain consistent once the bitmap is loaded.
        val isPlaying = playbackManager.progression.isPlaying
        val repeatMode = playbackManager.repeatMode
        val isShuffled = playbackManager.isShuffled

        fun publish(bitmap: Bitmap?) {
            if (revision != artworkRevision) return
            val state =
                PlaybackState(
                    title = title,
                    artist = artist,
                    album = album,
                    cover = bitmap,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    isShuffled = isShuffled,
                )
            widgetProvider.update(context, uiSettings, state)
        }

        if (localSong == null) {
            publish(null)
            item.track.artwork
                ?.takeIf { BitmapProvider.isValidArtworkUrl(it) }
                ?.let { artwork ->
                    bitmapProvider.loadArtwork(artwork, newArtworkTarget(::publish))
                }
            return
        }

        L.d("Updating widget with new local artwork")
        bitmapProvider.load(localSong, newArtworkTarget(::publish))
    }

    private fun newArtworkTarget(publish: (Bitmap?) -> Unit) =
        object : BitmapProvider.Target {
            override fun onConfigRequest(builder: ImageRequest.Builder): ImageRequest.Builder {
                val cornerRadius =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12, always round the cover with the widget's inner radius
                        L.d("Using android 12 corner radius")
                        context.getDimenPixels(android.R.dimen.system_app_widget_inner_radius)
                    } else if (uiSettings.roundMode) {
                        // < Android 12, but the user still enabled round mode.
                        L.d("Using default corner radius")
                        context.getDimenPixels(R.dimen.m3_shape_corners_large)
                    } else {
                        // User did not enable round mode.
                        L.d("Using no corner radius")
                        0
                    }

                val transformations = buildList {
                    if (imageSettings.forceSquareCovers) {
                        add(SquareCropTransformation.INSTANCE)
                    }
                    if (cornerRadius > 0) {
                        add(WidgetBitmapTransformation(15f))
                        add(RoundedRectTransformation(cornerRadius.toFloat()))
                    } else {
                        add(WidgetBitmapTransformation(3f))
                    }
                }

                return builder.size(Size.ORIGINAL).transformations(transformations)
            }

            override fun onCompleted(bitmap: Bitmap?) {
                L.d("Bitmap loaded, uploading widget state")
                publish(bitmap)
            }
        }

    /** Release this instance, preventing any further events from updating the widget instances. */
    fun release() {
        bitmapProvider.release()
        imageSettings.unregisterListener(this)
        playbackManager.removeListener(this)
        uiSettings.unregisterListener(this)
        widgetProvider.reset(context, uiSettings)
    }

    // --- CALLBACKS ---

    // Respond to all major song or player changes that will affect the widget
    override fun onIndexMoved(index: Int) = update()

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: QueueChange,
    ) {
        if (change.type == QueueChange.Type.SONG) {
            update()
        }
    }

    override fun onCanonicalQueueReordered(
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = update()

    override fun onCanonicalNewPlayback(
        parent: MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = update()

    override fun onProgressionChanged(progression: Progression) = update()

    override fun onRepeatModeChanged(repeatMode: RepeatMode) = update()

    // Respond to settings changes that will affect the widget
    override fun onRoundModeChanged() = update()

    override fun onImageSettingsChanged() = update()

    /**
     * A condensed form of the playback state that is safe to use in AppWidgets.
     *
     * @param title Current canonical playback title.
     * @param artist Current canonical playback artist text.
     * @param album Current canonical playback album text.
     * @param cover A pre-loaded album cover [Bitmap], with rounded corners.
     * @param isPlaying [PlaybackStateManager.progression]
     * @param repeatMode [PlaybackStateManager.repeatMode]
     * @param isShuffled [PlaybackStateManager.isShuffled]
     */
    data class PlaybackState(
        val title: String,
        val artist: String,
        val album: String,
        val cover: Bitmap?,
        val isPlaying: Boolean,
        val repeatMode: RepeatMode,
        val isShuffled: Boolean,
    )
}
