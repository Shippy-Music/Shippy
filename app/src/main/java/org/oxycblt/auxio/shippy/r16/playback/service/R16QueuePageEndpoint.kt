/*
 * Copyright (c) 2026 Auxio Project
 * R16QueuePageEndpoint.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.service

import android.os.Bundle
import android.os.ResultReceiver
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.queue.QueueAnchor
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16RecordingPresentation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.oxycblt.auxio.BuildConfig

/** A bounded read request against the service-owned queue occurrence order. */
data class R16QueuePageRequest(
    /** Null asks for the page containing the current occurrence; a value is an explicit offset. */
    val offset: Int? = null,
    val limit: Int = R16QueuePageEndpoint.DEFAULT_PAGE_SIZE,
    val expectedQueueRevision: Long? = null,
) {
    init {
        require(offset == null || offset >= 0) { "Queue page offset cannot be negative" }
        require(limit > 0) { "Queue page limit must be positive" }
        require(expectedQueueRevision == null || expectedQueueRevision >= 0) {
            "Expected queue revision cannot be negative"
        }
    }

    val boundedLimit: Int
        get() = limit.coerceAtMost(R16QueuePageEndpoint.MAX_PAGE_SIZE)
}

/** An exact queue occurrence plus optional metadata for one row in a bounded page. */
data class R16QueuePageEntry(
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val presentation: R16RecordingPresentation?,
)

data class R16QueuePage(
    val offset: Int,
    val limit: Int,
    val totalCount: Int,
    val items: List<R16QueuePageEntry>,
    val currentQueueEntryId: QueueEntryId?,
    val queueRevision: Long,
) {
    /**
     * Zero-based position inside [items], or -1 when the current occurrence is not on this page.
     */
    val currentIndex: Int
        get() = items.indexOfFirst { it.queueEntryId == currentQueueEntryId }

    val previousOffset: Int?
        get() = offset.takeIf { it > 0 }?.let { (it - limit).coerceAtLeast(0) }

    val nextOffset: Int?
        get() = (offset + items.size).takeIf { it < totalCount }

    val hasNext: Boolean
        get() = offset.toLong() + items.size < totalCount

    val hasPrevious: Boolean
        get() = previousOffset != null
}

data class R16QueueGoToRequest(
    val queueEntryId: QueueEntryId,
    val expectedQueueRevision: Long? = null,
) {
    init {
        require(expectedQueueRevision == null || expectedQueueRevision >= 0) {
            "Expected queue revision cannot be negative"
        }
    }
}

data class R16QueueMoveRequest(
    val queueEntryId: QueueEntryId,
    val anchor: QueueAnchor,
    val expectedQueueRevision: Long? = null,
    val moveTop: Boolean = false,
    val moveBottom: Boolean = false,
) {
    init {
        require(expectedQueueRevision == null || expectedQueueRevision >= 0) {
            "Expected queue revision cannot be negative"
        }
    }
}

data class R16QueueRemoveRequest(
    val queueEntryIds: Set<QueueEntryId>,
    val expectedQueueRevision: Long? = null,
) {
    init {
        require(expectedQueueRevision == null || expectedQueueRevision >= 0) {
            "Expected queue revision cannot be negative"
        }
    }
}

sealed interface R16QueueCommandResult {
    val queueRevision: Long
    val currentQueueEntryId: QueueEntryId?

    data class Page(val page: R16QueuePage) : R16QueueCommandResult {
        override val queueRevision: Long
            get() = page.queueRevision

        override val currentQueueEntryId: QueueEntryId?
            get() = page.currentQueueEntryId
    }

    data class GoToAccepted(
        val generation: Long,
        override val queueRevision: Long,
        override val currentQueueEntryId: QueueEntryId?,
    ) : R16QueueCommandResult

    data class MutationAccepted(
        val generation: Long,
        override val queueRevision: Long,
        override val currentQueueEntryId: QueueEntryId?,
    ) : R16QueueCommandResult

