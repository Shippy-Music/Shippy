/*
 * Copyright (c) 2026 Shippy contributors
 * CrewPlaybackBridge.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.state.PlaybackCommandFactoryImpl
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewSubmitResult
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.crew.media.CrewPrivateSourceRegistry
import org.oxycblt.auxio.shippy.crew.media.publicizeCrewQueue

private const val RECONCILE_DELAY_MS = 150L
private const val SEEK_DRIFT_MS = 900L

/**
 * Lifecycle bridge between the active Crew's canonical state and Auxio's one playback manager.
 * It deliberately owns neither a player nor a queue.
 */
@Singleton
class CrewPlaybackBridge
@Inject
constructor(
    private val activeCrewRuntime: ActiveCrewRuntime,
    private val playbackManager: PlaybackStateManager,
    private val resolutionCoordinator: PlaybackResolutionCoordinator,
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val crewSettings: CrewSettings,
    private val temporaryMediaIndex: CrewTemporaryMediaIndex,
    private val privateSources: CrewPrivateSourceRegistry,
) : PlaybackStateManager.Listener {
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null
    private var completionJob: Job? = null
    private var reconcileJob: Job? = null
    private var attached = false
    private var applyingRemote = false
    private var seededSession: String? = null
    @Volatile private var latestCrew: CrewState? = null
    @Volatile private var localCrewMemberId: CrewMemberId? = null
    private val applyMutex = Mutex()

    fun attach() {
        if (attached) return
        attached = true
        // PlaybackStateManager ultimately drives ExoPlayer, whose application
        // thread is the main looper. Provider implementations move their own
        // blocking work off-thread.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        playbackManager.addListener(this)
        stateJob = newScope.launch {
            activeCrewRuntime.state.collectLatest { runtimeState ->
                val active = runtimeState as? ActiveCrewRuntimeState.Active
                latestCrew = active?.presentation?.crewState
                localCrewMemberId = active?.presentation?.localMemberId
                if (active != null) {
                    applyMutex.withLock {
                        applyCrew(active.presentation.role, active.presentation.crewState)
                    }
                }
            }
        }
        completionJob =
            newScope.launch {
                temporaryMediaIndex.completions.collect { completion ->
                    val active =
                        activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active
                            ?: return@collect
                    val crew = active.presentation.crewState
                    if (
                        crew.sessionId != completion.sessionId ||
                            crew.queue.none { item ->
                                item.id == completion.queueItemId &&
                                    item.track.candidates.any {
                                        it.id == completion.candidateId
                                    }
                            }
                    ) {
                        return@collect
                    }
                    applyMutex.withLock {
                        applyCrew(active.presentation.role, crew, forceQueueResolution = true)
                    }
                }
            }
    }

    fun release() {
        if (!attached) return
        attached = false
        playbackManager.removeListener(this)
        reconcileJob?.cancel()
        stateJob?.cancel()
        completionJob?.cancel()
        scope?.cancel()
        reconcileJob = null
        stateJob = null
        completionJob = null
        scope = null
        latestCrew = null
        localCrewMemberId = null
        applyingRemote = false
        seededSession = null
    }

    override fun onIndexMoved(index: Int) = scheduleReconciliation()
    override fun onCanonicalQueueChanged(queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>, index: Int, change: org.oxycblt.auxio.playback.state.QueueChange) = scheduleReconciliation()
    override fun onCanonicalQueueReordered(queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>, index: Int, isShuffled: Boolean) = scheduleReconciliation()
    override fun onCanonicalNewPlayback(parent: org.oxycblt.musikr.MusicParent?, queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>, index: Int, isShuffled: Boolean) = scheduleReconciliation()
    override fun onProgressionChanged(progression: Progression) = scheduleReconciliation()
    override fun onRepeatModeChanged(repeatMode: RepeatMode) = scheduleReconciliation()

    private fun scheduleReconciliation() {
        if (!attached || applyingRemote) return
        reconcileJob?.cancel()
        reconcileJob = scope?.launch {
            delay(RECONCILE_DELAY_MS)
            reconcilePlayerToCrew()
        }
    }

    private suspend fun applyCrew(
        role: ActiveCrewMode,
        crew: CrewState,
        forceQueueResolution: Boolean = false,
    ) {
        if (role == ActiveCrewMode.HOST && crew.queue.isEmpty() && playbackManager.queueItems.isNotEmpty()) {
            val key = crew.sessionId.toString()
            if (seededSession != key) {
                seededSession = key
                // Reconcile from a sibling debounce job. Submitting the first
                // coordinator action updates this StateFlow and collectLatest
                // would otherwise cancel the remaining seed actions.
                scheduleReconciliation()
            }
            return
        }
        if (crew.queue.isEmpty()) return

        applyingRemote = true
        try {
            val targetId = crew.playback.currentQueueItemId
            val playerIds = playbackManager.queueItems.map(QueueItem::id)
            val crewIds = crew.queue.map(QueueItem::id)
            var replacedQueue = false
            if (forceQueueResolution || playerIds != crewIds) {
                val policy =
                    ResolutionPolicy(
                        providerPriority =
                            providerSettings
                                .selection(
                                    providerRegistry
                                        .supporting(ProviderCapability.STREAM)
                                        .map { it.descriptor.id }
                                )
                                .priority,
                        pushPullEnabled = crewSettings.pushPullEnabled,
                    )
                val prepared =
                    ArrayList<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>(
                        crew.queue.size
                    )
                for (item in crew.queue) {
                    val projected = privateSources.overlay(crew.sessionId, localCrewMemberId ?: return, item)
                    when (val result = resolutionCoordinator.prepare(projected, policy)) {
                        is PlaybackPreparation.Ready -> prepared += result.value
                        is PlaybackPreparation.Failed ->
                            return // Leave the working player intact for prefetch/retry.
                    }
                }
                playbackManager.play(
                    PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                        selectedItemId = targetId ?: prepared.first().item.id,
                        queue = prepared,
                        parent = null,
                        shuffled = crew.shuffleEnabled,
                    )
                )
                replacedQueue = true
            } else {
                targetId?.let { id ->
                    val targetIndex = playbackManager.queueItems.indexOfFirst { it.id == id }
                    if (
                        crew.playback.mode != CrewPlaybackMode.PREPARING &&
                            targetIndex >= 0 &&
                            playbackManager.currentQueueItem?.id != id
                    ) {
                        playbackManager.goto(targetIndex)
                    }
                }
                if (playbackManager.isShuffled != crew.shuffleEnabled) playbackManager.shuffled(crew.shuffleEnabled)
            }
            val repeat = crew.repeatMode.toPlayerRepeatMode()
            if (playbackManager.repeatMode != repeat) playbackManager.repeatMode(repeat)
            val targetPosition = crewPositionAt(crew.playback, monotonicNowMs())
            val positionIsAuthoritative =
                crew.playback.mode == CrewPlaybackMode.PLAYING ||
                    crew.playback.mode == CrewPlaybackMode.PAUSED
            if (
                positionIsAuthoritative &&
                    targetId != null &&
                    playbackManager.currentQueueItem?.id == targetId &&
                    kotlin.math.abs(
                        playbackManager.progression.calculateElapsedPositionMs() -
                            targetPosition
                    ) > SEEK_DRIFT_MS
            ) {
                playbackManager.seekTo(targetPosition)
            }
            when (crew.playback.mode) {
                CrewPlaybackMode.PLAYING -> if (!playbackManager.progression.isPlaying) playbackManager.playing(true)
                CrewPlaybackMode.PAUSED -> if (playbackManager.progression.isPlaying) playbackManager.playing(false)
                CrewPlaybackMode.ENDED ->
                    if (playbackManager.progression.isPlaying) {
                        playbackManager.playing(false)
                    }
                CrewPlaybackMode.IDLE,
                CrewPlaybackMode.PREPARING,
                CrewPlaybackMode.BUFFERING ->
                    // newPlayback() starts ExoPlayer immediately. A queue event
                    // in PREPARING must not audibly race the later scheduled Play.
                    if (replacedQueue && playbackManager.progression.isPlaying) {
                        playbackManager.playing(false)
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A provider/player failure must not take down playback service or the active Crew.
        } finally {
            applyingRemote = false
        }
    }

    private suspend fun reconcilePlayerToCrew() {
        val crew = latestCrew ?: return
        val snapshot = playerSnapshot(playbackManager, monotonicNowMs(), localCrewMemberId)
        for (action in crewDiff(crew, snapshot)) {
            val accepted = activeCrewRuntime.submit(action) as? ActiveCrewSubmitResult.Accepted
                ?: return
            when (accepted.result) {
                is CrewSubmitResult.Submitted,
                is CrewSubmitResult.AlreadyPending -> Unit
                CrewSubmitResult.CapacityReached,
                is CrewSubmitResult.Rejected -> return
            }
        }
    }
}

internal data class PlayerCrewSnapshot(
    val queue: List<QueueItem>,
    val currentItemId: QueueItemId?,
    val playing: Boolean,
    val positionMs: Long,
    val sessionEpochMs: Long,
    val shuffled: Boolean,
    val repeatMode: CrewRepeatMode,
)

/** Removes device-only playback material before a queue crosses into the Crew protocol. */
internal fun canonicalQueueForCrew(items: List<QueueItem>): List<QueueItem> =
    items.map { item ->
        // Keep LOCAL for this first top-down slice: current supplier authorization relies on it.
        // Private local-locator projection is a follow-up refinement.
        item.copy(track = item.track.copy(candidates = item.track.candidates.filterNot {
            it.kind == CandidateKind.CREW_TEMPORARY || it.kind == CandidateKind.DOWNLOAD
        }))
    }

/** Stamps only local introductions; preserved contributor IDs remain remote provenance. */
internal fun stampCrewContributor(items: List<QueueItem>, localMemberId: CrewMemberId?): List<QueueItem> =
    if (localMemberId == null) items else items.map { item ->
        if (item.contributorId == null && item.track.candidates.any { it.kind == CandidateKind.LOCAL }) {
            item.copy(contributorId = localMemberId.value)
        } else item
    }

internal fun crewPositionAt(playback: org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState, nowMs: Long): Long {
    if (playback.mode != CrewPlaybackMode.PLAYING) return playback.positionAtEpochMs.coerceAtLeast(0)
    val elapsed = (nowMs - playback.sessionEpochMs).coerceAtLeast(0)
    return if (Long.MAX_VALUE - playback.positionAtEpochMs < elapsed) Long.MAX_VALUE
    else playback.positionAtEpochMs + elapsed
}

private fun playerSnapshot(manager: PlaybackStateManager, nowMs: Long, localMemberId: CrewMemberId?) =
    PlayerCrewSnapshot(
        queue = stampCrewContributor(canonicalQueueForCrew(manager.queueItems), localMemberId),
        currentItemId = manager.currentQueueItem?.id,
        playing = manager.progression.isPlaying,
        positionMs = manager.progression.calculateElapsedPositionMs().coerceAtLeast(0),
        sessionEpochMs = nowMs,
        shuffled = manager.isShuffled,
        repeatMode = manager.repeatMode.toCrewRepeatMode(),
    )

internal fun crewDiff(crew: CrewState, player: PlayerCrewSnapshot): List<CrewAction> {
    val actions = mutableListOf<CrewAction>()
    val queueChanged = publicizeCrewQueue(canonicalQueueForCrew(crew.queue)) != publicizeCrewQueue(player.queue)
    if (queueChanged) actions += CrewAction.QueueReplaced(player.queue)
    if (player.currentItemId != null && player.currentItemId != crew.playback.currentQueueItemId) actions += CrewAction.CurrentItemChanged(player.currentItemId)
    if (player.shuffled != crew.shuffleEnabled) actions += CrewAction.ShuffleChanged(player.shuffled)
    if (player.repeatMode != crew.repeatMode) actions += CrewAction.RepeatChanged(player.repeatMode)
    val crewPosition = crewPositionAt(crew.playback, player.sessionEpochMs)
    val crewHasPlaybackIntent =
        crew.playback.mode == CrewPlaybackMode.PLAYING ||
            crew.playback.mode == CrewPlaybackMode.PAUSED
    if (
        !crewHasPlaybackIntent &&
            player.currentItemId != null &&
            (queueChanged || crew.queue.isEmpty())
    ) {
        actions +=
            if (player.playing) CrewAction.Play(player.positionMs, player.sessionEpochMs)
            else CrewAction.Pause(player.positionMs, player.sessionEpochMs)
    } else if (player.playing != (crew.playback.mode == CrewPlaybackMode.PLAYING)) {
        actions += if (player.playing) CrewAction.Play(player.positionMs, player.sessionEpochMs)
        else CrewAction.Pause(player.positionMs, player.sessionEpochMs)
    } else if (
        crewHasPlaybackIntent &&
            kotlin.math.abs(player.positionMs - crewPosition) > SEEK_DRIFT_MS
    ) {
        actions += CrewAction.Seek(player.positionMs, player.sessionEpochMs)
    }
    return actions
}

private fun CrewRepeatMode.toPlayerRepeatMode() = when (this) {
    CrewRepeatMode.OFF -> RepeatMode.NONE
    CrewRepeatMode.ALL -> RepeatMode.ALL
    CrewRepeatMode.ONE -> RepeatMode.TRACK
}

private fun RepeatMode.toCrewRepeatMode() = when (this) {
    RepeatMode.NONE -> CrewRepeatMode.OFF
    RepeatMode.ALL -> CrewRepeatMode.ALL
    RepeatMode.TRACK -> CrewRepeatMode.ONE
}

private fun monotonicNowMs(): Long = android.os.SystemClock.elapsedRealtime()
