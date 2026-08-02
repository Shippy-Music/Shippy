/*
 * Copyright (c) 2026 Auxio Project
 * ActiveCrewRuntime.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.media.CrewPrivateSourceRegistry
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinRestoreDecision
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinRestorePolicy
import org.oxycblt.auxio.shippy.crew.session.CrewReactionSendResult
import org.oxycblt.auxio.shippy.crew.session.CrewSessionNotice
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.crew.settings.CrewProfileSettings
import org.oxycblt.auxio.shippy.crew.settings.profileUpdateAction
import org.oxycblt.auxio.shippy.crew.sync.CrewClockEstimate
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointLoadResult
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore

enum class ActiveCrewMode {
    HOST,
    JOIN,
}

/** One ordered, attributable action from the current Crew. Never persisted across sessions. */
data class ActiveCrewActivity(val issuingMemberId: CrewMemberId, val action: CrewAction)

sealed interface ActiveCrewRequestResult {
    data object Accepted : ActiveCrewRequestResult

    data object Busy : ActiveCrewRequestResult

    data object NothingToEnd : ActiveCrewRequestResult
}

sealed interface ActiveCrewSubmitResult {
    data class Accepted(val result: CrewSubmitResult) : ActiveCrewSubmitResult

    data object NotActive : ActiveCrewSubmitResult

    data object SessionChanged : ActiveCrewSubmitResult

    data object Failed : ActiveCrewSubmitResult
}

sealed interface ActiveCrewReactionSendResult {
    data class Accepted(val reaction: ActiveCrewReaction) : ActiveCrewReactionSendResult

    data object Rejected : ActiveCrewReactionSendResult

    data object NotActive : ActiveCrewReactionSendResult

    data object SessionChanged : ActiveCrewReactionSendResult

    data object Failed : ActiveCrewReactionSendResult
}

sealed interface ActiveCrewRuntimeFailure {
    class Host(val launchFailure: CrewLanHostLaunchFailure) : ActiveCrewRuntimeFailure {
        override fun toString() = "ActiveCrewRuntimeFailure.Host(redacted)"
    }

    class Join(val launchFailure: CrewLanJoinLaunchFailure) : ActiveCrewRuntimeFailure {
        override fun toString() = "ActiveCrewRuntimeFailure.Join(redacted)"
    }

    data object BlankInviteLink : ActiveCrewRuntimeFailure

    data object Internal : ActiveCrewRuntimeFailure
}

/** The safe, UI-facing view of the one Crew currently owned by this process. */
class ActiveCrewPresentation
internal constructor(
    val role: ActiveCrewMode,
    val sessionId: CrewSessionId,
    val localMemberId: CrewMemberId,
    val crewState: CrewState,
    val inviteLink: String?,
    val connectivity: CrewConnectivityPresentation,
    val reconnectState: CrewJoinReconnectState,
    val clockEstimate: StateFlow<CrewClockEstimate?>,
) {
    override fun toString() =
        "ActiveCrewPresentation(role=$role, sessionId=redacted, localMemberId=redacted, " +
            "crewState=redacted, inviteLink=${if (inviteLink == null) "none" else "redacted"})"
}

sealed interface ActiveCrewRuntimeState {
    data object Idle : ActiveCrewRuntimeState

    data class Starting(val mode: ActiveCrewMode) : ActiveCrewRuntimeState

    data class Active(val presentation: ActiveCrewPresentation) : ActiveCrewRuntimeState

    data class Ending(val presentation: ActiveCrewPresentation) : ActiveCrewRuntimeState

    data class Failed(val failure: ActiveCrewRuntimeFailure) : ActiveCrewRuntimeState
}

/**
 * Application-wide owner for the one live LAN Crew. It owns launch, state observation, and explicit
 * teardown so a fragment or ViewModel cannot orphan a session.
 */