    data class Rejected(
        val reason: R16QueueRejection,
        override val queueRevision: Long,
        override val currentQueueEntryId: QueueEntryId?,
    ) : R16QueueCommandResult
}

enum class R16QueueRejection {
    INVALID_REQUEST,
    STALE_QUEUE_REVISION,
    ENTRY_NOT_FOUND,
    EMPTY_QUEUE,
    SERVICE_NOT_ATTACHED,
    COORDINATOR_RELEASED,
    INVALID_QUEUE_MUTATION,
}

/**
 * Service-side queue contract for the R16 queue screen and MediaSession command bridge.
 *
 * The endpoint deliberately reads [PlaybackSnapshot] directly. It never creates a second queue
 * authority, maps the complete queue to presentation objects, or mutates MediaSession's legacy
 * queue projection.
 */
class R16QueuePageEndpoint(
    private val snapshots: StateFlow<PlaybackSnapshot>,
    private val router: PlaybackCommandRouter,
    private val presentations: R16PlaybackPresentationRepository? = null,
) {
    private var entryIndex: QueueEntryIndex? = null

    suspend fun page(request: R16QueuePageRequest): R16QueueCommandResult {
        val snapshot = snapshots.value
        if (
            request.expectedQueueRevision != null &&
                request.expectedQueueRevision != snapshot.queueRevision
        ) {
            return snapshot.rejected(R16QueueRejection.STALE_QUEUE_REVISION)
        }

        val traversal = snapshot.queue.traversalOrder
        val index = entryIndex(snapshot.queue)
        val pageLimit = request.boundedLimit
        val anchoredOffset =
            request.offset
                ?: index.position(snapshot.queue.currentQueueEntryId)?.let {
                    (it / pageLimit) * pageLimit
                }
                ?: 0
        val start = anchoredOffset.coerceAtMost(traversal.size)
        val end = (start.toLong() + pageLimit).coerceAtMost(traversal.size.toLong()).toInt()
        val pageIds = traversal.subList(start, end)
        val entries = pageIds.map(index::entry)
        val recordingIds = entries.map(QueueEntry::recordingId).toSet()
        val resolvedPresentations = presentations?.observe(recordingIds)?.first().orEmpty()
        val current = snapshots.value
        if (current.queueRevision != snapshot.queueRevision) {
            return current.rejected(R16QueueRejection.STALE_QUEUE_REVISION)
        }

        return R16QueueCommandResult.Page(
            R16QueuePage(
                offset = start,
                limit = pageLimit,
                totalCount = traversal.size,
                items =
                    entries.map { entry ->
                        R16QueuePageEntry(
                            queueEntryId = entry.id,
                            recordingId = entry.recordingId,
                            presentation = resolvedPresentations[entry.recordingId],
                        )
                    },
                currentQueueEntryId = current.queue.currentQueueEntryId,
                queueRevision = current.queueRevision,
            )
        )
    }

    suspend fun goTo(request: R16QueueGoToRequest): R16QueueCommandResult {
        val snapshot = snapshots.value
        if (
            request.expectedQueueRevision != null &&
                request.expectedQueueRevision != snapshot.queueRevision
        ) {
            return snapshot.rejected(R16QueueRejection.STALE_QUEUE_REVISION)
        }
        if (snapshot.queue.traversalOrder.none { it == request.queueEntryId }) {
            return snapshot.rejected(R16QueueRejection.ENTRY_NOT_FOUND)
        }

        return when (val result = router.dispatch(PlaybackCommand.GoTo(request.queueEntryId))) {
            is PlaybackCommandResult.Accepted -> {
                val current = snapshots.value
                R16QueueCommandResult.GoToAccepted(
                    generation = result.generation,
                    queueRevision = result.queueRevision,
                    currentQueueEntryId = current.queue.currentQueueEntryId,
                )
            }
            is PlaybackCommandResult.Rejected -> {
                snapshots.value.rejected(result.reason.toQueueRejection())
            }
        }
    }

    suspend fun move(request: R16QueueMoveRequest): R16QueueCommandResult {
        val snapshot = snapshots.value
        if (
            request.expectedQueueRevision != null &&
                request.expectedQueueRevision != snapshot.queueRevision
        ) {
            return snapshot.rejected(R16QueueRejection.STALE_QUEUE_REVISION)
        }
        if (snapshot.queue.traversalOrder.none { it == request.queueEntryId }) {
            return snapshot.rejected(R16QueueRejection.ENTRY_NOT_FOUND)
        }

        val effectiveAnchor =
            when {
                request.moveTop -> {
                    val firstOther =
                        snapshot.queue.traversalOrder.firstOrNull { it != request.queueEntryId }
                    QueueAnchor(before = null, after = firstOther)
                }
                request.moveBottom -> {
                    val lastOther =
                        snapshot.queue.traversalOrder.lastOrNull { it != request.queueEntryId }
                    QueueAnchor(before = lastOther, after = null)
                }
                else -> request.anchor
            }

        return when (
            val result =
                router.dispatch(PlaybackCommand.Move(request.queueEntryId, effectiveAnchor))
        ) {
            is PlaybackCommandResult.Accepted -> {
                val current = snapshots.value
                R16QueueCommandResult.MutationAccepted(
                    generation = result.generation,
                    queueRevision = result.queueRevision,
                    currentQueueEntryId = current.queue.currentQueueEntryId,
                )
            }
            is PlaybackCommandResult.Rejected -> {
                snapshots.value.rejected(result.reason.toQueueRejection())
            }
        }
    }

    suspend fun remove(request: R16QueueRemoveRequest): R16QueueCommandResult {
        val snapshot = snapshots.value
        if (
            request.expectedQueueRevision != null &&
                request.expectedQueueRevision != snapshot.queueRevision
        ) {
            return snapshot.rejected(R16QueueRejection.STALE_QUEUE_REVISION)
        }

        return when (val result = router.dispatch(PlaybackCommand.Remove(request.queueEntryIds))) {
            is PlaybackCommandResult.Accepted -> {
                val current = snapshots.value
                R16QueueCommandResult.MutationAccepted(
                    generation = result.generation,
                    queueRevision = result.queueRevision,
                    currentQueueEntryId = current.queue.currentQueueEntryId,
                )
            }
            is PlaybackCommandResult.Rejected -> {
                snapshots.value.rejected(result.reason.toQueueRejection())
            }
        }
    }

    /** Dispatches stable MediaSession commands without exposing queue internals to it. */
    suspend fun handle(command: String, extras: Bundle?): R16QueueCommandResult =
        when (command) {
            R16QueueMediaCommands.PAGE ->
                extras.toPageRequest()?.let { request -> page(request) }
                    ?: snapshots.value.rejected(R16QueueRejection.INVALID_REQUEST)
            R16QueueMediaCommands.GO_TO ->
                extras.toGoToRequest()?.let { request -> goTo(request) }
                    ?: snapshots.value.rejected(R16QueueRejection.INVALID_REQUEST)
            R16QueueMediaCommands.MOVE ->
                extras.toMoveRequest()?.let { request -> move(request) }
                    ?: snapshots.value.rejected(R16QueueRejection.INVALID_REQUEST)
            R16QueueMediaCommands.REMOVE ->
                extras.toRemoveRequest()?.let { request -> remove(request) }
                    ?: snapshots.value.rejected(R16QueueRejection.INVALID_REQUEST)
            else -> snapshots.value.rejected(R16QueueRejection.INVALID_REQUEST)
        }

    private fun entryIndex(queue: QueueState): QueueEntryIndex {
        val current = entryIndex
        return if (current != null && current.queue === queue) {
            current
        } else {
            QueueEntryIndex(queue).also { entryIndex = it }
        }
    }

    private fun PlaybackSnapshot.rejected(reason: R16QueueRejection) =
        R16QueueCommandResult.Rejected(
            reason = reason,
            queueRevision = queueRevision,
            currentQueueEntryId = queue.currentQueueEntryId,
        )

    private fun PlaybackCommandRejection.toQueueRejection() =
        when (this) {
            PlaybackCommandRejection.ENTRY_NOT_FOUND -> R16QueueRejection.ENTRY_NOT_FOUND
            PlaybackCommandRejection.EMPTY_QUEUE -> R16QueueRejection.EMPTY_QUEUE
            PlaybackCommandRejection.SERVICE_NOT_ATTACHED -> R16QueueRejection.SERVICE_NOT_ATTACHED
            PlaybackCommandRejection.COORDINATOR_RELEASED -> R16QueueRejection.COORDINATOR_RELEASED
            PlaybackCommandRejection.INVALID_QUEUE_MUTATION ->
                R16QueueRejection.INVALID_QUEUE_MUTATION
        }

    companion object {
        const val MAX_PAGE_SIZE = 50
        const val DEFAULT_PAGE_SIZE = MAX_PAGE_SIZE
    }

    private class QueueEntryIndex(val queue: QueueState) {
        private val entriesById = queue.baseQueue.associateBy(QueueEntry::id)
        private val positionsById =
            queue.traversalOrder.withIndex().associate { (position, id) -> id to position }

        fun entry(id: QueueEntryId): QueueEntry = checkNotNull(entriesById[id])

        fun position(id: QueueEntryId?): Int? = id?.let(positionsById::get)
    }
}

