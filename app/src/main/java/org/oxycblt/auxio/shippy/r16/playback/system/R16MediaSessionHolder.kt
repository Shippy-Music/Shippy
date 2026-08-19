/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaSessionHolder.kt is part of Auxio.
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
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.playback.RepeatMode
import app.shippy.data.playback.R16RecordingPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.ForegroundListener
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.BitmapProvider
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.playback.service.PlaybackNotification

/**
 * Inactive R16 lifecycle wrapper around the retained MediaSession and notification behavior. It is
 * deliberately not injected into the R15.3 service and owns no player or queue state.
 */
class R16MediaSessionHolder(
    private val context: Context,
    parentScope: CoroutineScope,
    private val foregroundListener: ForegroundListener,
    private val states: StateFlow<R16SystemPlaybackState>,
    commands: R16SystemPlaybackCommands,
    private val bitmapProvider: BitmapProvider,
    private val imageSettings: ImageSettings,
    onPlayFromMediaIdRequested: (String?, android.os.Bundle?) -> Unit,
    onPlayFromSearchRequested: (String?, android.os.Bundle?) -> Unit,
    onExitRequested: () -> Unit,
) : ImageSettings.Listener {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + holderJob + Dispatchers.Main.immediate)
    private val mediaSession = MediaSessionCompat(context, context.packageName)
    private val playbackNotification = PlaybackNotification(context, mediaSession.sessionToken)
    private val callback =
        R16MediaSessionCommandCallback(
            scope = scope,
            commands = commands,
            onPlayFromMediaIdRequested = onPlayFromMediaIdRequested,
            onPlayFromSearchRequested = onPlayFromSearchRequested,
            onExitRequested = onExitRequested,
        )

    private var attached = false
    private var released = false
    private var stateJob: Job? = null
    private var queueJob: Job? = null
    private var queueRevision = 0L
    private var metadataRevision = 0L
    private var lastTraversal: List<R16SystemQueueItem>? = null
    private var lastDisplayIdentity: Pair<QueueEntryId?, R16RecordingPresentation?>? = null
    private var lastPlayWhenReady: Boolean? = null
    private var lastRepeatMode: RepeatMode? = null
    private var lastShuffled: Boolean? = null

    val token: MediaSessionCompat.Token
        get() = mediaSession.sessionToken

    val notification: ForegroundServiceNotification
        get() = playbackNotification

    fun attach(): MediaSessionCompat.Token {
        check(!released) { "Released R16 MediaSession holder cannot be attached" }
        if (attached) return token
        attached = true
        imageSettings.registerListener(this)
        mediaSession.apply {
            isActive = true
            setQueueTitle(context.getString(R.string.lbl_queue))
            setCallback(callback)
        }
        stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { states.collect(::render) }
        return token
    }

    fun tryMediaButtonIntent(intent: Intent): Boolean =
        MediaButtonReceiver.handleIntent(mediaSession, intent) != null

    fun release() {
        if (released) return
        released = true
        attached = false
        ++metadataRevision
        ++queueRevision
        stateJob?.cancel()
        queueJob?.cancel()
        holderJob.cancel()
        bitmapProvider.release()
        imageSettings.unregisterListener(this)
        mediaSession.apply {
            isActive = false
            release()
        }
    }

    override fun onImageSettingsChanged() {
        lastDisplayIdentity = null
        scope.launch { render(states.value) }
    }

    private fun render(state: R16SystemPlaybackState) {
        mediaSession.setPlaybackState(R16MediaSessionProjection.playbackState(state))
        renderQueue(state)
        renderMetadata(state)

        var notificationChanged = false
        if (lastPlayWhenReady != state.playback.playWhenReady) {
            lastPlayWhenReady = state.playback.playWhenReady
            playbackNotification.updatePlaying(state.playback.playWhenReady)
            notificationChanged = true
        }
        if (lastRepeatMode != state.playback.repeatMode) {
            lastRepeatMode = state.playback.repeatMode
            mediaSession.setRepeatMode(state.playback.repeatMode.toMediaSessionRepeatMode())
            playbackNotification.updateRepeatIcon(state.playback.repeatMode.iconRes)
            notificationChanged = true
        }
        if (lastShuffled != state.isShuffled) {
            lastShuffled = state.isShuffled
            mediaSession.setShuffleMode(
                if (state.isShuffled) {
                    PlaybackStateCompat.SHUFFLE_MODE_ALL
                } else {
                    PlaybackStateCompat.SHUFFLE_MODE_NONE
                }
            )
            playbackNotification.updateShuffled(state.isShuffled)
            notificationChanged = true
        }
        if (notificationChanged && !bitmapProvider.isBusy) updateForeground()
    }

    private fun renderQueue(state: R16SystemPlaybackState) {
        if (lastTraversal == state.traversal) return
        lastTraversal = state.traversal
        val revision = ++queueRevision
        queueJob?.cancel()
        queueJob =
            scope.launch {
                val queue =
                    withContext(Dispatchers.Default) { R16MediaSessionProjection.queue(state) }
                if (revision == queueRevision && !released) mediaSession.setQueue(queue)
            }
    }

    private fun renderMetadata(state: R16SystemPlaybackState) {
        val displayIdentity = state.displayQueueEntryId to state.displayItem?.presentation
        if (lastDisplayIdentity == displayIdentity) return
        lastDisplayIdentity = displayIdentity
        val revision = ++metadataRevision
        val metadata =
            R16MediaSessionProjection.metadata(
                state = state,
                unknownTitle = context.getString(R.string.cdc_unknown),
                unknownArtist = context.getString(R.string.cdc_unknown),
                defaultParent = context.getString(R.string.info_app_name),
            )
        publishMetadata(metadata)

        val artwork = state.displayItem?.presentation?.artworkLocation ?: return
        bitmapProvider.loadArtwork(
            artwork,
            object : BitmapProvider.Target {
                override fun onCompleted(bitmap: Bitmap?) {
                    if (revision != metadataRevision || bitmap == null || released) return
                    publishMetadata(
                        MediaMetadataCompat.Builder(metadata)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                            .build()
                    )
                }
            },
        )
    }

    private fun publishMetadata(metadata: MediaMetadataCompat) {
        mediaSession.setMetadata(metadata)
        playbackNotification.updateMetadata(metadata)
        updateForeground()
    }

    private fun updateForeground() {
        foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
    }
}

internal val RepeatMode.iconRes: Int
    get() =
        when (this) {
            RepeatMode.OFF -> R.drawable.ic_repeat_off_24
            RepeatMode.ONE -> R.drawable.ic_repeat_one_24
            RepeatMode.ALL -> R.drawable.ic_repeat_on_24
        }

private fun RepeatMode.toMediaSessionRepeatMode(): Int =
    when (this) {
        RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
        RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
    }
