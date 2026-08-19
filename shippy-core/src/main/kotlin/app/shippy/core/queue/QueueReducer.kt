/*
 * Copyright (c) 2026 Auxio Project
 * QueueReducer.kt is part of Auxio.
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
package app.shippy.core.queue

import app.shippy.core.identity.QueueEntryId

sealed interface ShuffleState {
    data object Off : ShuffleState

    data class On(val seed: Long) : ShuffleState
}

data class QueueState(
    val baseQueue: List<QueueEntry>,
    val traversalOrder: List<QueueEntryId>,
    val currentQueueEntryId: QueueEntryId?,
    val shuffle: ShuffleState,
) {
    init {
        val baseIds = baseQueue.map(QueueEntry::id)
        require(baseIds.size == baseIds.toSet().size) { "QueueEntryId values must be unique" }
        require(traversalOrder.size == traversalOrder.toSet().size) {
            "Traversal cannot contain duplicate QueueEntryId values"
        }
        require(traversalOrder.toSet() == baseIds.toSet()) {
            "Traversal must contain every base queue entry exactly once"
        }
        require(currentQueueEntryId == null || currentQueueEntryId in baseIds) {
            "Current QueueEntryId must exist in the queue"
        }
        require(shuffle !is ShuffleState.Off || traversalOrder == baseIds) {
            "Unshuffled traversal must match base queue order"
        }
    }

    companion object {
        val Empty = QueueState(emptyList(), emptyList(), null, ShuffleState.Off)
    }
}

sealed interface QueueMutationResult {
    val state: QueueState

    data class Applied(override val state: QueueState) : QueueMutationResult

    data class Rejected(override val state: QueueState, val reason: QueueMutationRejection) :
        QueueMutationResult
}

enum class QueueMutationRejection {
    ENTRY_NOT_FOUND,
    ANCHOR_NOT_FOUND,
    STALE_ANCHOR,
    SELF_ANCHOR,
}

class QueueReducer {
    fun replace(
        entries: List<QueueEntry>,
        currentQueueEntryId: QueueEntryId?,
        shuffleSeed: Long? = null,
    ): QueueState {
        val ids = entries.map(QueueEntry::id)
        return QueueState(
            baseQueue = entries,
            traversalOrder = shuffleSeed?.let { deterministicOrder(ids, it) } ?: ids,
            currentQueueEntryId = currentQueueEntryId,
            shuffle = shuffleSeed?.let(ShuffleState::On) ?: ShuffleState.Off,
        )
    }

    fun setShuffle(state: QueueState, enabled: Boolean, seed: Long): QueueState {
        if (enabled && state.shuffle is ShuffleState.On) return state
        if (!enabled && state.shuffle is ShuffleState.Off) return state
        val order =
            if (enabled) {
                deterministicOrder(state.baseQueue.map(QueueEntry::id), seed)
            } else {
                state.baseQueue.map(QueueEntry::id)
            }
        return state.copy(
            traversalOrder = order,
            shuffle = if (enabled) ShuffleState.On(seed) else ShuffleState.Off,
        )
    }

    fun reshuffle(state: QueueState, seed: Long): QueueState =
        state.copy(
            traversalOrder = deterministicOrder(state.baseQueue.map(QueueEntry::id), seed),
            shuffle = ShuffleState.On(seed),
        )

    fun addToEnd(state: QueueState, entries: List<QueueEntry>): QueueState {
        if (entries.isEmpty()) return state
        requireUniqueAdditions(state, entries)
        val baseQueue = state.baseQueue + entries
        val traversal =
            when (val shuffle = state.shuffle) {
                ShuffleState.Off -> baseQueue.map(QueueEntry::id)
                is ShuffleState.On ->
                    insertDeterministically(
                        existing = state.traversalOrder,
                        additions = entries.map(QueueEntry::id),
                        seed = shuffle.seed,
                    )
            }
        return state.copy(baseQueue = baseQueue, traversalOrder = traversal)
    }

    fun addNext(state: QueueState, entries: List<QueueEntry>): QueueState {
        if (entries.isEmpty()) return state
        requireUniqueAdditions(state, entries)
        val ids = entries.map(QueueEntry::id)
        val baseInsertion =
            state.currentQueueEntryId?.let { state.baseQueue.indexOfId(it) + 1 }
                ?: state.baseQueue.size
        val traversalInsertion =
            state.currentQueueEntryId?.let { state.traversalOrder.indexOf(it) + 1 }
                ?: state.traversalOrder.size
        return state.copy(
            baseQueue = state.baseQueue.insertAt(baseInsertion, entries),
            traversalOrder = state.traversalOrder.insertAt(traversalInsertion, ids),
        )
    }

    fun remove(state: QueueState, entryIds: Set<QueueEntryId>): QueueState {
        if (entryIds.isEmpty()) return state
        val current = state.currentQueueEntryId
        val nextCurrent =
            if (current == null || current !in entryIds) {
                current
            } else {
                nextSurvivingEntry(state.traversalOrder, current, entryIds)
            }
        val baseQueue = state.baseQueue.filterNot { it.id in entryIds }
        val traversal = state.traversalOrder.filterNot { it in entryIds }
        return state.copy(
            baseQueue = baseQueue,
            traversalOrder =
                if (state.shuffle is ShuffleState.Off) baseQueue.map(QueueEntry::id) else traversal,
            currentQueueEntryId = nextCurrent,
        )
    }

    fun move(state: QueueState, entryId: QueueEntryId, anchor: QueueAnchor): QueueMutationResult {
        if (state.baseQueue.none { it.id == entryId }) {
            return QueueMutationResult.Rejected(state, QueueMutationRejection.ENTRY_NOT_FOUND)
        }
        if (anchor.before == entryId || anchor.after == entryId) {
            return QueueMutationResult.Rejected(state, QueueMutationRejection.SELF_ANCHOR)
        }
        val withoutEntry = state.baseQueue.filterNot { it.id == entryId }
        val insertion = resolveInsertion(withoutEntry, anchor)
        if (insertion is AnchorResolution.Rejected) {
            return QueueMutationResult.Rejected(state, insertion.reason)
        }
        val moving = state.baseQueue.first { it.id == entryId }
        val baseQueue =
            withoutEntry.insertAt((insertion as AnchorResolution.At).index, listOf(moving))
        val traversal =
            if (state.shuffle is ShuffleState.Off) baseQueue.map(QueueEntry::id)
            else state.traversalOrder
        return QueueMutationResult.Applied(
            state.copy(baseQueue = baseQueue, traversalOrder = traversal)
        )
    }

    private fun resolveInsertion(entries: List<QueueEntry>, anchor: QueueAnchor): AnchorResolution {
        val beforeIndex = anchor.before?.let { entries.indexOfId(it) }
        val afterIndex = anchor.after?.let { entries.indexOfId(it) }
        if (beforeIndex == -1 || afterIndex == -1) {
            return AnchorResolution.Rejected(QueueMutationRejection.ANCHOR_NOT_FOUND)
        }
        return when {
            beforeIndex != null && afterIndex != null && afterIndex != beforeIndex + 1 ->
                AnchorResolution.Rejected(QueueMutationRejection.STALE_ANCHOR)
            beforeIndex != null -> AnchorResolution.At(beforeIndex + 1)
            afterIndex != null -> AnchorResolution.At(afterIndex)
            entries.isEmpty() -> AnchorResolution.At(0)
            else -> AnchorResolution.Rejected(QueueMutationRejection.STALE_ANCHOR)
        }
    }

    private fun deterministicOrder(ids: List<QueueEntryId>, seed: Long): List<QueueEntryId> =
        ids.sortedWith(shuffleComparator(seed))

    private fun insertDeterministically(
        existing: List<QueueEntryId>,
        additions: List<QueueEntryId>,
        seed: Long,
    ): List<QueueEntryId> {
        val comparator = shuffleComparator(seed)
        val result = existing.toMutableList()
        for (addition in additions.sortedWith(comparator)) {
            val insertion = result.indexOfFirst { comparator.compare(addition, it) < 0 }
            if (insertion == -1) result.add(addition) else result.add(insertion, addition)
        }
        return result
    }

    private fun shuffleComparator(seed: Long): Comparator<QueueEntryId> =
        Comparator { left, right ->
            val rankComparison =
                java.lang.Long.compareUnsigned(shuffleRank(left, seed), shuffleRank(right, seed))
            if (rankComparison != 0) rankComparison else left.value.compareTo(right.value)
        }

    private fun shuffleRank(id: QueueEntryId, seed: Long): Long {
        var hash = seed xor -3750763034362895579L
        id.value.forEach { character -> hash = (hash xor character.code.toLong()) * 1099511628211L }
        hash = (hash xor (hash ushr 30)) * -4658895280553007687L
        hash = (hash xor (hash ushr 27)) * -7723592293110705685L
        return hash xor (hash ushr 31)
    }

    private fun requireUniqueAdditions(state: QueueState, entries: List<QueueEntry>) {
        val additions = entries.map(QueueEntry::id)
        require(additions.size == additions.toSet().size) {
            "Added QueueEntryId values must be unique"
        }
        require(additions.none { candidate -> state.baseQueue.any { it.id == candidate } }) {
            "Added QueueEntryId values must not already exist"
        }
    }

    private fun nextSurvivingEntry(
        traversal: List<QueueEntryId>,
        current: QueueEntryId,
        removed: Set<QueueEntryId>,
    ): QueueEntryId? {
        val index = traversal.indexOf(current)
        return traversal.drop(index + 1).firstOrNull { it !in removed }
            ?: traversal.take(index).lastOrNull { it !in removed }
    }

    private fun List<QueueEntry>.indexOfId(id: QueueEntryId): Int = indexOfFirst { it.id == id }

    private fun <T> List<T>.insertAt(index: Int, values: List<T>): List<T> =
        take(index) + values + drop(index)

    private sealed interface AnchorResolution {
        data class At(val index: Int) : AnchorResolution

        data class Rejected(val reason: QueueMutationRejection) : AnchorResolution
    }
}
