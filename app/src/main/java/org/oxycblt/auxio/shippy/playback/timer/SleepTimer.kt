/*
 * Copyright (c) 2026 Shippy contributors
 * SleepTimer.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.playback.timer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.musikr.MusicParent

enum class SleepTimerMode(val durationMs: Long? = null) {
    OFF,
    FINISH_CURRENT,
    MINUTES_15(15L * 60L * 1000L),
    MINUTES_30(30L * 60L * 1000L),
    MINUTES_45(45L * 60L * 1000L),
    MINUTES_60(60L * 60L * 1000L),
}

data class SleepTimerPresentation(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val deadlineElapsedMs: Long? = null,
)

internal fun interface SleepTimerClock {
    fun elapsedRealtime(): Long
}

internal interface SleepTimerScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): SleepTimerCancellation
}

internal fun interface SleepTimerCancellation {
    fun cancel()
}

internal fun interface SleepTimerPlaybackPort {
    fun pause()
}

/** Pure arming and expiry policy; the controller owns Android scheduling and playback wiring. */
internal class SleepTimerPolicy {
    private var mode = SleepTimerMode.OFF
    private var deadlineElapsedMs: Long? = null
    private var finishItemId: QueueItemId? = null

    fun arm(mode: SleepTimerMode, currentItemId: QueueItemId?, nowElapsedMs: Long): SleepTimerPresentation {
        this.mode = mode
        deadlineElapsedMs = mode.durationMs?.let(nowElapsedMs::plus)
        finishItemId = if (mode == SleepTimerMode.FINISH_CURRENT) currentItemId else null
        if (mode == SleepTimerMode.FINISH_CURRENT && currentItemId == null) {
            this.mode = SleepTimerMode.OFF
        }
        return presentation()
    }

    fun reset(): SleepTimerPresentation {
        mode = SleepTimerMode.OFF
        deadlineElapsedMs = null
        finishItemId = null
        return presentation()
    }

    fun onCurrentItem(currentItemId: QueueItemId?): Boolean {
        if (mode != SleepTimerMode.FINISH_CURRENT || currentItemId == finishItemId) return false
        reset()
        return true
    }

    fun onElapsed(nowElapsedMs: Long): Boolean {
        val deadline = deadlineElapsedMs ?: return false
        if (nowElapsedMs < deadline) return false
        reset()
        return true
    }

    fun presentation(): SleepTimerPresentation = SleepTimerPresentation(mode, deadlineElapsedMs)
}

@Singleton
class SleepTimerController
private constructor(
    private val playback: SleepTimerPlaybackPort,
    private val clock: SleepTimerClock,
    private val scheduler: SleepTimerScheduler,
    private val policy: SleepTimerPolicy,
) : PlaybackStateManager.Listener {
    @Inject
    constructor(playbackManager: PlaybackStateManager) :
        this(
            SleepTimerPlaybackPort { playbackManager.playing(false) },
            SleepTimerClock { SystemClock.elapsedRealtime() },
            HandlerSleepTimerScheduler,
            SleepTimerPolicy(),
        )

    internal constructor(
        playback: SleepTimerPlaybackPort,
        clock: SleepTimerClock,
        scheduler: SleepTimerScheduler,
    ) : this(playback, clock, scheduler, SleepTimerPolicy())

    private val _state = MutableStateFlow(SleepTimerPresentation())
    val state: StateFlow<SleepTimerPresentation> = _state

    private var attached = false
    private var cancellation: SleepTimerCancellation? = null
    private var canonicalQueue: List<ResolvedQueueItem> = emptyList()
    private var currentIndex = -1

    fun attach(manager: PlaybackStateManager) {
        if (attached) return
        attached = true
        manager.addListener(this)
    }

    fun release(manager: PlaybackStateManager) {
        if (attached) manager.removeListener(this)
        attached = false
        canonicalQueue = emptyList()
        currentIndex = -1
        cancelAndPublish(policy.reset())
    }

    fun select(mode: SleepTimerMode) {
        val presentation = policy.arm(mode, currentItemId(), clock.elapsedRealtime())
        cancelAndPublish(presentation)
        scheduleDeadline(presentation.deadlineElapsedMs)
    }

    override fun onCanonicalNewPlayback(
        parent: MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = updateCanonicalQueue(queue, index)

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: QueueChange,
    ) = updateCanonicalQueue(queue, index)

    override fun onCanonicalQueueReordered(
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = updateCanonicalQueue(queue, index)

    override fun onIndexMoved(index: Int) {
        currentIndex = index
        checkFinishCurrent()
    }

    private fun updateCanonicalQueue(queue: List<ResolvedQueueItem>, index: Int) {
        canonicalQueue = queue
        currentIndex = index
        checkFinishCurrent()
    }

    private fun checkFinishCurrent() {
        if (policy.onCurrentItem(currentItemId())) {
            cancelAndPublish(policy.presentation())
            playback.pause()
        }
    }

    private fun scheduleDeadline(deadlineElapsedMs: Long?) {
        deadlineElapsedMs ?: return
        cancellation =
            scheduler.schedule((deadlineElapsedMs - clock.elapsedRealtime()).coerceAtLeast(0)) {
                if (policy.onElapsed(clock.elapsedRealtime())) {
                    cancelAndPublish(policy.presentation())
                    playback.pause()
                }
            }
    }

    private fun cancelAndPublish(presentation: SleepTimerPresentation) {
        cancellation?.cancel()
        cancellation = null
        _state.value = presentation
    }

    private fun currentItemId(): QueueItemId? = canonicalQueue.getOrNull(currentIndex)?.item?.id

    private companion object HandlerSleepTimerScheduler : SleepTimerScheduler {
        private val handler = Handler(Looper.getMainLooper())

        override fun schedule(delayMs: Long, task: () -> Unit): SleepTimerCancellation {
            val runnable = Runnable(task)
            handler.postDelayed(runnable, delayMs)
            return SleepTimerCancellation { handler.removeCallbacks(runnable) }
        }
    }
}
