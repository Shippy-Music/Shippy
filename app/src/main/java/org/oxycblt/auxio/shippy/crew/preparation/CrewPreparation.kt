/*
 * Copyright (c) 2026 Auxio Project
 * CrewPreparation.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.preparation

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId

/**
 * Capability-only state sent over Crew control messages. It deliberately contains no paths,
 * provider URLs, or media locators.
 *
 * [PEER_ONLY] means a compatible active-Crew supplier may have the item. [BLOCKED] is a definitive
 * local policy/capability denial, not a synonym for a temporarily disabled Push & Pull toggle. A
 * PEER_ONLY member becomes blocked for *this preparation attempt* when Push & Pull is disabled,
 * while its published capability remains PEER_ONLY.
 */
enum class CrewAvailability {
    LOCAL_EXACT,
    TEMPORARY_CACHE,
    DOWNLOAD,
    PREFERRED_PROVIDER,
    FALLBACK_PROVIDER,
    PEER_ONLY,
    BLOCKED,
    UNAVAILABLE;

    val isPlayableWithoutPeer: Boolean
        get() =
            this in
                setOf(LOCAL_EXACT, TEMPORARY_CACHE, DOWNLOAD, PREFERRED_PROVIDER, FALLBACK_PROVIDER)

    val isEligibleSupplier: Boolean
        get() = this in setOf(LOCAL_EXACT, TEMPORARY_CACHE, DOWNLOAD)
}

data class MemberItemAvailability(
    val memberId: CrewMemberId,
    val queueItemId: QueueItemId,
    val availability: CrewAvailability,
)

/** A compact, path-free availability view for one active session queue item. */
data class QueueItemAvailabilitySummary(
    val queueItem: QueueItem,
    val members: List<MemberItemAvailability>,
) {
    init {
        require(members.all { it.queueItemId == queueItem.id }) {
            "Availability must describe the containing queue item"
        }
        require(members.map { it.memberId }.distinct().size == members.size) {
            "A member can publish one availability per queue item"
        }
    }

    fun availabilityFor(memberId: CrewMemberId): CrewAvailability? =
        members.firstOrNull { it.memberId == memberId }?.availability
}

/**
 * Target-specific, ephemeral connection telemetry. It is not durable Crew state and cannot reveal a
 * local path. The completion estimate includes connection setup and transfer time.
 */
data class SupplierCandidateMetrics(
    val targetMemberId: CrewMemberId,
    val supplierMemberId: CrewMemberId,
    val connectionEstimateMs: Long,
    val estimatedCompletionMs: Long,
    val activeUploadCount: Int,
    val batteryEligible: Boolean,
    val networkEligible: Boolean,
) {
    init {
        require(connectionEstimateMs >= 0) { "Connection estimate cannot be negative" }
        require(estimatedCompletionMs >= 0) { "Completion estimate cannot be negative" }
        require(activeUploadCount >= 0) { "Upload pressure cannot be negative" }
    }

    val isEligible: Boolean
        get() = batteryEligible && networkEligible
}

enum class PeerSupplySource {
    LOCAL_EXACT,
    TEMPORARY_CACHE,
    DOWNLOAD,
}

sealed interface SupplierDecision {
    data object AlreadyPlayable : SupplierDecision

    data class Selected(val supplierMemberId: CrewMemberId, val source: PeerSupplySource) :
        SupplierDecision

    /** Published PEER_ONLY capability, but the listener has disabled peer-media for this Crew. */
    data object PushPullDisabled : SupplierDecision

    /** Peer media is allowed, but no candidate can safely supply this item now. */
    data object NoEligibleSupplier : SupplierDecision

    data class NotPlayable(val availability: CrewAvailability) : SupplierDecision
}

/**
 * Chooses a supplier using session-visible capability plus short-lived connection telemetry. There
 * is intentionally no coordinator parameter: every active member can supply an item.
 */