/** Stable command and ResultReceiver keys for the service-owned queue page contract. */
object R16QueueMediaCommands {
    const val PAGE = BuildConfig.APPLICATION_ID + ".r16.queue.page"
    const val GO_TO = BuildConfig.APPLICATION_ID + ".r16.queue.go_to"
    const val MOVE = BuildConfig.APPLICATION_ID + ".r16.queue.move"
    const val REMOVE = BuildConfig.APPLICATION_ID + ".r16.queue.remove"

    const val EXTRA_OFFSET = BuildConfig.APPLICATION_ID + ".r16.queue.offset"
    const val EXTRA_LIMIT = BuildConfig.APPLICATION_ID + ".r16.queue.limit"
    const val EXTRA_QUEUE_REVISION = BuildConfig.APPLICATION_ID + ".r16.queue.revision"
    const val EXTRA_QUEUE_ENTRY_ID = BuildConfig.APPLICATION_ID + ".r16.queue.entry_id"
    const val EXTRA_ANCHOR_BEFORE = BuildConfig.APPLICATION_ID + ".r16.queue.anchor_before"
    const val EXTRA_ANCHOR_AFTER = BuildConfig.APPLICATION_ID + ".r16.queue.anchor_after"
    const val EXTRA_MOVE_TOP = BuildConfig.APPLICATION_ID + ".r16.queue.move_top"
    const val EXTRA_MOVE_BOTTOM = BuildConfig.APPLICATION_ID + ".r16.queue.move_bottom"
    const val EXTRA_QUEUE_ENTRY_IDS = BuildConfig.APPLICATION_ID + ".r16.queue.entry_ids"

