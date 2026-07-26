/*
 * Copyright (c) 2026 Shippy contributors
 * ActiveCrewRuntime.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.crew.session.CrewReactionSendResult
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction

enum class ActiveCrewMode {
    HOST,
    JOIN,
}

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
class ActiveCrewPresentation internal constructor(
    val role: ActiveCrewMode,
    val sessionId: CrewSessionId,
    val localMemberId: CrewMemberId,
    val crewState: CrewState,
    val inviteLink: String?,
    val connectivity: CrewConnectivityPresentation,
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
 * Application-wide owner for the one live LAN Crew. It owns launch, state observation, and
 * explicit teardown so a fragment or ViewModel cannot orphan a session.
 */
@Singleton
class ActiveCrewRuntime
@Inject
constructor(
    private val hostLauncher: CrewLanHostLauncher,
    private val joinLauncher: CrewLanJoinLauncher,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<ActiveCrewRuntimeState>(ActiveCrewRuntimeState.Idle)
    private val mutableReactions = MutableSharedFlow<ActiveCrewReaction>(extraBufferCapacity = 32)
    private val mutablePeerMediaBlocked = MutableStateFlow(false)
    private var generation = 0L
    private var ownedSession: OwnedSession? = null
    private var presentationJob: Job? = null
    private var reactionJob: Job? = null
    private var peerMediaBlockedJob: Job? = null

    val state: StateFlow<ActiveCrewRuntimeState> = mutableState.asStateFlow()
    val reactions: SharedFlow<ActiveCrewReaction> = mutableReactions
    val peerMediaBlocked: StateFlow<Boolean> = mutablePeerMediaBlocked.asStateFlow()
    val allowedReactions: List<String>
        get() = synchronized(lock) { ownedSession?.allowedReactions ?: emptyList() }

    fun startHost(): ActiveCrewRequestResult =
        begin(ActiveCrewMode.HOST) { generation -> launchHost(generation) }

    fun join(inviteLink: String): ActiveCrewRequestResult {
        val generation = synchronized(lock) {
            if (!mutableState.value.isStartable()) return ActiveCrewRequestResult.Busy
            generation += 1
            if (inviteLink.isBlank()) {
                mutableState.value = ActiveCrewRuntimeState.Failed(ActiveCrewRuntimeFailure.BlankInviteLink)
                return ActiveCrewRequestResult.Accepted
            }
            mutableState.value = ActiveCrewRuntimeState.Starting(ActiveCrewMode.JOIN)
            generation
        }
        launch(generation) { launchJoin(generation, inviteLink) }
        return ActiveCrewRequestResult.Accepted
    }

    fun end(): ActiveCrewRequestResult {
        val ending = synchronized(lock) {
            val active = mutableState.value as? ActiveCrewRuntimeState.Active
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
        val submission = synchronized(lock) {
            if (mutableState.value !is ActiveCrewRuntimeState.Active) return ActiveCrewSubmitResult.NotActive
            val session = ownedSession ?: return ActiveCrewSubmitResult.NotActive
            SubmitRequest(generation, session)
        }
        val result =
            try {
                submission.session.submit(action)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return synchronized(lock) {
                    if (generation != submission.generation || ownedSession !== submission.session) {
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
        val request = synchronized(lock) {
            if (mutableState.value !is ActiveCrewRuntimeState.Active) return ActiveCrewReactionSendResult.NotActive
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
            } else when (result) {
                is CrewReactionSendResult.Accepted -> ActiveCrewReactionSendResult.Accepted(result.reaction)
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

    private fun begin(
        mode: ActiveCrewMode,
        work: suspend (Long) -> Unit,
    ): ActiveCrewRequestResult {
        val generation = synchronized(lock) {
            if (!mutableState.value.isStartable()) return ActiveCrewRequestResult.Busy
            generation += 1
            mutableState.value = ActiveCrewRuntimeState.Starting(mode)
            generation
        }
        launch(generation) { work(generation) }
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

    private suspend fun activate(generation: Long, session: OwnedSession) {
        val claimed = synchronized(lock) {
            if (this.generation != generation || mutableState.value !is ActiveCrewRuntimeState.Starting) {
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
                    refreshPresentation(generation, session, crewState)
                }
            }
        val nextReactionJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.reactions.collect { reaction ->
                    synchronized(lock) {
                        if (this.generation == generation && ownedSession === session) {
                            mutableReactions.tryEmit(reaction)
                        }
                    }
                }
            }
        val nextPeerMediaBlockedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                session.peerMediaBlocked.collect { blocked ->
                    synchronized(lock) {
                        if (this.generation == generation && ownedSession === session) {
                            mutablePeerMediaBlocked.value = blocked
                        }
                    }
                }
            }
        val activated = synchronized(lock) {
            if (
                this.generation != generation ||
                    mutableState.value !is ActiveCrewRuntimeState.Starting ||
                    ownedSession !== session
            ) {
                false
            } else {
                presentationJob?.cancel()
                reactionJob?.cancel()
                peerMediaBlockedJob?.cancel()
                presentationJob = nextPresentationJob
                reactionJob = nextReactionJob
                peerMediaBlockedJob = nextPeerMediaBlockedJob
                mutablePeerMediaBlocked.value = false
                nextPresentationJob.start()
                nextReactionJob.start()
                nextPeerMediaBlockedJob.start()
                mutableState.value =
                    ActiveCrewRuntimeState.Active(session.presentation(session.state.value))
                true
            }
        }
        if (!activated) {
            nextPresentationJob.cancel()
            nextReactionJob.cancel()
            nextPeerMediaBlockedJob.cancel()
            session.endExplicitly()
        }
    }

    private fun refreshPresentation(generation: Long, session: OwnedSession, crewState: CrewState) {
        val terminalRequest =
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
        terminalRequest?.let { request ->
            launch(request.generation) { endOwnedSession(request) }
        }
    }

    private fun publishFailure(generation: Long, failure: ActiveCrewRuntimeFailure) {
        synchronized(lock) {
            if (this.generation == generation && mutableState.value is ActiveCrewRuntimeState.Starting) {
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
                    reactionJob?.cancel()
                    peerMediaBlockedJob?.cancel()
                    presentationJob = null
                    reactionJob = null
                    peerMediaBlockedJob = null
                    mutablePeerMediaBlocked.value = false
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
        val reactions: SharedFlow<ActiveCrewReaction>
        val peerMediaBlocked: StateFlow<Boolean>
        val allowedReactions: List<String>

        suspend fun endExplicitly()

        suspend fun submit(action: CrewAction): CrewSubmitResult
        suspend fun sendReaction(emoji: String): CrewReactionSendResult

        fun presentation(crewState: CrewState) =
            ActiveCrewPresentation(role, sessionId, localMemberId, crewState, inviteLink, connectivity)

        class Host(private val session: CrewLanHostSession) : OwnedSession {
            override val role = ActiveCrewMode.HOST
            override val sessionId = session.sessionId
            override val localMemberId = session.localMemberId
            override val state = session.state
            override val inviteLink = session.inviteLink
            override val connectivity = session.connectivity
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
            override val state = session.state
            override val inviteLink: String? = null
            override val connectivity = session.connectivity
            override val reactions = session.reactions
            override val peerMediaBlocked = session.peerMediaBlocked
            override val allowedReactions = session.allowedReactions

            override suspend fun endExplicitly() = session.leave()

            override suspend fun submit(action: CrewAction) = session.submit(action)
            override suspend fun sendReaction(emoji: String) = session.sendReaction(emoji)
        }
    }
}
