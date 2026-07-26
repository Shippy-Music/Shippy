/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSessionEngine.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.session

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.core.CrewEventResult
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewReducer
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshotResult
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.toSnapshot
import org.oxycblt.auxio.shippy.crew.media.CrewAuthenticatedMediaLifecycle
import org.oxycblt.auxio.shippy.crew.media.CrewAuthenticatedMediaPeer
import org.oxycblt.auxio.shippy.crew.media.CrewMediaFrameResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFrameResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlCodec
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFramer
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlReassembler
import org.oxycblt.auxio.shippy.crew.protocol.CrewSnapshotRequest
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailability
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityAnnouncement
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityEntry
import org.oxycblt.auxio.shippy.crew.preparation.MemberItemAvailability
import org.oxycblt.auxio.shippy.crew.preparation.QueueItemAvailabilitySummary
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionCodec
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionDecodeResult
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionEvent
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionId
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionPolicy
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionReducer
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionResult
import org.oxycblt.auxio.shippy.crew.reaction.CrewReactionState
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLease
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository

/** Honest V1 product bound: one local member plus at most seven active peers. */
const val CREW_MAX_SESSION_MEMBERS = 8
private const val MAX_SESSION_PEERS = CREW_MAX_SESSION_MEMBERS
private const val OUTBOUND_CONTROL_CAPACITY = 32
private const val CONTROL_SEND_TIMEOUT_MS = 10_000L
private const val CONTROL_RETRY_DELAY_MS = 20L
private const val LIVENESS_RECONCILE_INTERVAL_MS = 1_000L
val CREW_ALLOWED_REACTIONS: List<String> = listOf("❤️", "🔥", "😂", "😢", "✨", "👍")

sealed interface CrewReactionSendResult {
    data class Accepted(val reaction: ActiveCrewReaction) : CrewReactionSendResult
    data object Rejected : CrewReactionSendResult
}

private enum class CrewRouteResult {
    ROUTED,
    COORDINATOR_REJECTED,
    TRANSPORT_UNAVAILABLE,
}

sealed interface CrewSubmitResult {
    data class Submitted(val request: CrewActionRequest) : CrewSubmitResult

    data class AlreadyPending(val request: CrewActionRequest) : CrewSubmitResult

    data object CapacityReached : CrewSubmitResult

    data class Rejected(
        val request: CrewActionRequest,
        val reason: CrewOptimisticRejectionReason,
    ) : CrewSubmitResult
}

sealed interface CrewAdmissionResult {
    data class Admitted(
        val member: CrewMember,
        val event: DurableCrewEvent,
    ) : CrewAdmissionResult

    data class AlreadyActive(val member: CrewMember) : CrewAdmissionResult

    data class Rejected(val reason: CrewAdmissionRejection) : CrewAdmissionResult
}

enum class CrewAdmissionRejection {
    NOT_COORDINATOR,
    PEER_NOT_ATTACHED,
    PEER_ID_MISMATCH,
    SESSION_FULL,
    SEQUENCER_REJECTED,
}

/** Receives only a validated, coordinator-authenticated transient rejoin lease. */
fun interface CrewRejoinCredentialReceiver {
    suspend fun accept(lease: CrewRejoinLease)
}

internal object CrewSessionCapacity {
    fun hasRoom(state: CrewState): Boolean = state.members.size < CREW_MAX_SESSION_MEMBERS
}

sealed interface CrewGracefulLeaveResult {
    data class Requested(
        val transferRequest: CrewActionRequest?,
        val leaveRequest: CrewActionRequest,
    ) : CrewGracefulLeaveResult

    data object NoConnectedSuccessor : CrewGracefulLeaveResult

    data class Rejected(val submission: CrewSubmitResult) : CrewGracefulLeaveResult
}

sealed interface CrewSessionNotice {
    data class StateAdvanced(
        val event: DurableCrewEvent,
        val state: CrewState,
    ) : CrewSessionNotice

    data class SnapshotApplied(val state: CrewState) : CrewSessionNotice

    data class OptimisticAccepted(val result: CrewOptimisticReconcileResult.Accepted) :
        CrewSessionNotice

    data class OptimisticConflict(val result: CrewOptimisticReconcileResult.Conflict) :
        CrewSessionNotice

    data class OptimisticRejected(val rejection: CrewOptimisticRejection) :
        CrewSessionNotice

    data class PeerAttached(val memberId: CrewMemberId) : CrewSessionNotice

    data class PeerDetached(
        val memberId: CrewMemberId,
        val reason: CrewPeerDetachReason,
    ) : CrewSessionNotice

    data class SnapshotRequested(val coordinatorMemberId: CrewMemberId) : CrewSessionNotice

    data class ProtocolRejected(
        val memberId: CrewMemberId,
        val reason: String,
    ) : CrewSessionNotice

    data class EngineFailed(val reason: String) : CrewSessionNotice

    data class LocalLeft(val finalState: CrewState) : CrewSessionNotice

    data class SessionEnded(val finalState: CrewState) : CrewSessionNotice
}

enum class CrewPeerDetachReason {
    REPLACED,
    TRANSPORT_FAILED,
    CONTROL_BACKPRESSURE,
    PROTOCOL_VIOLATION,
    ENGINE_FAILURE,
    ENGINE_CLOSED,
}

/**
 * One active Crew's authenticated control-plane authority.
 *
 * Each peer has a single bounded outbound actor, preserving coordinator event order across
 * multi-frame messages. The engine never trusts member IDs inside a payload without comparing
 * them to the authenticated [CrewPeerTransport.remoteMemberId].
 */