object CrewSupplierSelector {
    fun select(
        summary: QueueItemAvailabilitySummary,
        targetMemberId: CrewMemberId,
        pushPullEnabled: Boolean,
        candidateMetrics: Collection<SupplierCandidateMetrics> = emptyList(),
        activeMemberIds: Collection<CrewMemberId> = summary.members.map { it.memberId },
    ): SupplierDecision {
        val targetAvailability =
            summary.availabilityFor(targetMemberId) ?: CrewAvailability.UNAVAILABLE
        if (targetAvailability.isPlayableWithoutPeer) return SupplierDecision.AlreadyPlayable
        if (
            targetAvailability == CrewAvailability.BLOCKED ||
                targetAvailability == CrewAvailability.UNAVAILABLE
        ) {
            return SupplierDecision.NotPlayable(targetAvailability)
        }
        if (targetAvailability != CrewAvailability.PEER_ONLY) {
            return SupplierDecision.NotPlayable(targetAvailability)
        }
        if (!pushPullEnabled) return SupplierDecision.PushPullDisabled

        val activeMembers = activeMemberIds.toSet()
        val matchingMetrics =
            candidateMetrics.filter {
                it.targetMemberId == targetMemberId && it.supplierMemberId != targetMemberId
            }
        require(matchingMetrics.groupBy { it.supplierMemberId }.values.all { it.size == 1 }) {
            "A target may publish one candidate metric per supplier"
        }
        val metricsBySupplier = matchingMetrics.associateBy { it.supplierMemberId }

        return summary.members
            .asSequence()
            .filter {
                it.memberId != targetMemberId &&
                    it.memberId in activeMembers &&
                    it.availability.isEligibleSupplier
            }
            .map { availability ->
                SupplierCandidate(
                    memberId = availability.memberId,
                    source = availability.availability.toSupplySource(),
                    metrics = metricsBySupplier[availability.memberId],
                )
            }
            .filter { it.metrics?.isEligible != false }
            .sortedWith(supplierComparator)
            .firstOrNull()
            ?.let { SupplierDecision.Selected(it.memberId, it.source) }
            ?: SupplierDecision.NoEligibleSupplier
    }

    private data class SupplierCandidate(
        val memberId: CrewMemberId,
        val source: PeerSupplySource,
        val metrics: SupplierCandidateMetrics?,
    )

    private val supplierComparator =
        compareBy<SupplierCandidate>(
            { it.source.priority },
            { it.metrics?.estimatedCompletionMs ?: Long.MAX_VALUE },
            { it.metrics?.connectionEstimateMs ?: Long.MAX_VALUE },
            { it.metrics?.activeUploadCount ?: Int.MAX_VALUE },
            { it.memberId.value },
            { it.memberId.protocolVersion.value },
        )

    private val PeerSupplySource.priority: Int
        get() =
            when (this) {
                PeerSupplySource.LOCAL_EXACT -> 0
                PeerSupplySource.TEMPORARY_CACHE -> 1
                PeerSupplySource.DOWNLOAD -> 2
            }

    private fun CrewAvailability.toSupplySource(): PeerSupplySource =
        when (this) {
            CrewAvailability.LOCAL_EXACT -> PeerSupplySource.LOCAL_EXACT
            CrewAvailability.TEMPORARY_CACHE -> PeerSupplySource.TEMPORARY_CACHE
            CrewAvailability.DOWNLOAD -> PeerSupplySource.DOWNLOAD
            else -> error("Only supplier availability may be converted to a source")
        }
}

data class CrewReadinessPolicy(
    val deadlineEpochMs: Long,
    val requireCommonStart: Boolean = true,
    val allowLateJoinAfterDeadline: Boolean = true,
) {
    init {
        require(deadlineEpochMs >= 0) { "Readiness deadline cannot be negative" }
    }
}

enum class CrewReadinessProblemReason {
    PUSH_PULL_DISABLED,
    NO_ELIGIBLE_SUPPLIER,
    BLOCKED,
    UNAVAILABLE,
    DEADLINE_EXPIRED,
}