@Singleton
class ActiveCrewRuntime
@Inject
constructor(
    @ApplicationContext context: Context,
    private val hostLauncher: CrewLanHostLauncher,
    private val joinLauncher: CrewLanJoinLauncher,
    private val privateSources: CrewPrivateSourceRegistry,
    private val profileSettings: CrewProfileSettings,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<ActiveCrewRuntimeState>(ActiveCrewRuntimeState.Idle)
    private val mutableReactions = MutableSharedFlow<ActiveCrewReaction>(extraBufferCapacity = 32)
    private val mutableActivity = MutableStateFlow<List<ActiveCrewActivity>>(emptyList())
    private val mutablePeerMediaBlocked = MutableStateFlow(false)
    private var generation = 0L
    private var ownedSession: OwnedSession? = null
    private var presentationJob: Job? = null
    private var reconnectStateJob: Job? = null
    private var reactionJob: Job? = null
    private var activityJob: Job? = null
    private var peerMediaBlockedJob: Job? = null
    private var recoveryAttemptInFlight = false
    private var pendingRecoverySessionId: CrewSessionId? = null
    private val recoveryNetworkMonitor =
        CrewNetworkChangeMonitor(context.applicationContext) {
            scope.launch { restorePersistedSession(fromNetworkChange = true) }
        }

    val state: StateFlow<ActiveCrewRuntimeState> = mutableState.asStateFlow()
    val reactions: SharedFlow<ActiveCrewReaction> = mutableReactions
    val activity: StateFlow<List<ActiveCrewActivity>> = mutableActivity.asStateFlow()
    val peerMediaBlocked: StateFlow<Boolean> = mutablePeerMediaBlocked.asStateFlow()
    val allowedReactions: List<String>
        get() = synchronized(lock) { ownedSession?.allowedReactions ?: emptyList() }

    private val profileListener =
        object : CrewProfileSettings.Listener {
            override fun onDisplayNameChanged(displayName: String) {
                publishProfileUpdate()
            }

            override fun onAvatarChanged(
                avatar: org.oxycblt.auxio.shippy.crew.core.CrewAvatarDescriptor
            ) {
                publishProfileUpdate()
            }

            private fun publishProfileUpdate() {
                val action =
                    synchronized(lock) {
                        val active = mutableState.value as? ActiveCrewRuntimeState.Active
                        active?.presentation?.let { presentation ->
                            profileUpdateAction(
                                presentation.crewState,
                                presentation.localMemberId,
                                profileSettings.member(presentation.localMemberId.protocolVersion),
                            )
                        }
                    } ?: return
                scope.launch { submit(action) }
            }
        }

    init {
        profileSettings.registerListener(profileListener)
        scope.launch { restorePersistedSession(fromNetworkChange = false) }
    }

    fun startHost(): ActiveCrewRequestResult {
        abandonPendingRecovery()
        return begin(ActiveCrewMode.HOST) { generation -> launchHost(generation) }
    }

    fun join(inviteLink: String): ActiveCrewRequestResult {
        abandonPendingRecovery()
        val requestGeneration =
            synchronized(lock) {
                if (!mutableState.value.isStartable()) return ActiveCrewRequestResult.Busy
                generation += 1
                if (inviteLink.isBlank()) {
                    mutableState.value =
                        ActiveCrewRuntimeState.Failed(ActiveCrewRuntimeFailure.BlankInviteLink)
                    return ActiveCrewRequestResult.Accepted
                }
                mutableState.value = ActiveCrewRuntimeState.Starting(ActiveCrewMode.JOIN)
                generation
            }
        launch(requestGeneration) { launchJoin(requestGeneration, inviteLink) }
        return ActiveCrewRequestResult.Accepted
    }

    fun end(): ActiveCrewRequestResult {
        val ending =
            synchronized(lock) {
                val active =
                    mutableState.value as? ActiveCrewRuntimeState.Active
                        ?: return when (mutableState.value) {
                            ActiveCrewRuntimeState.Idle,
                            is ActiveCrewRuntimeState.Failed -> ActiveCrewRequestResult.NothingToEnd
                            else -> ActiveCrewRequestResult.Busy
                        }
                val session = ownedSession ?: return ActiveCrewRequestResult.Busy
                generation += 1
                val endGeneration = generation
                mutableState.value = ActiveCrewRuntimeState.Ending(active.presentation)
                EndRequest(endGeneration, session)
            }
        launch(ending.generation) { endOwnedSession(ending) }
        return ActiveCrewRequestResult.Accepted
    }

    suspend fun submit(action: CrewAction): ActiveCrewSubmitResult {
        val submission =
            synchronized(lock) {
                if (mutableState.value !is ActiveCrewRuntimeState.Active)
                    return ActiveCrewSubmitResult.NotActive
                val session = ownedSession ?: return ActiveCrewSubmitResult.NotActive
                SubmitRequest(generation, session)
            }
        val result =
            try {
                submission.session.submit(
                    privateSources.captureAndPublicize(
                        submission.session.sessionId,
                        submission.session.localMemberId,
                        action,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return synchronized(lock) {
                    if (
                        generation != submission.generation || ownedSession !== submission.session
                    ) {
                        ActiveCrewSubmitResult.SessionChanged
                    } else {
                        ActiveCrewSubmitResult.Failed
                    }
                }
            }
        return synchronized(lock) {
            if (generation != submission.generation || ownedSession !== submission.session) {
                ActiveCrewSubmitResult.SessionChanged
            } else {
                ActiveCrewSubmitResult.Accepted(result)
            }
        }
    }

    suspend fun sendReaction(emoji: String): ActiveCrewReactionSendResult {
        val request =
            synchronized(lock) {
                if (mutableState.value !is ActiveCrewRuntimeState.Active)
                    return ActiveCrewReactionSendResult.NotActive
                val session = ownedSession ?: return ActiveCrewReactionSendResult.NotActive
                SubmitRequest(generation, session)
            }
        val result =
            try {
                request.session.sendReaction(emoji)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return synchronized(lock) {
                    if (generation != request.generation || ownedSession !== request.session) {
                        ActiveCrewReactionSendResult.SessionChanged
                    } else ActiveCrewReactionSendResult.Failed
                }
            }
        return synchronized(lock) {
            if (generation != request.generation || ownedSession !== request.session) {
                ActiveCrewReactionSendResult.SessionChanged
            } else
                when (result) {
                    is CrewReactionSendResult.Accepted ->
                        ActiveCrewReactionSendResult.Accepted(result.reaction)
                    CrewReactionSendResult.Rejected -> ActiveCrewReactionSendResult.Rejected
                }
        }
    }

    fun dismissFailure() {
        synchronized(lock) {
            if (mutableState.value is ActiveCrewRuntimeState.Failed) {
                mutableState.value = ActiveCrewRuntimeState.Idle
            }
        }
    }

    private fun begin(mode: ActiveCrewMode, work: suspend (Long) -> Unit): ActiveCrewRequestResult {
        val requestGeneration =
            synchronized(lock) {
                if (!mutableState.value.isStartable()) return ActiveCrewRequestResult.Busy
                generation += 1
                mutableState.value = ActiveCrewRuntimeState.Starting(mode)
                generation
            }
        launch(requestGeneration) { work(requestGeneration) }
        return ActiveCrewRequestResult.Accepted
    }

    private fun launch(generation: Long, work: suspend () -> Unit) {
        try {
            scope.launch {
                try {
                    work()
                } catch (_: Throwable) {
                    publishFailure(generation, ActiveCrewRuntimeFailure.Internal)
                }
            }
        } catch (_: Throwable) {
            publishFailure(generation, ActiveCrewRuntimeFailure.Internal)
        }
    }

    private suspend fun launchHost(generation: Long) {
        when (val result = hostLauncher.start()) {
            is CrewLanHostLaunchResult.Failed ->
                publishFailure(generation, ActiveCrewRuntimeFailure.Host(result.reason))
            is CrewLanHostLaunchResult.Started ->
                activate(generation, OwnedSession.Host(result.session))
        }
    }

    private suspend fun launchJoin(generation: Long, inviteLink: String) {
        when (val result = joinLauncher.join(inviteLink)) {
            is CrewLanJoinLaunchResult.Failed ->
                publishFailure(generation, ActiveCrewRuntimeFailure.Join(result.reason))
            is CrewLanJoinLaunchResult.Started ->
                activate(generation, OwnedSession.Join(result.session))
        }
    }

    /**
     * Process-start recovery deliberately reuses the production authenticated join runtime. A
     * retryable route failure retains both the Room checkpoint and Keystore lease; a later default
     * network callback retries the same membership instead of fabricating a new Crew.
     */
    private suspend fun restorePersistedSession(fromNetworkChange: Boolean) {
        val attemptGeneration =
            synchronized(lock) {
                if (recoveryAttemptInFlight) return
                if (fromNetworkChange && pendingRecoverySessionId == null) return
                if (!mutableState.value.isStartable()) return
                recoveryAttemptInFlight = true
                generation += 1
                mutableState.value = ActiveCrewRuntimeState.Starting(ActiveCrewMode.JOIN)
                generation
            }
        try {
            val checkpoint =
                (checkpoints.load() as? CrewCheckpointLoadResult.Loaded)?.checkpoint?.snapshot
            val lease = leases.load()
            val decision =
                CrewRejoinRestorePolicy.decide(checkpoint, lease, System.currentTimeMillis())
            if (decision != CrewRejoinRestoreDecision.RESTORE) {
                checkpoint?.sessionId?.let { checkpoints.clear(it) }
                lease?.sessionId?.let { leases.clear(it) }
                synchronized(lock) {
                    if (generation == attemptGeneration) {
                        pendingRecoverySessionId = null
                        mutableState.value = ActiveCrewRuntimeState.Idle
                    }
                }
                return
            }
            val validLease = checkNotNull(lease)
            synchronized(lock) {
                if (generation == attemptGeneration) {
                    pendingRecoverySessionId = validLease.sessionId
                }
            }
            when (val result = joinLauncher.rejoin(validLease)) {
                is CrewLanJoinLaunchResult.Started -> {
                    synchronized(lock) {
                        if (generation == attemptGeneration) pendingRecoverySessionId = null
                    }
                    activate(attemptGeneration, OwnedSession.Join(result.session))
                }
                is CrewLanJoinLaunchResult.Failed -> {
                    val revoked =
                        result.reason is CrewLanJoinLaunchFailure.InviteRejected ||
                            result.reason is CrewLanJoinLaunchFailure.JoinRejected
                    if (revoked) {
                        checkpoints.clear(validLease.sessionId)
                        leases.clear(validLease.sessionId)
                    }
                    synchronized(lock) {
                        if (generation == attemptGeneration) {
                            if (revoked) pendingRecoverySessionId = null
                            mutableState.value =
                                ActiveCrewRuntimeState.Failed(
                                    ActiveCrewRuntimeFailure.Join(result.reason)
                                )
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            publishFailure(attemptGeneration, ActiveCrewRuntimeFailure.Internal)
        } finally {
            synchronized(lock) { recoveryAttemptInFlight = false }
        }
    }

    private fun abandonPendingRecovery() {
        val sessionId =
            synchronized(lock) { pendingRecoverySessionId.also { pendingRecoverySessionId = null } }
        if (sessionId != null) {
            scope.launch {
                runCatching { checkpoints.clear(sessionId) }
                runCatching { leases.clear(sessionId) }
            }
        }
    }

    private suspend fun activate(generation: Long, session: OwnedSession) {
        val claimed =
            synchronized(lock) {
                if (
                    this.generation != generation ||
                        mutableState.value !is ActiveCrewRuntimeState.Starting
                ) {
                    false
                } else {
                    ownedSession = session
                    true
                }
            }
        if (!claimed) {
            session.endExplicitly()
            return
        }
        val nextPresentationJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.state.collect { crewState ->
                    privateSources.prune(session.sessionId, crewState.queue)
                    refreshPresentation(generation, session, crewState)
                }
            }
        val nextReactionJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.reactions.collect { reaction ->
                    synchronized(lock) {
                        if (
                            this@ActiveCrewRuntime.generation == generation &&
                                ownedSession === session
                        ) {
                            mutableReactions.tryEmit(reaction)
                        }
                    }
                }
            }
        val nextActivityJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.notices.collect { notice ->
                    val advanced = notice as? CrewSessionNotice.StateAdvanced ?: return@collect
                    if (!advanced.event.action.isMeaningfulActivity()) return@collect
                    synchronized(lock) {
                        if (
                            this@ActiveCrewRuntime.generation == generation &&
                                ownedSession === session
                        ) {
                            mutableActivity.value =
                                (listOf(
                                        ActiveCrewActivity(
                                            advanced.event.issuingMemberId,
                                            advanced.event.action,
                                        )
                                    ) + mutableActivity.value)
                                    .take(MAX_RECENT_ACTIVITY)
                        }
                    }
                }
            }
        val nextReconnectStateJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.reconnectState.collect {
                    refreshPresentation(generation, session, session.state.value)
                }
            }
        val nextPeerMediaBlockedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.peerMediaBlocked.collect { blocked ->
                    synchronized(lock) {
                        if (
                            this@ActiveCrewRuntime.generation == generation &&
                                ownedSession === session
                        ) {
                            mutablePeerMediaBlocked.value = blocked
                        }
                    }
                }
            }
        val activated =
            synchronized(lock) {
                if (
                    this.generation != generation ||
                        mutableState.value !is ActiveCrewRuntimeState.Starting ||
                        ownedSession !== session
                ) {
                    false
                } else {
                    presentationJob?.cancel()
                    reconnectStateJob?.cancel()
                    reactionJob?.cancel()
                    activityJob?.cancel()
                    peerMediaBlockedJob?.cancel()
                    presentationJob = nextPresentationJob
                    reconnectStateJob = nextReconnectStateJob
                    reactionJob = nextReactionJob
                    activityJob = nextActivityJob
                    peerMediaBlockedJob = nextPeerMediaBlockedJob
                    mutablePeerMediaBlocked.value = false
                    mutableActivity.value = emptyList()
                    nextPresentationJob.start()
                    nextReconnectStateJob.start()
                    nextReactionJob.start()
                    nextActivityJob.start()
                    nextPeerMediaBlockedJob.start()
                    mutableState.value =
                        ActiveCrewRuntimeState.Active(session.presentation(session.state.value))
                    true
                }
            }
        if (!activated) {
            nextPresentationJob.cancel()
            nextReconnectStateJob.cancel()
            nextReactionJob.cancel()
            nextActivityJob.cancel()
            nextPeerMediaBlockedJob.cancel()
            session.endExplicitly()
        } else {
            // Join transports may bootstrap us from a minimal discovery profile. Publish the
            // canonical bounded profile once the active session can sequence it.
            profileUpdateAction(
                    state = session.state.value,
                    localMemberId = session.localMemberId,
                    profile = profileSettings.member(session.localMemberId.protocolVersion),
                )
                ?.let { action -> scope.launch { submit(action) } }
        }
    }

    private fun refreshPresentation(generation: Long, session: OwnedSession, crewState: CrewState) {
        val terminalRequest: EndRequest? =
            synchronized(lock) {
                if (this.generation == generation && ownedSession === session) {
                    val active =
                        mutableState.value as? ActiveCrewRuntimeState.Active
                            ?: return@synchronized null
                    val presentation = session.presentation(crewState)
                    if (crewState.playback.mode == CrewPlaybackMode.ENDED) {
                        this.generation += 1
                        mutableState.value = ActiveCrewRuntimeState.Ending(presentation)
                        EndRequest(this.generation, session)
                    } else {
                        mutableState.value = active.copy(presentation = presentation)
                        null
                    }
                } else {
                    null
                }
            }
        terminalRequest?.let { request -> launch(request.generation) { endOwnedSession(request) } }
    }

    private fun publishFailure(generation: Long, failure: ActiveCrewRuntimeFailure) {
        synchronized(lock) {
            if (
                this.generation == generation &&
                    mutableState.value is ActiveCrewRuntimeState.Starting
            ) {
                mutableState.value = ActiveCrewRuntimeState.Failed(failure)
            }
        }
    }

    private suspend fun endOwnedSession(request: EndRequest) {
        try {
            request.session.endExplicitly()
        } finally {
            synchronized(lock) {
                if (generation == request.generation && ownedSession === request.session) {
                    presentationJob?.cancel()
                    reconnectStateJob?.cancel()
                    reactionJob?.cancel()
                    activityJob?.cancel()
                    peerMediaBlockedJob?.cancel()
                    presentationJob = null
                    reconnectStateJob = null
                    reactionJob = null
                    activityJob = null
                    peerMediaBlockedJob = null
                    mutablePeerMediaBlocked.value = false
                    mutableActivity.value = emptyList()
                    ownedSession = null
                    mutableState.value = ActiveCrewRuntimeState.Idle
                }
            }
        }
    }

    private fun ActiveCrewRuntimeState.isStartable() =
        this == ActiveCrewRuntimeState.Idle || this is ActiveCrewRuntimeState.Failed

    private data class EndRequest(val generation: Long, val session: OwnedSession)

    private data class SubmitRequest(val generation: Long, val session: OwnedSession)

    private sealed interface OwnedSession {
        val role: ActiveCrewMode
        val sessionId: CrewSessionId
        val localMemberId: CrewMemberId
        val state: StateFlow<CrewState>
        val inviteLink: String?
        val connectivity: CrewConnectivityPresentation
        val reconnectState: StateFlow<CrewJoinReconnectState>
        val clockEstimate: StateFlow<CrewClockEstimate?>
        val notices: SharedFlow<CrewSessionNotice>
        val reactions: SharedFlow<ActiveCrewReaction>
        val peerMediaBlocked: StateFlow<Boolean>
        val allowedReactions: List<String>

        suspend fun endExplicitly()

        suspend fun submit(action: CrewAction): CrewSubmitResult

        suspend fun sendReaction(emoji: String): CrewReactionSendResult

        fun presentation(crewState: CrewState) =
            ActiveCrewPresentation(
                role,
                sessionId,
                localMemberId,
                crewState,
                inviteLink,
                connectivity,
                reconnectState.value,
                clockEstimate,
            )

        class Host(private val session: CrewLanHostSession) : OwnedSession {
            override val role = ActiveCrewMode.HOST
            override val sessionId = session.sessionId
            override val localMemberId = session.localMemberId
            override val state: StateFlow<CrewState> = session.state
            override val inviteLink = session.inviteLink
            override val connectivity = session.connectivity
            override val reconnectState =
                MutableStateFlow<CrewJoinReconnectState>(CrewJoinReconnectState.Connected)
            override val clockEstimate = session.clockEstimate
            override val notices = session.notices
            override val reactions = session.reactions
            override val peerMediaBlocked = session.peerMediaBlocked
            override val allowedReactions = session.allowedReactions

            override suspend fun endExplicitly() = session.end()

            override suspend fun submit(action: CrewAction) = session.submit(action)

            override suspend fun sendReaction(emoji: String) = session.sendReaction(emoji)
        }

        class Join(private val session: CrewLanJoinedSession) : OwnedSession {
            override val role = ActiveCrewMode.JOIN
            override val sessionId = session.sessionId
            override val localMemberId = session.localMemberId
            override val state: StateFlow<CrewState> = session.state
            override val inviteLink: String? = null
            override val connectivity = session.connectivity
            override val reconnectState = session.reconnectState
            override val clockEstimate = session.clockEstimate
            override val notices = session.notices
            override val reactions = session.reactions
            override val peerMediaBlocked = session.peerMediaBlocked
            override val allowedReactions = session.allowedReactions

            override suspend fun endExplicitly() = session.leave()

            override suspend fun submit(action: CrewAction) = session.submit(action)

            override suspend fun sendReaction(emoji: String) = session.sendReaction(emoji)
        }
    }

    private fun CrewAction.isMeaningfulActivity() =
        when (this) {
            is CrewAction.MemberUpdated,
            is CrewAction.Seek -> false
            else -> true
        }

    private companion object {
        const val MAX_RECENT_ACTIVITY = 5
    }
}