    const val RESULT_OK = 0
    const val RESULT_INVALID_REQUEST = 1
    const val RESULT_STALE_QUEUE_REVISION = 2
    const val RESULT_ENTRY_NOT_FOUND = 3
    const val RESULT_REJECTED = 4

    const val KEY_OFFSET = EXTRA_OFFSET
    const val KEY_LIMIT = EXTRA_LIMIT
    const val KEY_TOTAL_COUNT = BuildConfig.APPLICATION_ID + ".r16.queue.total_count"
    const val KEY_HAS_NEXT = BuildConfig.APPLICATION_ID + ".r16.queue.has_next"
    const val KEY_HAS_PREVIOUS = BuildConfig.APPLICATION_ID + ".r16.queue.has_previous"
    const val KEY_CURRENT_INDEX = BuildConfig.APPLICATION_ID + ".r16.queue.current_index"
    const val KEY_PREVIOUS_OFFSET = BuildConfig.APPLICATION_ID + ".r16.queue.previous_offset"
    const val KEY_NEXT_OFFSET = BuildConfig.APPLICATION_ID + ".r16.queue.next_offset"
    const val KEY_ITEMS = BuildConfig.APPLICATION_ID + ".r16.queue.items"
    const val KEY_CURRENT_QUEUE_ENTRY_ID =
        BuildConfig.APPLICATION_ID + ".r16.queue.current_entry_id"
    const val KEY_QUEUE_REVISION = EXTRA_QUEUE_REVISION
    const val KEY_GENERATION = BuildConfig.APPLICATION_ID + ".r16.queue.generation"
    const val KEY_REJECTION = BuildConfig.APPLICATION_ID + ".r16.queue.rejection"

