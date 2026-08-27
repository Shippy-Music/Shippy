/*
 * Copyright (c) 2024 Auxio Project
 * AuxioService.kt is part of Auxio.
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
package org.oxycblt.auxio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat.MediaItem
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaSessionManager
import androidx.media.utils.MediaConstants
import app.shippy.data.browser.R16MediaBrowserIdCodec
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.oxycblt.auxio.image.BitmapProvider
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.music.service.MusicServiceFragment
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.service.AudioOnlyPlayerFactory
import org.oxycblt.auxio.playback.service.PlaybackServiceFragment
import org.oxycblt.auxio.shippy.lastfm.R16LastFmOutboxWorkScheduler
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityMode
import org.oxycblt.auxio.shippy.r16.authority.R16AuthoritySelector
import org.oxycblt.auxio.shippy.r16.browser.R16BrowserPlaybackRouter
import org.oxycblt.auxio.shippy.r16.browser.R16BrowserQueueResolver
import org.oxycblt.auxio.shippy.r16.browser.R16MediaBrowserServiceAdapter
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.oxycblt.auxio.shippy.r16.playback.R16PlaybackSpine
import org.oxycblt.auxio.shippy.r16.playback.R16PlaybackSpineFactory
import org.oxycblt.auxio.shippy.r16.playback.service.R16PlaybackServiceOwner
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionHolder
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemActionRouter
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemPlaybackCommands
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemPlaybackReceiver
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemSurfaceRuntime
import org.oxycblt.auxio.shippy.r16.playback.system.R16WidgetComponent
import org.oxycblt.auxio.ui.UISettings
import timber.log.Timber

@AndroidEntryPoint
class AuxioService :
    MediaBrowserServiceCompat(), ForegroundListener, MusicServiceFragment.Invalidator {
    @Inject lateinit var authoritySelector: R16AuthoritySelector
    @Inject lateinit var migrationGate: R16MigrationProcessGate
    @Inject lateinit var playbackFragmentFactory: Lazy<PlaybackServiceFragment.Factory>
    private var playbackFragment: PlaybackServiceFragment? = null

    @Inject lateinit var musicFragmentFactory: Lazy<MusicServiceFragment.Factory>
    private var musicFragment: MusicServiceFragment? = null
    private var legacyAccessLease: Closeable? = null
    private var legacyAuthority = false

    @Inject lateinit var activeDataRuntimeOwner: R16ActiveDataRuntimeOwner
    @Inject lateinit var audioOnlyPlayerFactory: AudioOnlyPlayerFactory
    @Inject lateinit var playbackSpineFactory: R16PlaybackSpineFactory
    @Inject lateinit var playbackSettings: PlaybackSettings
    @Inject lateinit var imageSettings: ImageSettings
    @Inject lateinit var uiSettings: UISettings
    @Inject lateinit var bitmapProviderFactory: Provider<BitmapProvider>
    @Inject lateinit var lastFmOutboxWorkScheduler: R16LastFmOutboxWorkScheduler

    private var r16Authority = false
    private var r16Attached = false
    private var r16ReleaseRequested = false
    private var r16PendingMediaButtonIntent: Intent? = null
    private var r16Scope: CoroutineScope? = null
    private var r16AttachJob: Job? = null
    private var r16Owner: R16PlaybackServiceOwner? = null
    private var r16Spine: R16PlaybackSpine? = null
    private var r16BrowserRouter: R16BrowserPlaybackRouter? = null
    private var r16BrowserAdapter: R16MediaBrowserServiceAdapter? = null
    private var r16Surfaces: R16SystemSurfaceRuntime? = null

    @SuppressLint("WrongConstant")
    override fun onCreate() {
        super.onCreate()
        when (authoritySelector.select()) {
            R16AuthorityMode.LEGACY -> onCreateLegacy()
            R16AuthorityMode.ACTIVE -> onCreateR16()
            else -> {
                Timber.w("Skipping service startup for unavailable R16 authority mode")
                stopSelf()
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun onCreateLegacy() {
        val lease = migrationGate.tryAcquireLegacyAccess()
        if (lease == null) {
            Timber.w("Skipping legacy service startup while R16 migration is active")
            stopSelf()
            return
        }
        legacyAccessLease = lease
        legacyAuthority = true

        var initialized = false
        try {
            val playback = playbackFragmentFactory.get().create(this, this)
            playbackFragment = playback
            val music = musicFragmentFactory.get().create(this, this, this)
            musicFragment = music
            sessionToken = playback.attach()
            music.attach()
            initialized = true
            Timber.d("Service Created")
        } finally {
            if (!initialized) {
                legacyAuthority = false
                musicFragment?.release()
                playbackFragment?.release()
                musicFragment = null
                playbackFragment = null
                legacyAccessLease?.close()
                legacyAccessLease = null
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun onCreateR16() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        r16Scope = scope
        r16ReleaseRequested = false
        try {
            val data = activeDataRuntimeOwner.activeRuntimeOrNull()
            if (data == null) {
                Timber.w("R16 ACTIVE service branch has no enabled data runtime")
                scope.cancel()
                r16Scope = null
                stopSelf()
                return
            }

            val spine =
                playbackSpineFactory.create(scope, audioOnlyPlayerFactory.createR16().player, data)
            r16Spine = spine
            val commands =
                R16SystemPlaybackCommands(
                    states = spine.system.states,
                    router = spine.system,
                    shuffleSeed = System::nanoTime,
                )
            val browserRouter =
                R16BrowserPlaybackRouter(
                    parentScope = scope,
                    resolver = R16BrowserQueueResolver(data.mediaBrowser),
                    commands = spine.system,
                )
            r16BrowserRouter = browserRouter
            val browserAdapter = R16MediaBrowserServiceAdapter(data.mediaBrowser, browserRouter)
            r16BrowserAdapter = browserAdapter

            val actionRouter = R16SystemActionRouter(commands) { stopSelf() }
            val sessionBitmapProvider = bitmapProviderFactory.get()
            val widgetBitmapProvider = bitmapProviderFactory.get()
            check(sessionBitmapProvider !== widgetBitmapProvider) {
                "R16 system surfaces must not share a BitmapProvider"
            }
            val mediaSession =
                R16MediaSessionHolder(
                    context = this,
                    parentScope = scope,
                    foregroundListener = this,
                    states = spine.system.states,
                    commands = commands,
                    bitmapProvider = sessionBitmapProvider,
                    imageSettings = imageSettings,
                    onPlayFromMediaIdRequested = { mediaId, extras ->
                        browserAdapter.playFromMediaId(mediaId, extras)
                    },
                    onPlayFromSearchRequested = { query, _ -> browserRouter.playFromSearch(query) },
                    onExitRequested = ::stopSelf,
                    queueEndpoint = spine.system.queueEndpoint,
                )
            val widget =
                R16WidgetComponent(
                    context = this,
                    parentScope = scope,
                    states = spine.system.states,
                    imageSettings = imageSettings,
                    bitmapProvider = widgetBitmapProvider,
                    uiSettings = uiSettings,
                )
            val receiver =
                R16SystemPlaybackReceiver(
                    context = this,
                    parentScope = scope,
                    playbackSettings = playbackSettings,
                    actionRouter = actionRouter,
                    widgetComponent = widget,
                )
            val surfaces = R16SystemSurfaceRuntime(mediaSession, widget, receiver)
            r16Surfaces = surfaces
            val owner = R16PlaybackServiceOwner(spine, surfaces)
            r16Owner = owner
            r16Authority = true
            // MediaBrowser clients may bind before the asynchronous playback restore finishes.
            // The token exists with the constructed session and does not expose legacy authority.
            sessionToken = surfaces.token
            r16AttachJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        owner.attach(allowResume = true)
                        if (r16ReleaseRequested || !r16Authority) {
                            owner.release()
                            return@launch
                        }
                        sessionToken = surfaces.token
                        r16Attached = true
                        r16PendingMediaButtonIntent?.also(surfaces::tryMediaButtonIntent)
                        r16PendingMediaButtonIntent = null
                        lastFmOutboxWorkScheduler.schedule()
                        onHandleR16Foreground()
                        Timber.d("R16 ACTIVE service attached")
                    } catch (error: Exception) {
                        Timber.e(error, "R16 ACTIVE service attach failed")
                        releaseR16()
                        stopSelf()
                    }
                }
        } catch (error: Exception) {
            Timber.e(error, "R16 ACTIVE service composition failed")
            releaseR16()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (r16Authority) {
            super.onStartCommand(intent, flags, startId)
            // Media-button broadcasts can start the service directly, bypassing the dynamic
            // system receiver. Route them through the same R16 MediaSession callback.
            if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                if (r16Attached) {
                    r16Surfaces?.tryMediaButtonIntent(intent)
                } else {
                    r16PendingMediaButtonIntent = intent
                }
            }
            onHandleR16Foreground()
            return START_NOT_STICKY
        }
        if (!legacyAuthority) return START_NOT_STICKY
        // TODO: Start command occurring from a foreign service basically implies a detached
        //  service, we might need more handling here.
        super.onStartCommand(intent, flags, startId)
        if (!legacyAuthority || migrationGate.isBlocked()) return START_NOT_STICKY
        onHandleForeground(intent)
        // If we die we want to not restart, we will immediately try to foreground in and just
        // fail to start again since the activity will be dead too. This is not the semantically
        // "correct" flag (normally you want START_STICKY for playback) but we need this to avoid
        // weird foreground errors.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        if (r16Authority) {
            if (r16Surfaces == null) return null
            val binder = super.onBind(intent) ?: return null
            onHandleR16Foreground()
            return binder
        }
        if (!legacyAuthority || migrationGate.isBlocked()) return null
        val binder = super.onBind(intent)
        if (!legacyAuthority || migrationGate.isBlocked()) return null
        onHandleForeground(intent)
        return binder
    }

    private fun onHandleForeground(intent: Intent?) {
        if (!legacyAuthority) return
        val music = musicFragment ?: return
        val playback = playbackFragment ?: return
        migrationGate.runWithLegacyAccess {
            music.start()
            playback.start(intent)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (r16Authority) {
            val owner = r16Owner ?: return
            val scope = r16Scope ?: return
            scope.launch {
                try {
                    if (
                        owner.handleTaskRemoved(
                            exitOnTaskRemoval = playbackSettings.exitOnTaskRemoval,
                            hasActiveCrew = hasActiveCrewForR16(),
                        )
                    ) {
                        stopSelf()
                    }
                } catch (error: Exception) {
                    Timber.e(error, "R16 task-removal handling failed")
                    stopSelf()
                }
            }
            return
        }
        if (!legacyAuthority) return
        val playback = playbackFragment ?: return
        migrationGate.runWithLegacyAccess { playback.handleTaskRemoved() }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseR16()
        legacyAuthority = false
        musicFragment?.release()
        playbackFragment?.release()
        musicFragment = null
        playbackFragment = null
        legacyAccessLease?.close()
        legacyAccessLease = null
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (r16Authority) {
            if (r16BrowserAdapter == null) return null
            val remote = MediaSessionManager.RemoteUserInfo(clientPackageName, -1, clientUid)
            if (
                clientUid != applicationInfo.uid &&
                    !MediaSessionManager.getSessionManager(this).isTrustedForMediaControl(remote)
            ) {
                return null
            }
            return BrowserRoot(R16MediaBrowserIdCodec.ROOT, null)
        }
        if (!legacyAuthority) return null
        val remote = MediaSessionManager.RemoteUserInfo(clientPackageName, -1, clientUid)
        if (
            clientUid != applicationInfo.uid &&
                !MediaSessionManager.getSessionManager(this).isTrustedForMediaControl(remote)
        ) {
            return null
        }
        val music = musicFragment ?: return null
        return migrationGate.runWithLegacyAccess { music.getRoot() }
    }

    override fun onLoadItem(itemId: String, result: Result<MediaItem>) {
        if (r16Authority) {
            val adapter = r16BrowserAdapter
            val scope = r16Scope
            if (!r16Attached || adapter == null || scope == null) {
                result.sendResult(null)
                return
            }
            result.detach()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val item =
                    try {
                        adapter.loadItem(itemId)
                    } catch (error: Exception) {
                        Timber.e(error, "R16 browser item load failed")
                        null
                    }
                result.sendResult(item)
            }
            return
        }
        if (!legacyAuthority) {
            result.sendResult(null)
            return
        }
        val music = musicFragment
        if (music == null) {
            result.sendResult(null)
            return
        }
        if (migrationGate.runWithLegacyAccess { music.getItem(itemId, result) } == null) {
            result.sendResult(null)
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (r16Authority) {
            loadR16Children(parentId, result, null)
            return
        }
        if (!legacyAuthority) {
            result.sendResult(null)
            return
        }
        val maximumRootChildLimit = getRootChildrenLimit()
        val music = musicFragment
        if (music == null) {
            result.sendResult(null)
            return
        }
        if (
            migrationGate.runWithLegacyAccess {
                music.getChildren(parentId, maximumRootChildLimit, result)
            } == null
        ) {
            result.sendResult(null)
        }
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaItem>>,
        options: Bundle,
    ) {
        if (r16Authority) {
            loadR16Children(parentId, result, options)
            return
        }
        if (!legacyAuthority) {
            result.sendResult(null)
            return
        }
        val maximumRootChildLimit = getRootChildrenLimit()
        val music = musicFragment
        if (music == null) {
            result.sendResult(null)
            return
        }
        if (
            migrationGate.runWithLegacyAccess {
                music.getChildren(parentId, maximumRootChildLimit, result)
            } == null
        ) {
            result.sendResult(null)
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        if (r16Authority) {
            val adapter = r16BrowserAdapter
            val scope = r16Scope
            if (!r16Attached || adapter == null || scope == null) {
                result.sendResult(null)
                return
            }
            result.detach()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val items =
                    try {
                        adapter.search(query, extras)
                    } catch (error: Exception) {
                        Timber.e(error, "R16 browser search failed")
                        null
                    }
                result.sendResult(items?.toMutableList())
            }
            return
        }
        if (!legacyAuthority) {
            result.sendResult(null)
            return
        }
        val music = musicFragment
        if (music == null) {
            result.sendResult(null)
            return
        }
        if (migrationGate.runWithLegacyAccess { music.search(query, result) } == null) {
            result.sendResult(null)
        }
    }

    private fun loadR16Children(
        parentId: String,
        result: Result<MutableList<MediaItem>>,
        options: Bundle?,
    ) {
        val adapter = r16BrowserAdapter
        val scope = r16Scope
        if (!r16Attached || adapter == null || scope == null) {
            result.sendResult(null)
            return
        }
        result.detach()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val items =
                try {
                    adapter.loadChildren(parentId, options, browserRootHints)
                } catch (error: Exception) {
                    Timber.e(error, "R16 browser children load failed")
                    null
                }
            result.sendResult(items?.toMutableList())
        }
    }

    private fun onHandleR16Foreground() {
        if (!r16Authority || !r16Attached) return
        val notification = r16Surfaces?.notification ?: return
        startForeground(notification.code, notification.build())
        isForeground = true
    }

    private fun hasActiveCrewForR16(): Boolean {
        // Crew integration is not part of this service slice. Keep the placeholder isolated so
        // task-removal policy cannot accidentally claim a Crew session that is not owned here.
        return false
    }

    private fun releaseR16() {
        if (r16ReleaseRequested) return
        r16ReleaseRequested = true
        r16Authority = false
        r16Attached = false
        r16PendingMediaButtonIntent = null
        val scope = r16Scope
        val owner = r16Owner
        val spine = r16Spine
        val surfaces = r16Surfaces
        val browserAdapter = r16BrowserAdapter
        val browserRouter = r16BrowserRouter
        r16Owner = null
        r16Spine = null
        r16Surfaces = null
        r16BrowserAdapter = null
        r16BrowserRouter = null
        browserAdapter?.release() ?: browserRouter?.release()
        if (scope == null) {
            surfaces?.release()
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (owner != null) owner.release()
                else {
                    surfaces?.release()
                    spine?.release()
                }
            } catch (error: Exception) {
                Timber.e(error, "R16 service release failed")
            } finally {
                scope.cancel()
                r16Scope = null
            }
        }
    }

    private fun getRootChildrenLimit(): Int {
        return browserRootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT,
            4,
        ) ?: 4
    }

    override fun updateForeground(change: ForegroundListener.Change) {
        if (r16Authority) {
            onHandleR16Foreground()
            return
        }
        if (!legacyAuthority) return
        val playback = playbackFragment ?: return
        val music = musicFragment ?: return
        migrationGate.runWithLegacyAccess {
            val mediaNotification = playback.notification
            if (mediaNotification != null) {
                if (change == ForegroundListener.Change.MEDIA_SESSION) {
                    startForeground(mediaNotification.code, mediaNotification.build())
                }
                // Nothing changed, but don't show anything music related since we can always
                // index during playback.
                isForeground = true
            } else {
                music.createNotification {
                    if (it != null) {
                        startForeground(it.code, it.build())
                        isForeground = true
                    } else {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        isForeground = false
                    }
                }
            }
        }
    }

    override fun invalidateMusic(mediaId: String) {
        if (!legacyAuthority || migrationGate.isBlocked()) return
        notifyChildrenChanged(mediaId)
    }

    companion object {
        const val ACTION_START = BuildConfig.APPLICATION_ID + ".service.START"

        var isForeground = false
            private set

        // This is only meant for Auxio to internally ensure that it's state management will work.
        const val INTENT_KEY_START_ID = BuildConfig.APPLICATION_ID + ".service.START_ID"
    }
}

interface ForegroundListener {
    fun updateForeground(change: Change)

    enum class Change {
        MEDIA_SESSION,
        INDEXER,
    }
}

/**
 * Wrapper around [NotificationCompat.Builder] intended for use for [NotificationCompat]s that
 * signal a Service's ongoing foreground state.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
abstract class ForegroundServiceNotification(context: Context, info: ChannelInfo) :
    NotificationCompat.Builder(context, info.id) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        // Set up the notification channel. Foreground notifications are non-substantial, and
        // thus make no sense to have lights, vibration, or lead to a notification badge.
        val channel =
            NotificationChannelCompat.Builder(info.id, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(info.nameRes))
                .setLightsEnabled(false)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * The code used to identify this notification.
     *
     * @see NotificationManagerCompat.notify
     */
    abstract val code: Int

    /**
     * Reduced representation of a [NotificationChannelCompat].
     *
     * @param id The ID of the channel.
     * @param nameRes A string resource ID corresponding to the human-readable name of this channel.
     */
    data class ChannelInfo(val id: String, @StringRes val nameRes: Int)
}