data class CrewReadinessProblem(val memberId: CrewMemberId, val reason: CrewReadinessProblemReason)

sealed interface CrewStartDecision {
    data class StartTogether(val readyMembers: List<CrewMemberId>) : CrewStartDecision

    /** Viable peer transfers are in progress and the configured shared start is still bounded. */
    data class WaitForCommonStart(
        val pendingMembers: List<CrewMemberId>,
        val deadlineEpochMs: Long,
    ) : CrewStartDecision

    /** Ready devices begin; viable peers receive the calculated live position after preparation. */
    data class StartReadyMembersWithLateJoin(
        val readyMembers: List<CrewMemberId>,
        val lateMembers: List<CrewMemberId>,
    ) : CrewStartDecision

    /** Members cannot become playable, rather than merely needing a bounded preparation window. */
    data class Unavailable(val problems: List<CrewReadinessProblem>) : CrewStartDecision
}

object CrewReadinessPlanner {
    fun decide(
        summary: QueueItemAvailabilitySummary,
        activeMembers: Collection<CrewMemberId>,
        pushPullEnabled: Boolean,
        nowEpochMs: Long,
        policy: CrewReadinessPolicy,
        candidateMetrics: Collection<SupplierCandidateMetrics> = emptyList(),
    ): CrewStartDecision {
        val members = activeMembers.distinct().sortedMemberIds()
        val ready = mutableListOf<CrewMemberId>()
        val pending = mutableListOf<CrewMemberId>()
        val problems = mutableListOf<CrewReadinessProblem>()

        for (memberId in members) {
            when (
                val decision =
                    CrewSupplierSelector.select(
                        summary,
                        memberId,
                        pushPullEnabled,
                        candidateMetrics,
                        members,
                    )
            ) {
                SupplierDecision.AlreadyPlayable -> ready += memberId
                is SupplierDecision.Selected -> pending += memberId
                SupplierDecision.PushPullDisabled ->
                    problems += memberId.problem(CrewReadinessProblemReason.PUSH_PULL_DISABLED)
                SupplierDecision.NoEligibleSupplier ->
                    problems += memberId.problem(CrewReadinessProblemReason.NO_ELIGIBLE_SUPPLIER)
                is SupplierDecision.NotPlayable ->
                    problems += memberId.problem(decision.availability.toProblemReason())
            }
        }

        if (problems.isNotEmpty()) return CrewStartDecision.Unavailable(problems)
        if (pending.isEmpty()) return CrewStartDecision.StartTogether(ready)
        if (!policy.requireCommonStart && ready.isNotEmpty()) {
            return CrewStartDecision.StartReadyMembersWithLateJoin(ready, pending)
        }
        if (nowEpochMs < policy.deadlineEpochMs) {
            return CrewStartDecision.WaitForCommonStart(pending, policy.deadlineEpochMs)
        }
        if (policy.allowLateJoinAfterDeadline && ready.isNotEmpty()) {
            return CrewStartDecision.StartReadyMembersWithLateJoin(ready, pending)
        }
        return CrewStartDecision.Unavailable(
            pending.map { it.problem(CrewReadinessProblemReason.DEADLINE_EXPIRED) }
        )
    }

    private fun CrewAvailability.toProblemReason() =
        when (this) {
            CrewAvailability.BLOCKED -> CrewReadinessProblemReason.BLOCKED
            else -> CrewReadinessProblemReason.UNAVAILABLE
        }
}

data class CrewPrefetchPolicy(val lookAheadItems: Int) {
    init {
        require(lookAheadItems >= 0) { "Look-ahead count cannot be negative" }
    }
}

data class CrewPrefetchKey(
    val queueItemId: QueueItemId,
    val targetMemberId: CrewMemberId,
    val supplierMemberId: CrewMemberId,
)

data class CrewPrefetchRequest(
    val key: CrewPrefetchKey,
    val source: PeerSupplySource,
    /** Larger values are scheduled first; the current item always outranks upcoming items. */
    val priority: Int,
)