    const val KEY_RECORDING_ID = BuildConfig.APPLICATION_ID + ".r16.queue.item.recording_id"
    const val KEY_TITLE = BuildConfig.APPLICATION_ID + ".r16.queue.item.title"
    const val KEY_ARTIST = BuildConfig.APPLICATION_ID + ".r16.queue.item.artist"
    const val KEY_RELEASE_TITLE = BuildConfig.APPLICATION_ID + ".r16.queue.item.release"
    const val KEY_ARTWORK_LOCATION = BuildConfig.APPLICATION_ID + ".r16.queue.item.artwork"
    const val KEY_DURATION_MS = BuildConfig.APPLICATION_ID + ".r16.queue.item.duration_ms"

    fun isQueueCommand(command: String): Boolean =
        command == PAGE || command == GO_TO || command == MOVE || command == REMOVE

    fun resultCode(result: R16QueueCommandResult): Int =
        when (result) {
            is R16QueueCommandResult.Page,
            is R16QueueCommandResult.GoToAccepted,
            is R16QueueCommandResult.MutationAccepted -> RESULT_OK
            is R16QueueCommandResult.Rejected ->
                when (result.reason) {
                    R16QueueRejection.INVALID_REQUEST -> RESULT_INVALID_REQUEST
                    R16QueueRejection.STALE_QUEUE_REVISION -> RESULT_STALE_QUEUE_REVISION
                    R16QueueRejection.ENTRY_NOT_FOUND -> RESULT_ENTRY_NOT_FOUND
                    else -> RESULT_REJECTED
                }
        }

    fun resultBundle(result: R16QueueCommandResult): Bundle =
        Bundle().apply {
            putLong(KEY_QUEUE_REVISION, result.queueRevision)
            putString(KEY_CURRENT_QUEUE_ENTRY_ID, result.currentQueueEntryId?.value)
            when (result) {
                is R16QueueCommandResult.Page -> {
                    putInt(KEY_OFFSET, result.page.offset)
                    putInt(KEY_LIMIT, result.page.limit)
                    putInt(KEY_TOTAL_COUNT, result.page.totalCount)
                    putBoolean(KEY_HAS_NEXT, result.page.hasNext)
                    putBoolean(KEY_HAS_PREVIOUS, result.page.hasPrevious)
                    putInt(KEY_CURRENT_INDEX, result.page.currentIndex)
                    result.page.previousOffset?.let { putInt(KEY_PREVIOUS_OFFSET, it) }
                    result.page.nextOffset?.let { putInt(KEY_NEXT_OFFSET, it) }
                    putParcelableArrayList(
                        KEY_ITEMS,
                        ArrayList(result.page.items.map(::itemBundle)),
                    )
                }
                is R16QueueCommandResult.GoToAccepted -> putLong(KEY_GENERATION, result.generation)
                is R16QueueCommandResult.MutationAccepted ->
                    putLong(KEY_GENERATION, result.generation)
                is R16QueueCommandResult.Rejected -> putString(KEY_REJECTION, result.reason.name)
            }
        }

