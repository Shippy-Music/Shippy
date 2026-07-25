/*
 * Copyright (c) 2026 Shippy contributors
 * CrewState.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.core

import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId

data class CrewMember(
    val id: CrewMemberId,
    val displayName: String,
) {
    init {
        require(displayName.isNotBlank()) { "Crew member displayName cannot be blank" }
    }
}

enum class CrewPlaybackMode {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
}

/** Repeat state is session state so every Crew member advances the queue the same way. */
enum class CrewRepeatMode {
    OFF,
    ALL,
    ONE,
}

data class CrewPlaybackState(
    val currentQueueItemId: QueueItemId? = null,
    val mode: CrewPlaybackMode = CrewPlaybackMode.IDLE,
    val positionAtEpochMs: Long = 0,
    val sessionEpochMs: Long = 0,
) {
    init {
        require(positionAtEpochMs >= 0) { "Playback position cannot be negative" }
        require(sessionEpochMs >= 0) { "Session epoch cannot be negative" }
        require(currentQueueItemId != null || mode == CrewPlaybackMode.IDLE) {
            "Playback without a current queue item must be idle"
        }
    }
}

data class CrewState(
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val term: CoordinatorTerm,
    val lastSequence: EventSequence,
    val coordinatorMemberId: CrewMemberId,
    val members: List<CrewMember>,
    val queue: List<QueueItem> = emptyList(),
    val playback: CrewPlaybackState = CrewPlaybackState(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: CrewRepeatMode = CrewRepeatMode.OFF,
    /**
     * Ordered, bounded replay protection for recently applied events.
     *
     * Sequence and term remain the durable source of ordering. This short cache only rejects a
     * duplicate before it reaches action validation; snapshots can safely clear it because their
     * last sequence is authoritative.
     */
    val appliedEventIds: List<DurableEventId> = emptyList(),
) {
    init {
        require(sessionId.protocolVersion == protocolVersion) {
            "Session ID protocol version must match Crew state"
        }
        require(members.isNotEmpty()) { "Crew must contain at least one member" }
        require(members.map(CrewMember::id).distinct().size == members.size) {
            "Crew member IDs must be unique"
        }
        require(members.all { it.id.protocolVersion == protocolVersion }) {
            "Member ID protocol versions must match Crew state"
        }
        require(members.any { it.id == coordinatorMemberId }) {
            "Coordinator must be an active Crew member"
        }
        require(queue.map(QueueItem::id).distinct().size == queue.size) {
            "Queue item IDs must be unique"
        }
        require(appliedEventIds.distinct().size == appliedEventIds.size) {
            "Applied Crew event IDs must be unique"
        }
        require(appliedEventIds.size <= MAX_APPLIED_EVENT_IDS) {
            "Applied Crew event ID retention exceeds its bound"
        }
        require(
            playback.currentQueueItemId == null ||
                queue.any { it.id == playback.currentQueueItemId }
        ) {
            "Current queue item must exist in the Crew queue"
        }
    }

    companion object {
        const val MAX_APPLIED_EVENT_IDS = 512
    }
}