class CrewSessionEngine(
    initialState: CrewState,
    private val localMemberId: CrewMemberId,
    private val checkpointRepository: CrewCheckpointRepository,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nowMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reducer: CrewReducer = CrewReducer(),
    reconnectPolicy: CrewReconnectPolicy = CrewReconnectPolicy(),
    private val mediaLifecycle: CrewAuthenticatedMediaLifecycle? = null,
    private val rejoinCredentialReceiver: CrewRejoinCredentialReceiver = CrewRejoinCredentialReceiver { },
) : Closeable {
    private data class PeerSession(
        val transport: CrewPeerTransport,
        val reassembler: CrewControlReassembler,
        val outgoing: Channel<CrewControlMessage>,
        val mediaPeer: CrewAuthenticatedMediaPeer,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateMutex = Mutex()
    private val peers = ConcurrentHashMap<CrewMemberId, PeerSession>()
    private val optimisticActions = CrewOptimisticActionTracker()
    private val liveness = CrewLivenessTracker(initialState, localMemberId, reconnectPolicy)
    private val electionVotes = CrewElectionVoteCollector()
    private var pendingElectionSnapshot:
        Pair<CrewMemberId, CrewControlMessage.SnapshotInstalled>? = null
    private var livenessJob: Job? = null
    private var sequencer: CrewCoordinatorSequencer? =
        if (initialState.coordinatorMemberId == localMemberId) {
            CrewCoordinatorSequencer(initialState, localMemberId, reducer)
        } else {
            null
        }
    private val mutableState = MutableStateFlow(initialState)
    private var localAvailability: Map<QueueItemId, CrewAvailability> = emptyMap()
    private val remoteAvailability =
        mutableMapOf<CrewMemberId, CrewAvailabilityAnnouncement>()
    private val mutableAvailability =
        MutableStateFlow<Map<QueueItemId, QueueItemAvailabilitySummary>>(emptyMap())
    private val mutablePeerStates = MutableStateFlow<Map<CrewMemberId, CrewTransportState>>(emptyMap())
    private val mutableNotices =
        MutableSharedFlow<CrewSessionNotice>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val reactionReducer =
        CrewReactionReducer(
            CrewReactionPolicy(CREW_ALLOWED_REACTIONS.toSet(), 3_000L, 250L, 32)
        )
    private var reactionState = CrewReactionState()
    private val mutableReactions =
        MutableSharedFlow<ActiveCrewReaction>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val state: StateFlow<CrewState> = mutableState.asStateFlow()
    /** Path-free active-member availability, never included in a durable Crew checkpoint. */
    val availability: StateFlow<Map<QueueItemId, QueueItemAvailabilitySummary>> =
        mutableAvailability.asStateFlow()
    val peerStates: StateFlow<Map<CrewMemberId, CrewTransportState>> =
        mutablePeerStates.asStateFlow()
    val notices: SharedFlow<CrewSessionNotice> = mutableNotices.asSharedFlow()
    val reactions: SharedFlow<ActiveCrewReaction> = mutableReactions.asSharedFlow()
    val allowedReactions: List<String> = CREW_ALLOWED_REACTIONS

    init {
        require(localMemberId.protocolVersion == initialState.protocolVersion) {
            "Local Crew member protocol must match the session"
        }
        require(initialState.members.any { it.id == localMemberId }) {
            "Local Crew member must exist in the initial state"
        }
        rebuildAvailabilityLocked()
    }

    suspend fun start() {
        check(!closed.get()) { "Crew session engine is closed" }
        checkpointRepository.save(mutableState.value.toSnapshot(), nowEpochMs())
        if (livenessJob == null) {
            livenessJob =
                scope.launch {
                    while (!closed.get()) {
                        delay(LIVENESS_RECONCILE_INTERVAL_MS)
                        reconcileLiveness()
                    }
                }
        }
    }

    @Synchronized
    fun attachPeer(transport: CrewPeerTransport) {
        check(!closed.get()) { "Crew session engine is closed" }
        require(transport.remoteMemberId != localMemberId) {
            "Crew cannot attach the local member as a remote peer"
        }
        require(transport.remoteMemberId.protocolVersion == mutableState.value.protocolVersion) {
            "Crew peer protocol must match the session"
        }
        check(peers.size < MAX_SESSION_PEERS || transport.remoteMemberId in peers) {
            "Crew peer limit reached"
        }
        val peer =
            PeerSession(
                transport = transport,
                reassembler = CrewControlReassembler(),
                outgoing = Channel(OUTBOUND_CONTROL_CAPACITY),
                mediaPeer = CrewAuthenticatedMediaPeer(transport.remoteMemberId, transport),
            )
        val replaced = peers.put(transport.remoteMemberId, peer)
        replaced?.let {
            releasePeer(
                transport.remoteMemberId,
                it,
                CrewPeerDetachReason.REPLACED,
                closeTransport = true,
            )
        }
        try {
            mediaLifecycle?.onPeerAttached(peer.mediaPeer)
        } catch (_: Exception) {
            emit(CrewSessionNotice.EngineFailed("Crew media processing failed"))
            detachPeer(
                transport.remoteMemberId,
                CrewPeerDetachReason.ENGINE_FAILURE,
                expectedPeer = peer,
            )
            return
        }
        peer.jobs += scope.launch { pumpOutbound(peer) }
        peer.jobs += scope.launch { collectInbound(peer) }
        peer.jobs += scope.launch { collectTransportState(peer) }
        liveness.connected(transport.remoteMemberId, nowMonotonicMs())
        publishPeerStates()
        emit(CrewSessionNotice.PeerAttached(transport.remoteMemberId))
    }

    suspend fun submit(
        request: CrewActionRequest,
        submittedAtMonotonicMs: Long = nowMonotonicMs(),
    ): CrewSubmitResult {
        check(!closed.get()) { "Crew session engine is closed" }
        require(request.issuingMemberId == localMemberId) {
            "Local Crew submission must identify the local member"
        }
        if (
            runCatching {
                    CrewControlCodec.encode(CrewControlMessage.Request(request))
                }
                .isFailure
        ) {
            return CrewSubmitResult.Rejected(
                request,
                CrewOptimisticRejectionReason.INVALID_REQUEST,
            )
        }
        return stateMutex.withLock {
            val submitResult = optimisticActions.submit(request, submittedAtMonotonicMs)
            when (submitResult) {
                CrewOptimisticSubmitResult.CapacityReached ->
                    CrewSubmitResult.CapacityReached
                is CrewOptimisticSubmitResult.AlreadyPending -> {
                    when (routeRequestLocked(submitResult.request)) {
                        CrewRouteResult.ROUTED ->
                            CrewSubmitResult.AlreadyPending(submitResult.request)
                        CrewRouteResult.COORDINATOR_REJECTED ->
                            CrewSubmitResult.Rejected(
                                submitResult.request,
                                CrewOptimisticRejectionReason.COORDINATOR_REJECTED,
                            )
                        CrewRouteResult.TRANSPORT_UNAVAILABLE ->
                            rejectLocalLocked(
                                submitResult.request,
                                CrewOptimisticRejectionReason.TRANSPORT_UNAVAILABLE,
                            )
                    }
                }
                is CrewOptimisticSubmitResult.Pending -> {
                    when (routeRequestLocked(submitResult.request)) {
                        CrewRouteResult.ROUTED -> CrewSubmitResult.Submitted(submitResult.request)
                        CrewRouteResult.COORDINATOR_REJECTED ->
                            CrewSubmitResult.Rejected(
                                submitResult.request,
                                CrewOptimisticRejectionReason.COORDINATOR_REJECTED,
                            )
                        CrewRouteResult.TRANSPORT_UNAVAILABLE ->
                            rejectLocalLocked(
                                submitResult.request,
                                CrewOptimisticRejectionReason.TRANSPORT_UNAVAILABLE,
                            )
                    }
                }
            }
        }
    }

    suspend fun requestSnapshot(): Boolean =
        stateMutex.withLock {
            val current = mutableState.value
            if (current.coordinatorMemberId == localMemberId) return@withLock false
            val request =
                CrewControlMessage.SnapshotRequested(
                    CrewSnapshotRequest(
                        sessionId = current.sessionId,
                        protocolVersion = current.protocolVersion,
                        requestingMemberId = localMemberId,
                        knownTerm = current.term,
                        knownSequence = current.lastSequence,
                    )
                )
            val queued = enqueueLocked(current.coordinatorMemberId, request)
            if (queued) {
                emit(CrewSessionNotice.SnapshotRequested(current.coordinatorMemberId))
            }
            queued
        }

    suspend fun admitPeer(
        transportMemberId: CrewMemberId,
        member: CrewMember,
        requestId: DurableEventId,
        clientMonotonicTimestampMs: Long = nowMonotonicMs(),
    ): CrewAdmissionResult {
        check(!closed.get()) { "Crew session engine is closed" }
        require(clientMonotonicTimestampMs >= 0) {
            "Crew admission monotonic timestamp cannot be negative"
        }
        return stateMutex.withLock {
            val current = mutableState.value
            current.members.firstOrNull { it.id == member.id }?.let {
                return@withLock CrewAdmissionResult.AlreadyActive(it)
            }
            if (current.coordinatorMemberId != localMemberId || sequencer == null) {
                return@withLock CrewAdmissionResult.Rejected(
                    CrewAdmissionRejection.NOT_COORDINATOR
                )
            }
            if (member.id != transportMemberId) {
                return@withLock CrewAdmissionResult.Rejected(
                    CrewAdmissionRejection.PEER_ID_MISMATCH
                )
            }
            if (peers[transportMemberId] == null) {
                return@withLock CrewAdmissionResult.Rejected(
                    CrewAdmissionRejection.PEER_NOT_ATTACHED
                )
            }
            if (!CrewSessionCapacity.hasRoom(current)) {
                return@withLock CrewAdmissionResult.Rejected(
                    CrewAdmissionRejection.SESSION_FULL
                )
            }
            val request =
                CrewActionRequest(
                    id = requestId,
                    issuingMemberId = localMemberId,
                    clientMonotonicTimestampMs = clientMonotonicTimestampMs,
                    action = CrewAction.MemberJoined(member),
                )
            when (val result = checkNotNull(sequencer).sequence(request, localMemberId)) {
                is CrewSequenceResult.Published -> {
                    publishSequenceResultLocked(result, request, localMemberId)
                    enqueueLocked(
                        member.id,
                        CrewControlMessage.SnapshotInstalled(result.state.toSnapshot()),
                    )
                    CrewAdmissionResult.Admitted(member, result.event)
                }
                is CrewSequenceResult.Duplicate -> {
                    publishSequenceResultLocked(result, request, localMemberId)
                    enqueueLocked(
                        member.id,
                        CrewControlMessage.SnapshotInstalled(result.state.toSnapshot()),
                    )
                    CrewAdmissionResult.Admitted(member, result.event)
                }
                is CrewSequenceResult.Rejected ->
                    CrewAdmissionResult.Rejected(CrewAdmissionRejection.SEQUENCER_REJECTED)
            }
        }
    }

    /** Coordinator-only, member-targeted delivery after ordinary authenticated admission. */
    suspend fun sendRejoinCredential(
        memberId: CrewMemberId,
        lease: CrewRejoinLease,
    ): Boolean =
        stateMutex.withLock {
            val current = mutableState.value
            if (
                current.coordinatorMemberId != localMemberId ||
                    lease.sessionId != current.sessionId ||
                    lease.protocolVersion != current.protocolVersion ||
                    lease.memberId != memberId ||
                    nowEpochMs() !in lease.issuedAtEpochMs until lease.expiresAtEpochMs
            ) {
                false
            } else {
                enqueueLocked(memberId, CrewControlMessage.RejoinCredentialIssued(lease))
            }
        }

    suspend fun gracefulLeave(
        transferRequestId: DurableEventId,
        leaveRequestId: DurableEventId,
        clientMonotonicTimestampMs: Long = nowMonotonicMs(),
    ): CrewGracefulLeaveResult {
        check(!closed.get()) { "Crew session engine is closed" }
        val before = mutableState.value
        var transferRequest: CrewActionRequest? = null
        if (before.coordinatorMemberId == localMemberId) {
            val successor =
                before.members
                    .asSequence()
                    .map(CrewMember::id)
                    .filter { it != localMemberId }
                    .filter { peerStates.value[it] == CrewTransportState.CONNECTED }
                    .minByOrNull(CrewMemberId::value)
                    ?: return CrewGracefulLeaveResult.NoConnectedSuccessor
            transferRequest =
                CrewActionRequest(
                    id = transferRequestId,
                    issuingMemberId = localMemberId,
                    clientMonotonicTimestampMs = clientMonotonicTimestampMs,
                    action = CrewAction.CoordinatorTransferred(successor),
                )
            val transferResult = submit(transferRequest, clientMonotonicTimestampMs)
            if (
                transferResult !is CrewSubmitResult.Submitted &&
                    transferResult !is CrewSubmitResult.AlreadyPending
            ) {
                return CrewGracefulLeaveResult.Rejected(transferResult)
            }
        }
        val leaveRequest =
            CrewActionRequest(
                id = leaveRequestId,
                issuingMemberId = localMemberId,
                clientMonotonicTimestampMs = clientMonotonicTimestampMs,
                action = CrewAction.MemberLeft(localMemberId),
            )
        val leaveResult = submit(leaveRequest, clientMonotonicTimestampMs)
        return if (
            leaveResult is CrewSubmitResult.Submitted ||
                leaveResult is CrewSubmitResult.AlreadyPending
        ) {
            CrewGracefulLeaveResult.Requested(transferRequest, leaveRequest)
        } else {
            CrewGracefulLeaveResult.Rejected(leaveResult)
        }
    }

    fun expireOptimisticActions(nowMonotonicMs: Long = this.nowMonotonicMs()) {
        optimisticActions.expire(nowMonotonicMs).forEach {
            emit(CrewSessionNotice.OptimisticRejected(it))
        }
    }

    fun livenessDecisions(nowMonotonicMs: Long = this.nowMonotonicMs()): List<CrewLivenessDecision> =
        liveness.evaluate(mutableState.value, nowMonotonicMs)

    /**
     * Converts expired liveness policy into ordered membership/election control messages.
     *
     * This is public for deterministic tests and manual lifecycle triggers; [start] also runs it
     * periodically so transport loss cannot remain a presentation-only decision.
     */
    suspend fun reconcileLiveness(
        nowMonotonicMs: Long = this.nowMonotonicMs()
    ): List<CrewLivenessDecision> =
        stateMutex.withLock {
            val decisions = liveness.evaluate(mutableState.value, nowMonotonicMs)
            decisions.forEach { decision ->
                when (decision) {
                    is CrewLivenessDecision.MemberRemovalEligible ->
                        sequenceExpiredMemberRemovalLocked(decision, nowMonotonicMs)
                    is CrewLivenessDecision.ElectionEligible ->
                        beginOrContinueElectionLocked(decision)
                    is CrewLivenessDecision.AwaitingReconnect,
                    is CrewLivenessDecision.CoordinatorUnavailableWithoutQuorum -> Unit
                }
            }
            decisions
        }

    /**
     * Publishes local, path-free capability for current queue entries. The payload is transient:
     * it is never sequenced, reduced, or checkpointed.
     */
    suspend fun publishLocalAvailability(
        availability: Map<QueueItemId, CrewAvailability>,
    ): Boolean =
        stateMutex.withLock {
            if (closed.get()) return@withLock false
            val current = mutableState.value
            val currentQueueIds = current.queue.map { it.id }.toSet()
            require(availability.keys.all { it in currentQueueIds }) {
                "Availability must only describe current queue items"
            }
            val announcement =
                CrewAvailabilityAnnouncement(
                    sessionId = current.sessionId,
                    protocolVersion = current.protocolVersion,
                    publishingMemberId = localMemberId,
                    knownTerm = current.term,
                    knownSequence = current.lastSequence,
                    entries =
                        availability.entries
                            .sortedBy { it.key.value }
                            .map { (queueItemId, value) ->
                                CrewAvailabilityEntry(queueItemId, value)
                            },
                )
            if (runCatching { CrewControlCodec.encode(CrewControlMessage.AvailabilityAnnounced(announcement)) }.isFailure) {
                return@withLock false
            }
            localAvailability = availability.toMap()
            rebuildAvailabilityLocked()
            if (current.coordinatorMemberId == localMemberId) {
                broadcastAvailabilityLocked(announcement)
                true
            } else {
                enqueueLocked(
                    current.coordinatorMemberId,
                    CrewControlMessage.AvailabilityAnnounced(announcement),
                )
            }
        }

    private suspend fun collectInbound(peer: PeerSession) {
        try {
            peer.transport.incoming.collect { frame ->
                liveness.connected(peer.transport.remoteMemberId, nowMonotonicMs())
                if (frame.channel == CrewTransportChannel.REACTION) {
                    (CrewReactionCodec.decode(frame.copyPayload()) as? CrewReactionDecodeResult.Decoded)
                        ?.let { handleReaction(peer.transport.remoteMemberId, it.event) }
                    return@collect
                }
                if (frame.channel == CrewTransportChannel.MEDIA) {
                    val result =
                        try {
                            mediaLifecycle?.onMediaFrame(peer.mediaPeer, frame)
                        } catch (_: Exception) {
                            emit(CrewSessionNotice.EngineFailed("Crew media processing failed"))
                            detachPeer(
                                peer.transport.remoteMemberId,
                                CrewPeerDetachReason.ENGINE_FAILURE,
                                expectedPeer = peer,
                            )
                            return@collect
                        }
                    when (result) {
                        null,
                        CrewMediaFrameResult.Accepted -> Unit
                        is CrewMediaFrameResult.Rejected -> {
                            protocolViolation(
                                peer,
                                "Crew media frame rejected: ${result.reason}",
                            )
                        }
                    }
                    return@collect
                }
                if (frame.channel != CrewTransportChannel.CONTROL) return@collect
                when (
                    val result =
                        peer.reassembler.accept(
                            frame,
                            nowMonotonicMs(),
                        )
                ) {
                    is CrewControlFrameResult.Pending -> Unit
                    is CrewControlFrameResult.Complete ->
                        handleMessage(peer.transport.remoteMemberId, result.message)
                    is CrewControlFrameResult.Rejected -> {
                        protocolViolation(
                            peer,
                            "Crew control frame rejected: ${result.name}",
                        )
                        return@collect
                    }
                }
            }
            if (!closed.get() && peers[peer.transport.remoteMemberId] === peer) {
                detachPeer(
                    peer.transport.remoteMemberId,
                    CrewPeerDetachReason.TRANSPORT_FAILED,
                    expectedPeer = peer,
                )
            }
        } catch (_: Exception) {
            if (!closed.get() && peers[peer.transport.remoteMemberId] === peer) {
                emit(CrewSessionNotice.EngineFailed("Crew control processing failed"))
                detachPeer(
                    peer.transport.remoteMemberId,
                    CrewPeerDetachReason.ENGINE_FAILURE,
                    expectedPeer = peer,
                )
            }
        }
    }

    private suspend fun collectTransportState(peer: PeerSession) {
        peer.transport.state.collect { transportState ->
            publishPeerStates()
            if (
                transportState == CrewTransportState.FAILED ||
                    transportState == CrewTransportState.CLOSED
            ) {
                detachPeer(
                    peer.transport.remoteMemberId,
                    CrewPeerDetachReason.TRANSPORT_FAILED,
                    expectedPeer = peer,
                )
                return@collect
            }
        }
    }

    private suspend fun pumpOutbound(peer: PeerSession) {
        for (message in peer.outgoing) {
            val sent =
                withTimeoutOrNull(CONTROL_SEND_TIMEOUT_MS) {
                    var complete = true
                    for (frame in CrewControlFramer.encode(message)) {
                        if (!sendFrameWhenReady(peer.transport, frame)) {
                            complete = false
                            break
                        }
                    }
                    complete
                } == true
            if (!sent) {
                detachPeer(
                    peer.transport.remoteMemberId,
                    CrewPeerDetachReason.CONTROL_BACKPRESSURE,
                    expectedPeer = peer,
                )
                return
            }
        }
    }

    private suspend fun sendFrameWhenReady(
        transport: CrewPeerTransport,
        frame: org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame,
    ): Boolean {
        while (!closed.get()) {
            when (transport.trySend(frame)) {
                is CrewSendResult.Sent -> return true
                is CrewSendResult.Backpressured,
                CrewSendResult.ChannelNotOpen -> {
                    val transportState = transport.state.value
                    if (
                        transportState == CrewTransportState.FAILED ||
                            transportState == CrewTransportState.CLOSED
                    ) {
                        return false
                    }
                    delay(CONTROL_RETRY_DELAY_MS)
                }
                CrewSendResult.NativeRejected,
                CrewSendResult.Closed -> return false
            }
        }
        return false
    }

    private suspend fun handleMessage(
        authenticatedMemberId: CrewMemberId,
        message: CrewControlMessage,
    ) {
        stateMutex.withLock {
            when (message) {
                is CrewControlMessage.Request ->
                    handleRequestLocked(authenticatedMemberId, message.request)
                is CrewControlMessage.Event ->
                    handleEventLocked(authenticatedMemberId, message.event)
                is CrewControlMessage.SnapshotRequested ->
                    handleSnapshotRequestLocked(authenticatedMemberId, message.request)
                is CrewControlMessage.SnapshotInstalled ->
                    handleSnapshotLocked(authenticatedMemberId, message)
                is CrewControlMessage.ElectionVoteCast ->
                    handleElectionVoteLocked(authenticatedMemberId, message.vote)
                is CrewControlMessage.AvailabilityAnnounced ->
                    handleAvailabilityLocked(authenticatedMemberId, message.announcement)
                is CrewControlMessage.RejoinCredentialIssued ->
                    handleRejoinCredentialLocked(authenticatedMemberId, message.lease)
                is CrewControlMessage.RequestRejected ->
                    handleRejectionLocked(authenticatedMemberId, message)
            }
        }
    }

    private suspend fun handleRejoinCredentialLocked(
        authenticatedMemberId: CrewMemberId,
        lease: CrewRejoinLease,
    ) {
        val current = mutableState.value
        if (
            authenticatedMemberId != current.coordinatorMemberId ||
                lease.sessionId != current.sessionId ||
                lease.protocolVersion != current.protocolVersion ||
                lease.memberId != localMemberId ||
                nowEpochMs() !in lease.issuedAtEpochMs until lease.expiresAtEpochMs
        ) {
            protocolRejected(authenticatedMemberId, "Rejected invalid Crew rejoin credential")
            return
        }
        rejoinCredentialReceiver.accept(lease)
    }

    private suspend fun handleRequestLocked(
        authenticatedMemberId: CrewMemberId,
        request: CrewActionRequest,
    ) {
        val currentSequencer = sequencer
        if (currentSequencer == null) {
            protocolRejected(authenticatedMemberId, "Received action request on non-coordinator")
            return
        }
        publishSequenceResultLocked(
            currentSequencer.sequence(request, authenticatedMemberId),
            request = request,
            requester = authenticatedMemberId,
        )
    }

    private suspend fun handleEventLocked(
        authenticatedMemberId: CrewMemberId,
        event: DurableCrewEvent,
    ) {
        val current = mutableState.value
        when (val result = reducer.apply(current, event, authenticatedMemberId)) {
            is CrewEventResult.Applied -> {
                installStateLocked(result.state)
                reconcileLocked(event)
                persistAcceptedStateLocked(result.state)
                emit(CrewSessionNotice.StateAdvanced(event, result.state))
            }
            is CrewEventResult.DuplicateRejected -> reconcileLocked(event)
            is CrewEventResult.SnapshotRequired -> {
                enqueueSnapshotRequestLocked()
            }
            is CrewEventResult.Rejected ->
                protocolRejected(authenticatedMemberId, result.reason)
            is CrewEventResult.StaleSequenceRejected,
            is CrewEventResult.StaleTermRejected -> Unit
        }
    }

    private suspend fun handleSnapshotRequestLocked(
        authenticatedMemberId: CrewMemberId,
        request: CrewSnapshotRequest,
    ) {
        val current = mutableState.value
        if (
            localMemberId != current.coordinatorMemberId ||
                request.requestingMemberId != authenticatedMemberId ||
                request.sessionId != current.sessionId ||
                request.protocolVersion != current.protocolVersion ||
                current.members.none { it.id == authenticatedMemberId }
        ) {
            protocolRejected(authenticatedMemberId, "Crew snapshot request is unauthorized")
            return
        }
        enqueueLocked(
            authenticatedMemberId,
            CrewControlMessage.SnapshotInstalled(current.toSnapshot()),
        )
    }

    private suspend fun handleSnapshotLocked(
        authenticatedMemberId: CrewMemberId,
        message: CrewControlMessage.SnapshotInstalled,
    ) {
        val current = mutableState.value
        val authenticatedVotes =
            if (message.electionVotes.isEmpty()) {
                emptyList()
            } else {
                val eligibility = currentElectionEligibilityLocked()
                val certificate =
                    eligibility?.let {
                        electionVotes.authenticatedCertificate(current, it)
                    }
                if (
                    certificate == null ||
                        message.electionVotes.any { it !in certificate }
                ) {
                    pendingElectionSnapshot = authenticatedMemberId to message
                    return
                }
                message.electionVotes
            }
        when (
            val result =
                reducer.applySnapshot(
                    current,
                    message.snapshot,
                    authenticatedPublisher = authenticatedMemberId,
                    authenticatedElectionVotes = authenticatedVotes,
                )
        ) {
            is CrewSnapshotResult.Applied -> {
                pendingElectionSnapshot = null
                installStateLocked(result.state)
                persistAcceptedStateLocked(result.state)
                emit(CrewSessionNotice.SnapshotApplied(result.state))
            }
            is CrewSnapshotResult.Rejected ->
                protocolRejected(authenticatedMemberId, result.reason)
            is CrewSnapshotResult.StaleRejected -> Unit
        }
    }

    private suspend fun handleReaction(
        authenticatedMemberId: CrewMemberId,
        event: CrewReactionEvent,
    ) {
        stateMutex.withLock {
            val current = mutableState.value
            when (val result = applyReactionLocked(event, authenticatedMemberId)) {
                is CrewReactionResult.Rejected -> Unit
                is CrewReactionResult.Accepted -> {
                    reactionState = result.state
                    mutableReactions.tryEmit(result.state.active.last())
                    if (current.coordinatorMemberId == localMemberId) {
                        broadcastReactionLocked(event, authenticatedMemberId)
                    }
                }
            }
        }
    }

    private fun handleAvailabilityLocked(
        authenticatedMemberId: CrewMemberId,
        announcement: CrewAvailabilityAnnouncement,
    ) {
        val current = mutableState.value
        val activeMemberIds = current.members.map { it.id }.toSet()
        val currentQueueIds = current.queue.map { it.id }.toSet()
        if (
            announcement.sessionId != current.sessionId ||
                announcement.protocolVersion != current.protocolVersion ||
                announcement.publishingMemberId !in activeMemberIds ||
                announcement.knownTerm != current.term ||
                announcement.knownSequence != current.lastSequence ||
                announcement.entries.any { it.queueItemId !in currentQueueIds }
        ) {
            protocolRejected(authenticatedMemberId, "Crew availability announcement is stale or unauthorized")
            return
        }
        if (current.coordinatorMemberId == localMemberId) {
            if (announcement.publishingMemberId != authenticatedMemberId) {
                protocolRejected(authenticatedMemberId, "Crew availability publisher is forged")
                return
            }
            if (remoteAvailability[authenticatedMemberId] == announcement) {
                return
            }
            remoteAvailability[authenticatedMemberId] = announcement
            rebuildAvailabilityLocked()
            broadcastAvailabilityLocked(announcement, exceptMemberId = authenticatedMemberId)
            return
        }
        if (authenticatedMemberId != current.coordinatorMemberId) {
            protocolRejected(authenticatedMemberId, "Crew availability relay is not the coordinator")
            return
        }
        if (announcement.publishingMemberId == localMemberId) {
            protocolRejected(authenticatedMemberId, "Crew availability relay cannot republish local truth")
            return
        }
        if (remoteAvailability[announcement.publishingMemberId] == announcement) {
            return
        }
        remoteAvailability[announcement.publishingMemberId] = announcement
        rebuildAvailabilityLocked()
    }

    private fun applyReactionLocked(
        event: CrewReactionEvent,
        authenticatedMemberId: CrewMemberId,
    ) =
        reactionReducer.apply(
            reactionState,
            event,
            authenticatedMemberId,
            mutableState.value.sessionId,
            mutableState.value.members.map { it.id }.toSet(),
            nowMonotonicMs(),
            mutableState.value.coordinatorMemberId,
            localMemberId,
        )

    suspend fun sendReaction(emoji: String): CrewReactionSendResult =
        stateMutex.withLock {
            if (closed.get()) return@withLock CrewReactionSendResult.Rejected
            val current = mutableState.value
            val event = CrewReactionEvent(
                current.sessionId,
                localMemberId,
                CrewReactionId(UUID.randomUUID().toString()),
                emoji,
            )
            when (val result = applyReactionLocked(event, localMemberId)) {
                is CrewReactionResult.Rejected -> CrewReactionSendResult.Rejected
                is CrewReactionResult.Accepted -> {
                    reactionState = result.state
                    val accepted = result.state.active.last()
                    mutableReactions.tryEmit(accepted)
                    if (current.coordinatorMemberId == localMemberId) {
                        broadcastReactionLocked(event)
                    } else {
                        sendReactionLocked(current.coordinatorMemberId, event)
                    }
                    CrewReactionSendResult.Accepted(accepted)
                }
            }
        }

    private suspend fun handleElectionVoteLocked(
        authenticatedMemberId: CrewMemberId,
        vote: CrewElectionVote,
    ) {
        val current = mutableState.value
        val eligibility = currentElectionEligibilityLocked()
        if (eligibility == null) {
            protocolRejected(authenticatedMemberId, "Crew election is not currently eligible")
            return
        }
        when (
            val result =
                electionVotes.record(
                    state = current,
                    eligibility = eligibility,
                    vote = vote,
                    authenticatedVoter = authenticatedMemberId,
                )
        ) {
            is CrewElectionVoteResult.Recorded,
            is CrewElectionVoteResult.Duplicate -> {
                completeElectionIfPossibleLocked(eligibility, result)
                val pending = pendingElectionSnapshot
                if (pending != null) {
                    pendingElectionSnapshot = null
                    handleSnapshotLocked(pending.first, pending.second)
                }
            }
            is CrewElectionVoteResult.Rejected ->
                protocolRejected(authenticatedMemberId, result.reason)
        }
    }

    private fun handleRejectionLocked(
        authenticatedMemberId: CrewMemberId,
        message: CrewControlMessage.RequestRejected,
    ) {
        val current = mutableState.value
        if (
            authenticatedMemberId != current.coordinatorMemberId ||
                message.coordinatorMemberId != authenticatedMemberId ||
                message.sessionId != current.sessionId ||
                message.protocolVersion != current.protocolVersion
        ) {
            protocolRejected(authenticatedMemberId, "Crew request rejection is unauthorized")
            return
        }
        optimisticActions
            .reject(
                message.requestId,
                CrewOptimisticRejectionReason.COORDINATOR_REJECTED,
            )
            ?.let { emit(CrewSessionNotice.OptimisticRejected(it)) }
    }

    private suspend fun sequenceExpiredMemberRemovalLocked(
        decision: CrewLivenessDecision.MemberRemovalEligible,
        nowMonotonicMs: Long,
    ) {
        val current = mutableState.value
        val currentSequencer = sequencer ?: return
        if (
            current.coordinatorMemberId != localMemberId ||
                decision.memberId == localMemberId ||
                decision.memberId == current.coordinatorMemberId ||
                current.members.none { it.id == decision.memberId }
        ) {
            return
        }
        val request =
            CrewActionRequest(
                id = DurableEventId("liveness-${UUID.randomUUID()}"),
                issuingMemberId = localMemberId,
                clientMonotonicTimestampMs = nowMonotonicMs,
                action = CrewAction.MemberLeft(decision.memberId),
            )
        publishSequenceResultLocked(
            currentSequencer.sequence(request, localMemberId),
            request = request,
            requester = localMemberId,
        )
    }

    private suspend fun beginOrContinueElectionLocked(
        eligibility: CrewLivenessDecision.ElectionEligible
    ) {
        val current = mutableState.value
        if (localMemberId !in eligibility.connectedVoterIds) return
        val vote =
            CrewElectionVote(
                voterMemberId = localMemberId,
                candidateMemberId = eligibility.candidateMemberId,
                checkpoint = eligibility.checkpoint,
            )
        when (
            val result =
                electionVotes.record(
                    state = current,
                    eligibility = eligibility,
                    vote = vote,
                    authenticatedVoter = localMemberId,
                )
        ) {
            is CrewElectionVoteResult.Recorded -> {
                eligibility.connectedVoterIds
                    .filter { it != localMemberId }
                    .forEach { memberId ->
                        enqueueLocked(memberId, CrewControlMessage.ElectionVoteCast(vote))
                    }
                completeElectionIfPossibleLocked(eligibility, result)
            }
            is CrewElectionVoteResult.Duplicate ->
                completeElectionIfPossibleLocked(eligibility, result)
            is CrewElectionVoteResult.Rejected ->
                emit(CrewSessionNotice.EngineFailed(result.reason))
        }
    }

    private suspend fun completeElectionIfPossibleLocked(
        eligibility: CrewLivenessDecision.ElectionEligible,
        result: CrewElectionVoteResult,
    ) {
        val (hasStrictMajority, authenticatedVotes) =
            when (result) {
                is CrewElectionVoteResult.Recorded ->
                    result.hasStrictMajority to result.authenticatedVotes
                is CrewElectionVoteResult.Duplicate ->
                    result.hasStrictMajority to result.authenticatedVotes
                is CrewElectionVoteResult.Rejected -> return
            }
        if (!hasStrictMajority || eligibility.candidateMemberId != localMemberId) return
        val current = mutableState.value
        val snapshot = current.toElectedSnapshot(localMemberId)
        when (
            val applied =
                reducer.applySnapshot(
                    state = current,
                    snapshot = snapshot,
                    authenticatedPublisher = localMemberId,
                    authenticatedElectionVotes = authenticatedVotes,
                )
        ) {
            is CrewSnapshotResult.Applied -> {
                pendingElectionSnapshot = null
                installStateLocked(applied.state)
                persistAcceptedStateLocked(applied.state)
                broadcastLocked(
                    CrewControlMessage.SnapshotInstalled(snapshot, authenticatedVotes)
                )
                emit(CrewSessionNotice.SnapshotApplied(applied.state))
            }
            is CrewSnapshotResult.Rejected ->
                emit(CrewSessionNotice.EngineFailed(applied.reason))
            is CrewSnapshotResult.StaleRejected -> Unit
        }
    }

    private fun currentElectionEligibilityLocked():
        CrewLivenessDecision.ElectionEligible? =
        liveness
            .evaluate(mutableState.value, nowMonotonicMs())
            .filterIsInstance<CrewLivenessDecision.ElectionEligible>()
            .singleOrNull()

    private suspend fun routeRequestLocked(request: CrewActionRequest): CrewRouteResult {
        val current = mutableState.value
        return if (current.coordinatorMemberId == localMemberId) {
            val currentSequencer = checkNotNull(sequencer) { "Local coordinator has no sequencer" }
            val result = currentSequencer.sequence(request, localMemberId)
            publishSequenceResultLocked(
                result,
                request = request,
                requester = localMemberId,
            )
            if (result is CrewSequenceResult.Rejected) {
                CrewRouteResult.COORDINATOR_REJECTED
            } else {
                CrewRouteResult.ROUTED
            }
        } else {
            if (
                enqueueLocked(
                    current.coordinatorMemberId,
                    CrewControlMessage.Request(request),
                )
            ) {
                CrewRouteResult.ROUTED
            } else {
                CrewRouteResult.TRANSPORT_UNAVAILABLE
            }
        }
    }

    private suspend fun publishSequenceResultLocked(
        result: CrewSequenceResult,
        request: CrewActionRequest,
        requester: CrewMemberId,
    ) {
        when (result) {
            is CrewSequenceResult.Published -> {
                installStateLocked(result.state)
                reconcileLocked(result.event)
                persistAcceptedStateLocked(result.state)
                broadcastLocked(
                    CrewControlMessage.Event(result.event),
                    additionalMemberIds = setOf(result.event.issuingMemberId),
                )
                emit(CrewSessionNotice.StateAdvanced(result.event, result.state))
            }
            is CrewSequenceResult.Duplicate -> {
                if (requester == localMemberId) {
                    reconcileLocked(result.event)
                } else {
                    enqueueLocked(requester, CrewControlMessage.Event(result.event))
                }
            }
            is CrewSequenceResult.Rejected -> {
                if (requester == localMemberId) {
                    optimisticActions
                        .reject(
                            request.id,
                            CrewOptimisticRejectionReason.COORDINATOR_REJECTED,
                        )
                        ?.let { emit(CrewSessionNotice.OptimisticRejected(it)) }
                } else {
                    enqueueLocked(
                        requester,
                        CrewControlMessage.RequestRejected(
                            sessionId = result.state.sessionId,
                            protocolVersion = result.state.protocolVersion,
                            requestId = request.id,
                            coordinatorMemberId = localMemberId,
                            reason = result.reason,
                        ),
                    )
                }
            }
        }
    }

    private fun rejectLocalLocked(
        request: CrewActionRequest,
        reason: CrewOptimisticRejectionReason,
    ): CrewSubmitResult.Rejected {
        optimisticActions.reject(request.id, reason)?.let {
            emit(CrewSessionNotice.OptimisticRejected(it))
        }
        return CrewSubmitResult.Rejected(request, reason)
    }

    private fun reconcileLocked(event: DurableCrewEvent) {
        when (val result = optimisticActions.reconcile(event)) {
            is CrewOptimisticReconcileResult.Accepted ->
                emit(CrewSessionNotice.OptimisticAccepted(result))
            is CrewOptimisticReconcileResult.Conflict ->
                emit(CrewSessionNotice.OptimisticConflict(result))
            CrewOptimisticReconcileResult.NotPending -> Unit
        }
    }

    private fun installStateLocked(state: CrewState) {
        mutableState.value = state
        pruneAvailabilityLocked()
        liveness.reconcile(state)
        electionVotes.resetUnless(state)
        sequencer =
            when {
                state.coordinatorMemberId != localMemberId -> null
                sequencer?.state() == state -> sequencer
                else -> CrewCoordinatorSequencer(state, localMemberId, reducer)
            }
    }

    private suspend fun persistAcceptedStateLocked(state: CrewState) {
        if (state.playback.mode == CrewPlaybackMode.ENDED) {
            checkpointRepository.clear(state.sessionId)
            emit(CrewSessionNotice.SessionEnded(state))
        } else if (state.members.any { it.id == localMemberId }) {
            checkpointRepository.save(state.toSnapshot(), nowEpochMs())
        } else {
            checkpointRepository.clear(state.sessionId)
            emit(CrewSessionNotice.LocalLeft(state))
            scope.launch { close() }
        }
    }

    private fun broadcastLocked(
        message: CrewControlMessage,
        additionalMemberIds: Set<CrewMemberId> = emptySet(),
    ) {
        (mutableState.value.members.map { it.id } + additionalMemberIds)
            .distinct()
            .filter { it != localMemberId }
            .sortedBy(CrewMemberId::value)
            .forEach { memberId ->
            enqueueLocked(memberId, message)
        }
    }

    private fun enqueueSnapshotRequestLocked() {
        val current = mutableState.value
        if (current.coordinatorMemberId == localMemberId) return
        if (
            enqueueLocked(
                current.coordinatorMemberId,
                CrewControlMessage.SnapshotRequested(
                    CrewSnapshotRequest(
                        sessionId = current.sessionId,
                        protocolVersion = current.protocolVersion,
                        requestingMemberId = localMemberId,
                        knownTerm = current.term,
                        knownSequence = current.lastSequence,
                    )
                ),
            )
        ) {
            emit(CrewSessionNotice.SnapshotRequested(current.coordinatorMemberId))
        }
    }

    private fun enqueueLocked(
        memberId: CrewMemberId,
        message: CrewControlMessage,
    ): Boolean {
        val peer = peers[memberId] ?: return false
        return if (peer.outgoing.trySend(message).isSuccess) {
            true
        } else {
            detachPeer(
                memberId,
                CrewPeerDetachReason.CONTROL_BACKPRESSURE,
                expectedPeer = peer,
            )
            false
        }
    }

    private fun protocolRejected(
        memberId: CrewMemberId,
        reason: String,
    ) {
        emit(CrewSessionNotice.ProtocolRejected(memberId, reason))
    }

    private fun protocolViolation(
        peer: PeerSession,
        reason: String,
    ) {
        val memberId = peer.transport.remoteMemberId
        protocolRejected(memberId, reason)
        detachPeer(
            memberId,
            CrewPeerDetachReason.PROTOCOL_VIOLATION,
            expectedPeer = peer,
        )
    }

    private fun detachPeer(
        memberId: CrewMemberId,
        reason: CrewPeerDetachReason,
        expectedPeer: PeerSession? = null,
    ) {
        val removed =
            if (expectedPeer == null) {
                peers.remove(memberId)
            } else if (peers.remove(memberId, expectedPeer)) {
                expectedPeer
            } else {
                null
            }
        removed?.let {
            releasePeer(memberId, it, reason, closeTransport = true)
        }
    }

    private fun releasePeer(
        memberId: CrewMemberId,
        peer: PeerSession,
        reason: CrewPeerDetachReason,
        closeTransport: Boolean,
    ) {
        peer.outgoing.close()
        peer.jobs.forEach { it.cancel() }
        peer.reassembler.reset()
        try {
            mediaLifecycle?.onPeerDetached(peer.mediaPeer)
        } catch (_: Exception) {
            emit(CrewSessionNotice.EngineFailed("Crew media cleanup failed"))
        }
        if (reason != CrewPeerDetachReason.ENGINE_CLOSED) {
            liveness.disconnected(memberId, nowMonotonicMs())
        }
        if (closeTransport) peer.transport.close()
        publishPeerStates()
        emit(CrewSessionNotice.PeerDetached(memberId, reason))
    }

    private fun publishPeerStates() {
        mutablePeerStates.value =
            peers.entries.associate { (memberId, peer) ->
                memberId to peer.transport.state.value
            }
    }

    private fun emit(notice: CrewSessionNotice) {
        mutableNotices.tryEmit(notice)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peers.entries.toList().forEach { (memberId, peer) ->
            if (peers.remove(memberId, peer)) {
                releasePeer(
                    memberId,
                    peer,
                    CrewPeerDetachReason.ENGINE_CLOSED,
                    closeTransport = true,
                )
            }
        }
        optimisticActions.clearForSessionChange().forEach {
            emit(CrewSessionNotice.OptimisticRejected(it))
        }
        pendingElectionSnapshot = null
        electionVotes.reset()
        livenessJob = null
        scope.cancel()
    }

    private fun broadcastAvailabilityLocked(
        announcement: CrewAvailabilityAnnouncement,
        exceptMemberId: CrewMemberId? = null,
    ) {
        mutableState.value.members
            .map { it.id }
            .filter { it != localMemberId && it != exceptMemberId }
            .sortedBy(CrewMemberId::value)
            .forEach { memberId ->
                enqueueLocked(memberId, CrewControlMessage.AvailabilityAnnounced(announcement))
            }
    }

    private fun pruneAvailabilityLocked() {
        val current = mutableState.value
        val currentQueueIds = current.queue.map { it.id }.toSet()
        val activeMemberIds = current.members.map { it.id }.toSet()
        localAvailability = localAvailability.filterKeys { it in currentQueueIds }
        remoteAvailability.entries.removeIf { (memberId, announcement) ->
            memberId !in activeMemberIds ||
                announcement.sessionId != current.sessionId ||
                announcement.protocolVersion != current.protocolVersion ||
                announcement.publishingMemberId != memberId ||
                announcement.knownTerm != current.term ||
                announcement.knownSequence != current.lastSequence ||
                announcement.entries.any { it.queueItemId !in currentQueueIds }
        }
        rebuildAvailabilityLocked()
    }

    private fun rebuildAvailabilityLocked() {
        val current = mutableState.value
        val activeMemberIds = current.members.map { it.id }.toSet()
        val remoteByMember = remoteAvailability
            .filterKeys { it in activeMemberIds }
            .mapValues { (_, announcement) -> announcement.availabilityByQueueItem() }
        mutableAvailability.value =
            current.queue.associate { item ->
                item.id to
                    QueueItemAvailabilitySummary(
                        queueItem = item,
                        members =
                            current.members.map { member ->
                                val value =
                                    if (member.id == localMemberId) {
                                        localAvailability[item.id]
                                    } else {
                                        remoteByMember[member.id]?.get(item.id)
                                    } ?: CrewAvailability.UNAVAILABLE
                                MemberItemAvailability(member.id, item.id, value)
                            },
                    )
            }
    }

    private fun broadcastReactionLocked(
        event: CrewReactionEvent,
        exceptMemberId: CrewMemberId? = null,
    ) {
        peers.keys.filter { it != exceptMemberId }.forEach { sendReactionLocked(it, event) }
    }

    private fun sendReactionLocked(memberId: CrewMemberId, event: CrewReactionEvent) {
        val peer = peers[memberId] ?: return
        val payload = runCatching { CrewReactionCodec.encode(event) }.getOrNull() ?: return
        // Intentionally lossy: a reaction never detaches a peer or changes durable state.
        peer.transport.trySend(
            org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame(
                CrewTransportChannel.REACTION,
                payload,
            )
        )
    }
}