    fun send(resultReceiver: ResultReceiver?, result: R16QueueCommandResult) {
        resultReceiver?.send(resultCode(result), resultBundle(result))
    }

    private fun itemBundle(item: R16QueuePageEntry) =
        Bundle().apply {
            putString(EXTRA_QUEUE_ENTRY_ID, item.queueEntryId.value)
            putString(KEY_RECORDING_ID, item.recordingId.value)
            item.presentation?.let { presentation ->
                putString(KEY_TITLE, presentation.title)
                putString(KEY_ARTIST, presentation.artist)
                putString(KEY_RELEASE_TITLE, presentation.releaseTitle)
                putString(KEY_ARTWORK_LOCATION, presentation.artworkLocation)
                presentation.durationMs?.let { putLong(KEY_DURATION_MS, it) }
            }
        }
}

private fun Bundle?.toPageRequest(): R16QueuePageRequest? {
    if (this == null) return R16QueuePageRequest()
    val offset =
        if (containsKey(R16QueueMediaCommands.EXTRA_OFFSET)) {
            getInt(R16QueueMediaCommands.EXTRA_OFFSET)
        } else {
            null
        }
    val limit = getInt(R16QueueMediaCommands.EXTRA_LIMIT, R16QueuePageEndpoint.DEFAULT_PAGE_SIZE)
    val expectedRevision =
        if (containsKey(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)) {
            getLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)
        } else {
            null
        }
    return runCatching {
            R16QueuePageRequest(
                offset = offset,
                limit = limit,
                expectedQueueRevision = expectedRevision,
            )
        }
        .getOrNull()
}

private fun Bundle?.toGoToRequest(): R16QueueGoToRequest? {
    val entryValue = this?.getString(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID) ?: return null
    val expectedRevision =
        if (this.containsKey(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)) {
            this.getLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)
        } else {
            null
        }
    return runCatching { R16QueueGoToRequest(QueueEntryId(entryValue), expectedRevision) }
        .getOrNull()
}

private fun Bundle?.toMoveRequest(): R16QueueMoveRequest? {
    val entryValue = this?.getString(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID) ?: return null
    val moveTop = this.getBoolean(R16QueueMediaCommands.EXTRA_MOVE_TOP, false)
    val moveBottom = this.getBoolean(R16QueueMediaCommands.EXTRA_MOVE_BOTTOM, false)
    val anchorBefore =
        this.getString(R16QueueMediaCommands.EXTRA_ANCHOR_BEFORE)?.let(::QueueEntryId)
    val anchorAfter = this.getString(R16QueueMediaCommands.EXTRA_ANCHOR_AFTER)?.let(::QueueEntryId)
    val expectedRevision =
        if (this.containsKey(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)) {
            this.getLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)
        } else {
            null
        }
    return runCatching {
            R16QueueMoveRequest(
                queueEntryId = QueueEntryId(entryValue),
                anchor = QueueAnchor(anchorBefore, anchorAfter),
                expectedQueueRevision = expectedRevision,
                moveTop = moveTop,
                moveBottom = moveBottom,
            )
        }
        .getOrNull()
}

private fun Bundle?.toRemoveRequest(): R16QueueRemoveRequest? {
    if (this == null) return null
    val ids =
        this.getStringArrayList(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_IDS)
            ?: this.getString(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID)?.let { arrayListOf(it) }
            ?: return null
    val expectedRevision =
        if (this.containsKey(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)) {
            this.getLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION)
        } else {
            null
        }
    return runCatching {
            R16QueueRemoveRequest(
                queueEntryIds = ids.map(::QueueEntryId).toSet(),
                expectedQueueRevision = expectedRevision,
            )
        }
        .getOrNull()
}
