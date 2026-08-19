/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemPlaybackReceiver.kt is part of Auxio.
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.service.PlaybackActions
import org.oxycblt.auxio.widgets.WidgetProvider

/** Inactive Android intent wrapper around [R16SystemActionRouter]. */
class R16SystemPlaybackReceiver(
    private val context: Context,
    parentScope: CoroutineScope,
    private val playbackSettings: PlaybackSettings,
    private val actionRouter: R16SystemActionRouter,
    private val widgetComponent: R16WidgetComponent,
) : BroadcastReceiver() {
    private val receiverJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + receiverJob + Dispatchers.Main.immediate)
    private var attached = false
    private var released = false
    private var initialHeadsetPlugEventHandled = false

    @Suppress("WrongConstant")
    fun attach() {
        check(!released) { "Released R16 system receiver cannot be attached" }
        if (attached) return
        attached = true
        ContextCompat.registerReceiver(
            context,
            this,
            INTENT_FILTER,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun release() {
        if (released) return
        released = true
        if (attached) {
            attached = false
            context.unregisterReceiver(this)
        }
        receiverJob.cancel()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetProvider.ACTION_WIDGET_UPDATE) {
            widgetComponent.update()
            return
        }
        val pendingResult = goAsync()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (intent.action) {
                    AudioManager.ACTION_HEADSET_PLUG -> {
                        when (intent.getIntExtra("state", -1)) {
                            0 -> actionRouter.onHeadsetDisconnected()
                            1 ->
                                actionRouter.onHeadsetConnected(
                                    autoplay = playbackSettings.headsetAutoplay,
                                    initialPlugEventHandled = initialHeadsetPlugEventHandled,
                                )
                        }
                        initialHeadsetPlugEventHandled = true
                    }
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> actionRouter.onAudioBecomingNoisy()
                    else -> actionRouter.handle(intent.action)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val INTENT_FILTER =
            IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(PlaybackActions.ACTION_INC_REPEAT_MODE)
                addAction(PlaybackActions.ACTION_INVERT_SHUFFLE)
                addAction(PlaybackActions.ACTION_SKIP_PREV)
                addAction(PlaybackActions.ACTION_PLAY_PAUSE)
                addAction(PlaybackActions.ACTION_SKIP_NEXT)
                addAction(PlaybackActions.ACTION_EXIT)
                addAction(WidgetProvider.ACTION_WIDGET_UPDATE)
            }
    }
}
