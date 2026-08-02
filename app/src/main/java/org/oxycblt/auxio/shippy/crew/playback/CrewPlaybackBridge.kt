/*
 * Copyright (c) 2026 Auxio Project
 * CrewPlaybackBridge.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.service.PlaybackTransitionGuard
import org.oxycblt.auxio.playback.state.PlaybackCommandFactoryImpl
import org.oxycblt.auxio.playback.state.PlaybackMutation
import org.oxycblt.auxio.playback.state.PlaybackMutationInterceptor
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.media.CrewPrivateSourceRegistry
import org.oxycblt.auxio.shippy.crew.media.publicizeCrewQueue
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewSubmitResult
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.crew.sync.CrewDriftDecision
import org.oxycblt.auxio.shippy.crew.sync.CrewDriftPolicy
import org.oxycblt.auxio.shippy.crew.sync.correctPlaybackDrift
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import timber.log.Timber as L

private const val DRIFT_RECONCILE_INTERVAL_MS = 2_000L
internal const val CREW_START_LEAD_MS = 300L
private val CREW_DRIFT_POLICY =
    CrewDriftPolicy(
        ignoredDriftMs = 120,
        speedCorrectionLimitMs = 900,
        speedCorrectionFraction = 0.025,
    )

/**
 * Lifecycle bridge between the active Crew's canonical state and Auxio's one playback manager. It
 * deliberately owns neither a player nor a queue.
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
    private val transitionGuard: PlaybackTransitionGuard,
) : PlaybackStateManager.Listener, PlaybackMutationInterceptor {
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null
    private var completionJob: Job? = null
    private var driftJob: Job? = null
    private var commandJob: Job? = null
    private var commandQueue: Channel<List<CrewAction>>? = null
    private var attached = false
    @Volatile private var applyingRemote = false
    private var seededSession: String? = null
    private var lastQueueResolutionKey: String? = null
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
        playbackManager.addMutationInterceptor(this)
        val newCommandQueue = Channel<List<CrewAction>>(Channel.UNLIMITED)
        commandQueue = newCommandQueue
        commandJob = newScope.launch { for (actions in newCommandQueue) submitActions(actions) }
        stateJob =
            newScope.launch {
                activeCrewRuntime.state.collect { runtimeState ->
                    val active = runtimeState as? ActiveCrewRuntimeState.Active
                    transitionGuard.setCrewActive(active != null)
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
                merge(temporaryMediaIndex.playable, temporaryMediaIndex.completions).collect {
                    completion ->
                    val active =
                        activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active
                            ?: return@collect
                    val crew = active.presentation.crewState
                    if (
                        crew.sessionId != completion.sessionId ||
                            crew.queue.none { item ->
                                item.id == completion.queueItemId &&
                                    item.track.candidates.any { it.id == completion.candidateId }
                            }
                    ) {
                        return@collect
                    }
                    applyMutex.withLock {
                        applyCrew(active.presentation.role, crew, forceQueueResolution = true)
                    }
                }
            }
        driftJob =
            newScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(DRIFT_RECONCILE_INTERVAL_MS)
                    val active =
                        activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active ?: continue
                    if (active.presentation.crewState.playback.mode != CrewPlaybackMode.PLAYING) {
                        continue
                    }
                    applyMutex.withLock {
                        applyCrew(active.presentation.role, active.presentation.crewState)
                    }
                }
            }
    }

    fun release() {
        if (!attached) return
        attached = false
        transitionGuard.setCrewActive(false)
        playbackManager.removeMutationInterceptor(this)
        playbackManager.removeListener(this)
        stateJob?.cancel()
        completionJob?.cancel()
        driftJob?.cancel()
        commandJob?.cancel()
        commandQueue?.close()
        scope?.cancel()
        stateJob = null
        completionJob = null
        driftJob = null
        commandJob = null
        commandQueue = null
        scope = null
        latestCrew = null
        localCrewMemberId = null
        applyingRemote = false
        seededSession = null
        lastQueueResolutionKey = null
    }

    override fun onIndexMoved(index: Int) {
        if (applyingRemote) return
        val crew = latestCrew ?: return
        // Every device's player can advance when a track ends. Only the coordinator publishes
        // that callback; members wait for the canonical event instead of racing and skipping.
        if (localCrewMemberId != crew.coordinatorMemberId) return
        val itemId = playbackManager.currentQueueItem?.id ?: return
        if (itemId != crew.playback.currentQueueItemId) {
            enqueueActions(selectItemActions(crew, itemId, crewNowMs()))
        }
    }

    override fun onCanonicalQueueChanged(
        queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>,
        index: Int,
        change: org.oxycblt.auxio.playback.state.QueueChange,
    ) = Unit

    override fun onCanonicalQueueReordered(
        queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = Unit

    override fun onCanonicalNewPlayback(
        parent: org.oxycblt.musikr.MusicParent?,
        queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = Unit

    override fun onProgressionChanged(progression: Progression) = Unit

    override fun onPlaybackEnded() {
        if (applyingRemote) return
        val crew = latestCrew ?: return
        if (localCrewMemberId != crew.coordinatorMemberId) return
        val first = crew.queue.firstOrNull()?.id ?: return
        enqueueActions(
            listOf(CrewAction.CurrentItemChanged(first), CrewAction.Pause(0, crewNowMs()))
        )
    }

    override fun onRepeatModeChanged(repeatMode: RepeatMode) = Unit

    override fun intercept(mutation: PlaybackMutation): Boolean {
        if (!attached || applyingRemote) return false
        val active = activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active ?: return false
        val actions =
            actionsForMutation(
                active.presentation.crewState,
                mutation,
                active.presentation.localMemberId,
                crewNowMs(),
            )
        if (actions.isEmpty()) return true
        return enqueueActions(actions)
    }

    private suspend fun applyCrew(
        role: ActiveCrewMode,
        crew: CrewState,
        forceQueueResolution: Boolean = false,
    ) {
        if (
            role == ActiveCrewMode.HOST &&
                crew.queue.isEmpty() &&
                playbackManager.queueItems.isNotEmpty()
        ) {
            val key = crew.sessionId.toString()
            if (seededSession != key) {
                seededSession = key
                val snapshot = playerSnapshot(playbackManager, crewNowMs(), localCrewMemberId)
                enqueueActions(crewDiff(crew, snapshot))
            }
            return
        }
        applyingRemote = true
        try {
            val queueResolutionKey = "${crew.sessionId}:${crew.term}:${crew.lastSequence}"
            if (crew.queue.isEmpty()) {
                lastQueueResolutionKey = queueResolutionKey
                if (playbackManager.queueItems.isNotEmpty()) playbackManager.endSession()
                return
            }
            val targetId = crew.playback.currentQueueItemId
            val playerIds = playbackManager.queueItems.map(QueueItem::id)
            val crewIds = crew.queue.map(QueueItem::id)
            var replacedQueue = false
            if (
                forceQueueResolution ||
                    (playerIds != crewIds && lastQueueResolutionKey != queueResolutionKey)
            ) {
                val policy =
                    ResolutionPolicy(
                        providerPriority =
                            providerSettings
                                .selection(
                                    providerRegistry.supporting(ProviderCapability.STREAM).map {
                                        it.descriptor.id
                                    }
                                )
                                .priority,
                        pushPullEnabled = crewSettings.pushPullEnabled,
                    )
                val prepared =
                    ArrayList<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>(crew.queue.size)
                for (item in crew.queue) {
                    val projected =
                        privateSources.overlay(crew.sessionId, localCrewMemberId ?: return, item)
                    when (
                        val result =
                            resolutionCoordinator.prepare(
                                projected,
                                policy,
                                StreamConstraints(
                                    preferredBitrateBps = providerSettings.streamingBitrateBps()
                                ),
                            )
                    ) {
                        is PlaybackPreparation.Ready -> prepared += result.value
                        is PlaybackPreparation.Failed ->
                            L.w(
                                "Crew item ${item.id} is temporarily unavailable; " +
                                    "installing the remaining playable queue"
                            )
                    }
                }
                lastQueueResolutionKey = queueResolutionKey
                if (prepared.isEmpty()) return
                val selectedId =
                    targetId?.takeIf { target -> prepared.any { it.item.id == target } }
                        ?: prepared.first().item.id
                playbackManager.play(
                    PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                        selectedItemId = selectedId,
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
                if (playbackManager.isShuffled != crew.shuffleEnabled)
                    playbackManager.shuffled(crew.shuffleEnabled)
            }
            val repeat = crew.repeatMode.toPlayerRepeatMode()
            if (playbackManager.repeatMode != repeat) playbackManager.repeatMode(repeat)
            val targetPosition = crewPositionAt(crew.playback, crewNowMs())
            val positionIsAuthoritative =
                crew.playback.mode == CrewPlaybackMode.PLAYING ||
                    crew.playback.mode == CrewPlaybackMode.PAUSED
            if (
                positionIsAuthoritative &&
                    targetId != null &&
                    playbackManager.currentQueueItem?.id == targetId
            ) {
                when (
                    val correction =
                        correctPlaybackDrift(
                            expectedPositionMs = targetPosition,
                            actualPositionMs =
                                playbackManager.progression.calculateElapsedPositionMs(),
                            supportsSpeedCorrection =
                                crew.playback.mode == CrewPlaybackMode.PLAYING,
                            policy = CREW_DRIFT_POLICY,
                        )
                ) {
                    CrewDriftDecision.InSync -> playbackManager.playbackSpeed(1f)
                    is CrewDriftDecision.CorrectSpeed ->
                        playbackManager.playbackSpeed(correction.playbackRate)
                    is CrewDriftDecision.Seek -> {
                        playbackManager.playbackSpeed(1f)
                        playbackManager.seekTo(correction.positionMs)
                    }
                }
            }
            when (crew.playback.mode) {
                CrewPlaybackMode.PLAYING -> {
                    val waitMs = localDelayUntil(crew.playback.sessionEpochMs)
                    if (waitMs > 0) {
                        kotlinx.coroutines.delay(waitMs)
                        val current =
                            (activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active)
                                ?.presentation
                                ?.crewState
                        if (
                            current?.sessionId != crew.sessionId ||
                                current.term != crew.term ||
                                current.lastSequence != crew.lastSequence ||
                                current.playback.mode != CrewPlaybackMode.PLAYING
                        ) {
                            return
                        }
                    }
                    if (!playbackManager.progression.isPlaying) playbackManager.playing(true)
                }
                CrewPlaybackMode.PAUSED ->
                    if (playbackManager.progression.isPlaying) {
                        playbackManager.playbackSpeed(1f)
                        playbackManager.playing(false)
                    }
                CrewPlaybackMode.ENDED ->
                    if (playbackManager.progression.isPlaying) {
                        playbackManager.playbackSpeed(1f)
                        playbackManager.playing(false)
                    }
                CrewPlaybackMode.IDLE,
                CrewPlaybackMode.PREPARING,
                CrewPlaybackMode.BUFFERING -> {
                    playbackManager.playbackSpeed(1f)
                    // newPlayback() starts ExoPlayer immediately. A queue event
                    // in PREPARING must not audibly race the later scheduled Play.
                    if (replacedQueue && playbackManager.progression.isPlaying) {
                        playbackManager.playing(false)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keep the service alive, but retain a diagnosable failure instead of silently
            // pretending the remote state was applied.
            L.e(error, "Could not apply authoritative Crew playback state")
        } finally {
            applyingRemote = false
        }
    }

    private fun enqueueActions(actions: List<CrewAction>): Boolean {
        if (actions.isEmpty()) return true
        val accepted = commandQueue?.trySend(actions)?.isSuccess == true
        if (!accepted) L.e("Crew command queue unavailable; leaving local player unchanged")
        return accepted
    }

    private suspend fun submitActions(actions: List<CrewAction>) {
        for (action in actions) {
            val accepted =
                activeCrewRuntime.submit(action) as? ActiveCrewSubmitResult.Accepted
                    ?: run {
                        L.w("Crew command was not submitted because the active session changed")
                        return
                    }
            when (accepted.result) {
                is CrewSubmitResult.Submitted,
                is CrewSubmitResult.AlreadyPending -> Unit
                CrewSubmitResult.CapacityReached,
                is CrewSubmitResult.Rejected -> {
                    L.w("Crew command was rejected by the active session")
                    return
                }
            }
        }
    }

    /** Current time in the active coordinator's epoch domain. */
    private fun crewNowMs(): Long {
        val localNow = System.currentTimeMillis().coerceAtLeast(0)
        val estimate =
            (activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active)
                ?.presentation
                ?.clockEstimate
                ?.value
        return estimate?.clientToCoordinator(localNow)?.coerceAtLeast(0) ?: localNow
    }

    /** Converts one coordinator epoch to a delay on this device's clock. */
    private fun localDelayUntil(coordinatorEpochMs: Long): Long {
        val localNow = System.currentTimeMillis().coerceAtLeast(0)
        val estimate =
            (activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active)
                ?.presentation
                ?.clockEstimate
                ?.value
        val localTarget = estimate?.coordinatorToClient(coordinatorEpochMs) ?: coordinatorEpochMs
        return (localTarget - localNow).coerceAtLeast(0)
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

/**
 * Converts one explicit local playback request into ordered Crew mutations. The local player is not
 * touched until these actions return through the canonical Crew state.
 */
internal fun actionsForMutation(
    crew: CrewState,
    mutation: PlaybackMutation,
    localMemberId: CrewMemberId?,
    nowMs: Long,
): List<CrewAction> =
    when (mutation) {
        is PlaybackMutation.Start -> {
            val queue =
                stampCrewContributor(
                    canonicalQueueForCrew(mutation.command.queue.map { it.item }),
                    localMemberId,
                )
            val selected =
                mutation.command.selectedItemId?.takeIf { selectedId ->
                    queue.any { it.id == selectedId }
                } ?: queue.firstOrNull()?.id
            if (selected == null) {
                emptyList()
            } else {
                buildList {
                    add(CrewAction.QueueReplaced(queue))
                    add(CrewAction.CurrentItemChanged(selected))
                    if (crew.shuffleEnabled != mutation.command.shuffled) {
                        add(CrewAction.ShuffleChanged(mutation.command.shuffled))
                    }
                    add(
                        CrewAction.Play(
                            positionAtEpochMs = 0,
                            sessionEpochMs = nowMs + CREW_START_LEAD_MS,
                        )
                    )
                }
            }
        }
        PlaybackMutation.Next ->
            adjacentItemId(crew, forward = true)?.let { selected ->
                selectItemActions(crew, selected, nowMs)
            } ?: emptyList()
        PlaybackMutation.Previous ->
            adjacentItemId(crew, forward = false)?.let { selected ->
                selectItemActions(crew, selected, nowMs)
            } ?: emptyList()
        is PlaybackMutation.GoTo ->
            if (
                mutation.itemId == crew.playback.currentQueueItemId ||
                    crew.queue.none { it.id == mutation.itemId }
            ) {
                emptyList()
            } else {
                selectItemActions(crew, mutation.itemId, nowMs)
            }
        is PlaybackMutation.PlayNext -> {
            val items =
                stampCrewContributor(
                    canonicalQueueForCrew(mutation.items.map { it.item }),
                    localMemberId,
                )
            val insertionIndex =
                (crew.queue.indexOfFirst { it.id == crew.playback.currentQueueItemId } + 1)
                    .coerceIn(0, crew.queue.size)
            val beforeItemId = crew.queue.getOrNull(insertionIndex)?.id
            buildList {
                items.forEachIndexed { offset, item ->
                    add(
                        CrewAction.QueueItemInserted(
                            item = item,
                            index = insertionIndex + offset,
                            beforeItemId = beforeItemId,
                            afterItemId =
                                if (beforeItemId == null) {
                                    items.getOrNull(offset - 1)?.id ?: crew.queue.lastOrNull()?.id
                                } else null,
                        )
                    )
                }
                if (crew.queue.isEmpty() && items.isNotEmpty()) {
                    add(CrewAction.Play(0, nowMs + CREW_START_LEAD_MS))
                }
            }
        }
        is PlaybackMutation.AddToQueue -> {
            val items =
                stampCrewContributor(
                    canonicalQueueForCrew(mutation.items.map { it.item }),
                    localMemberId,
                )
            buildList {
                items.forEachIndexed { offset, item ->
                    add(
                        CrewAction.QueueItemInserted(
                            item = item,
                            index = crew.queue.size + offset,
                            afterItemId =
                                items.getOrNull(offset - 1)?.id ?: crew.queue.lastOrNull()?.id,
                        )
                    )
                }
                if (crew.queue.isEmpty() && items.isNotEmpty()) {
                    add(CrewAction.Play(0, nowMs + CREW_START_LEAD_MS))
                }
            }
        }
        is PlaybackMutation.MoveQueueItem -> {
            val withoutMoved = crew.queue.filterNot { it.id == mutation.itemId }
            val destination =
                mutation.beforeId?.let { beforeId ->
                    withoutMoved.indexOfFirst { it.id == beforeId }.takeIf { it >= 0 }
                }
                    ?: mutation.afterId?.let { afterId ->
                        withoutMoved.indexOfFirst { it.id == afterId }.takeIf { it >= 0 }?.plus(1)
                    }
            if (
                destination == null ||
                    crew.queue.none { it.id == mutation.itemId } ||
                    destination !in 0..withoutMoved.size
            ) {
                emptyList()
            } else {
                listOf(
                    CrewAction.QueueItemMoved(
                        itemId = mutation.itemId,
                        newIndex = destination,
                        beforeItemId = mutation.beforeId,
                        afterItemId = if (mutation.beforeId == null) mutation.afterId else null,
                    )
                )
            }
        }
        is PlaybackMutation.RemoveQueueItem ->
            if (crew.queue.any { it.id == mutation.itemId }) {
                buildList {
                    add(CrewAction.QueueItemRemoved(mutation.itemId))
                    if (
                        mutation.itemId == crew.playback.currentQueueItemId && crew.queue.size > 1
                    ) {
                        add(playbackModeAction(crew, positionMs = 0, nowMs = nowMs))
                    }
                }
            } else {
                emptyList()
            }
        is PlaybackMutation.SetShuffled ->
            if (crew.shuffleEnabled == mutation.enabled) emptyList()
            else listOf(CrewAction.ShuffleChanged(mutation.enabled))
        is PlaybackMutation.SetPlaying -> {
            val position = crewPlaybackPositionForCommand(crew, nowMs)
            if (mutation.playing) {
                listOf(CrewAction.Play(position, nowMs + CREW_START_LEAD_MS))
            } else listOf(CrewAction.Pause(position, nowMs))
        }
        is PlaybackMutation.SetRepeatMode -> {
            val mode = mutation.repeatMode.toCrewRepeatMode()
            if (crew.repeatMode == mode) emptyList() else listOf(CrewAction.RepeatChanged(mode))
        }
        is PlaybackMutation.SeekTo -> listOf(CrewAction.Seek(mutation.positionMs, nowMs))
    }

private fun adjacentItemId(crew: CrewState, forward: Boolean): QueueItemId? {
    if (crew.queue.isEmpty()) return null
    val currentIndex = crew.queue.indexOfFirst { it.id == crew.playback.currentQueueItemId }
    if (currentIndex < 0) return crew.queue.first().id
    val nextIndex =
        if (forward) (currentIndex + 1) % crew.queue.size
        else (currentIndex - 1 + crew.queue.size) % crew.queue.size
    return crew.queue[nextIndex].id
}

private fun selectItemActions(
    crew: CrewState,
    selected: QueueItemId,
    nowMs: Long,
): List<CrewAction> =
    listOf(
        CrewAction.CurrentItemChanged(selected),
        playbackModeAction(crew, positionMs = 0, nowMs = nowMs),
    )

private fun playbackModeAction(crew: CrewState, positionMs: Long, nowMs: Long): CrewAction =
    if (crew.playback.mode == CrewPlaybackMode.PLAYING) {
        CrewAction.Play(positionMs, nowMs + CREW_START_LEAD_MS)
    } else CrewAction.Pause(positionMs, nowMs)

private fun crewPlaybackPositionForCommand(crew: CrewState, nowMs: Long): Long =
    crewPositionAt(crew.playback, nowMs).coerceAtLeast(0)

/** Removes device-only playback material before a queue crosses into the Crew protocol. */
internal fun canonicalQueueForCrew(items: List<QueueItem>): List<QueueItem> =
    items.map { item ->
        // Keep LOCAL for this first top-down slice: current supplier authorization relies on it.
        // Private local-locator projection is a follow-up refinement.
        item.copy(
            track =
                item.track.copy(
                    candidates =
                        item.track.candidates.filterNot {
                            it.kind == CandidateKind.CREW_TEMPORARY ||
                                it.kind == CandidateKind.DOWNLOAD
                        }
                )
        )
    }

/** Stamps only local introductions; preserved contributor IDs remain remote provenance. */
internal fun stampCrewContributor(
    items: List<QueueItem>,
    localMemberId: CrewMemberId?,
): List<QueueItem> =
    if (localMemberId == null) items
    else
        items.map { item ->
            if (item.contributorId == null) {
                item.copy(contributorId = localMemberId.value)
            } else item
        }

internal fun crewPositionAt(
    playback: org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState,
    nowMs: Long,
): Long {
    if (playback.mode != CrewPlaybackMode.PLAYING)
        return playback.positionAtEpochMs.coerceAtLeast(0)
    val elapsed = (nowMs - playback.sessionEpochMs).coerceAtLeast(0)
    return if (Long.MAX_VALUE - playback.positionAtEpochMs < elapsed) Long.MAX_VALUE
    else playback.positionAtEpochMs + elapsed
}

private fun playerSnapshot(
    manager: PlaybackStateManager,
    nowMs: Long,
    localMemberId: CrewMemberId?,
) =
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
    val queueChanged =
        publicizeCrewQueue(canonicalQueueForCrew(crew.queue)) != publicizeCrewQueue(player.queue)
    if (queueChanged) actions += CrewAction.QueueReplaced(player.queue)
    if (player.currentItemId != null && player.currentItemId != crew.playback.currentQueueItemId)
        actions += CrewAction.CurrentItemChanged(player.currentItemId)
    if (player.shuffled != crew.shuffleEnabled)
        actions += CrewAction.ShuffleChanged(player.shuffled)
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
        actions +=
            if (player.playing) CrewAction.Play(player.positionMs, player.sessionEpochMs)
            else CrewAction.Pause(player.positionMs, player.sessionEpochMs)
    } else if (
        crewHasPlaybackIntent &&
            kotlin.math.abs(player.positionMs - crewPosition) >
                CREW_DRIFT_POLICY.speedCorrectionLimitMs
    ) {
        actions += CrewAction.Seek(player.positionMs, player.sessionEpochMs)
    }
    return actions
}

private fun CrewRepeatMode.toPlayerRepeatMode() =
    when (this) {
        CrewRepeatMode.OFF -> RepeatMode.NONE
        CrewRepeatMode.ALL -> RepeatMode.ALL
        CrewRepeatMode.ONE -> RepeatMode.TRACK
    }

private fun RepeatMode.toCrewRepeatMode() =
    when (this) {
        RepeatMode.NONE -> CrewRepeatMode.OFF
        RepeatMode.ALL -> CrewRepeatMode.ALL
        RepeatMode.TRACK -> CrewRepeatMode.ONE
    }