data class CrewPrefetchPlan(
    val start: List<CrewPrefetchRequest>,
    val keep: List<CrewPrefetchRequest>,
    val reprioritize: List<CrewPrefetchRequest>,
    val cancellations: List<CrewPrefetchKey>,
)

/**
 * Plans current-plus-upcoming preparation while retaining reusable transfers across queue edits.
 */
object CrewPrefetchPlanner {
    fun plan(
        queue: List<QueueItem>,
        currentQueueItemId: QueueItemId?,
        summaries: Map<QueueItemId, QueueItemAvailabilitySummary>,
        activeMembers: Collection<CrewMemberId>,
        pushPullEnabled: Boolean,
        policy: CrewPrefetchPolicy,
        existingRequests: Collection<CrewPrefetchRequest>,
        candidateMetricsByItem: Map<QueueItemId, Collection<SupplierCandidateMetrics>> = emptyMap(),
    ): CrewPrefetchPlan {
        require(queue.map { it.id }.distinct().size == queue.size) {
            "Crew queue item IDs must be unique"
        }
        require(existingRequests.map { it.key }.distinct().size == existingRequests.size) {
            "A Crew prefetch key may have one active request"
        }
        val members = activeMembers.distinct().sortedMemberIds()
        val currentIndex =
            currentQueueItemId?.let { currentId ->
                queue.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }
            }
        val window =
            buildList {
                    currentIndex?.let(queue::get)?.let(::add)
                    val nextStart = currentIndex?.plus(1) ?: 0
                    addAll(queue.drop(nextStart).take(policy.lookAheadItems))
                }
                .distinctBy { it.id }
        val desired =
            window
                .flatMapIndexed { index, item ->
                    val summary = summaries[item.id] ?: return@flatMapIndexed emptyList()
                    val priority = window.size - index
                    members.mapNotNull { memberId ->
                        val selected =
                            CrewSupplierSelector.select(
                                summary,
                                memberId,
                                pushPullEnabled,
                                candidateMetricsByItem[item.id].orEmpty(),
                                members,
                            )
                        (selected as? SupplierDecision.Selected)?.let { supplier ->
                            CrewPrefetchRequest(
                                CrewPrefetchKey(item.id, memberId, supplier.supplierMemberId),
                                supplier.source,
                                priority,
                            )
                        }
                    }
                }
                .sortedWith(prefetchRequestComparator)
        val existingByKey = existingRequests.associateBy { it.key }
        val desiredByKey = desired.associateBy { it.key }
        val start = desired.filter { it.key !in existingByKey }
        val keep =
            desired.filter { request ->
                existingByKey[request.key]?.let {
                    it.source == request.source && it.priority == request.priority
                } == true
            }
        val reprioritize =
            desired.filter { request ->
                existingByKey[request.key]?.let {
                    it.source != request.source || it.priority != request.priority
                } == true
            }
        val cancellations =
            existingByKey.keys.filterNot { it in desiredByKey }.sortedWith(prefetchKeyComparator)
        return CrewPrefetchPlan(start, keep, reprioritize, cancellations)
    }

    private val prefetchRequestComparator =
        compareByDescending<CrewPrefetchRequest> { it.priority }
            .thenBy { it.key.queueItemId.value }
            .thenBy { it.key.targetMemberId.value }
            .thenBy { it.key.supplierMemberId.value }

    private val prefetchKeyComparator =
        compareBy<CrewPrefetchKey>(
            { it.queueItemId.value },
            { it.targetMemberId.value },
            { it.supplierMemberId.value },
        )
}

private fun Collection<CrewMemberId>.sortedMemberIds(): List<CrewMemberId> =
    sortedWith(compareBy({ it.value }, { it.protocolVersion.value }))

private fun CrewMemberId.problem(reason: CrewReadinessProblemReason) =
    CrewReadinessProblem(this, reason)
