/*
 * Copyright (c) 2022 Auxio Project
 * QueueViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.playback.PlaybackDisplayItem
import org.oxycblt.auxio.playback.PlaybackDisplayMapper
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.playback.CREW_START_LEAD_MS
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.MusicParent
import timber.log.Timber as L

/**
 * A [ViewModel] that manages the current queue state and allows navigation through the queue.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class QueueViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackDisplayMapper: PlaybackDisplayMapper,
    private val activeCrewRuntime: ActiveCrewRuntime,
) : ViewModel(), PlaybackStateManager.Listener {
    private var queueMappingJob: Job? = null

    private val _queue = MutableStateFlow(listOf<PlaybackDisplayItem>())
    /** The current queue. */
    val queue: StateFlow<List<PlaybackDisplayItem>> = _queue
    private val _queueInstructions = MutableEvent<UpdateInstructions>()
    /** Instructions for how to update [queue] in the UI. */
    val queueInstructions: Event<UpdateInstructions> = _queueInstructions
    private val _scrollTo = MutableEvent<Int>()
    /** Controls whether the queue should be force-scrolled to a particular location. */
    val scrollTo: Event<Int>
        get() = _scrollTo

    private val _index = MutableStateFlow(playbackManager.index)
    /** The index of the currently playing song in the queue. */
    val index: StateFlow<Int>
        get() = _index

    init {
        playbackManager.addListener(this)
        viewModelScope.launch {
            activeCrewRuntime.state.collect { runtime ->
                val active = runtime as? ActiveCrewRuntimeState.Active
                if (active == null) {
                    updateQueueAsync(
                        playbackManager.resolvedQueue,
                        playbackManager.index,
                        UpdateInstructions.Replace(0),
                    )
                } else {
                    val state = active.presentation.crewState
                    val index =
                        state.queue.indexOfFirst { it.id == state.playback.currentQueueItemId }
                    updateCrewQueueAsync(
                        state.queue,
                        index,
                        state.members.associate { it.id.value to it.displayName },
                    )
                }
            }
        }
    }

    override fun onIndexMoved(index: Int) {
        L.d("Index moved, synchronizing and scrolling to new position")
        _scrollTo.put(index)
        _index.value = index
    }

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: QueueChange,
    ) {
        if (activeCrew() != null) return
        // Queue changed trivially due to item mo -> Diff queue, stay at current index.
        L.d("Updating queue display")
        updateQueueAsync(queue, index, change.instructions)
        if (change.type != QueueChange.Type.MAPPING) {
            // Index changed, make sure it remains updated without actually scrolling to it.
            L.d("Index changed with queue, synchronizing new position")
            _index.value = index
        }
    }

    override fun onCanonicalQueueReordered(
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) {
        if (activeCrew() != null) return
        // Queue changed completely -> Replace queue, update index
        L.d("Queue changed completely, replacing queue and position")
        _scrollTo.put(index)
        _index.value = index
        updateQueueAsync(queue, index, UpdateInstructions.Replace(0))
    }

    override fun onCanonicalNewPlayback(
        parent: MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) {
        if (activeCrew() != null) return
        // Entirely new queue -> Replace queue, update index
        L.d("New playback, replacing queue and position")
        _scrollTo.put(index)
        _index.value = index
        updateQueueAsync(queue, index, UpdateInstructions.Replace(0))
    }

    private fun updateQueueAsync(
        queue: List<ResolvedQueueItem>,
        index: Int,
        instructions: UpdateInstructions,
    ) {
        queueMappingJob?.cancel()
        queueMappingJob =
            viewModelScope.launch {
                val displayQueue =
                    withContext(Dispatchers.Default) { queue.map(playbackDisplayMapper::map) }
                currentCoroutineContext().ensureActive()
                _queueInstructions.put(instructions)
                _queue.value = displayQueue
                _index.value = index
            }
    }

    private fun updateCrewQueueAsync(
        queue: List<org.oxycblt.auxio.shippy.domain.QueueItem>,
        index: Int,
        memberNames: Map<String, String>,
    ) {
        queueMappingJob?.cancel()
        queueMappingJob =
            viewModelScope.launch {
                val displayQueue =
                    withContext(Dispatchers.Default) {
                        queue.map { item ->
                            playbackDisplayMapper
                                .map(item)
                                .copy(
                                    contributorDisplayName =
                                        item.contributorId?.let(memberNames::get)
                                )
                        }
                    }
                currentCoroutineContext().ensureActive()
                _queueInstructions.put(UpdateInstructions.Replace(0))
                _queue.value = displayQueue
                _index.value = index
            }
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager.removeListener(this)
    }

    /**
     * Start playing the the queue item at the given index.
     *
     * @param adapterIndex The index of the queue item to play. Does nothing if the index is out of
     *   range.
     */
    fun goto(itemId: QueueItemId) {
        activeCrew()?.let { active ->
            val state = active.presentation.crewState
            if (state.queue.none { it.id == itemId }) return
            viewModelScope.launch {
                activeCrewRuntime.submit(CrewAction.CurrentItemChanged(itemId))
                val localNow = System.currentTimeMillis().coerceAtLeast(0)
                val now =
                    active.presentation.clockEstimate.value
                        ?.clientToCoordinator(localNow)
                        ?.coerceAtLeast(0) ?: localNow
                activeCrewRuntime.submit(
                    if (state.playback.mode == CrewPlaybackMode.PLAYING)
                        CrewAction.Play(0, now + CREW_START_LEAD_MS)
                    else CrewAction.Pause(0, now)
                )
            }
            return
        }
        val currentIndex = playbackManager.queueItems.indexOfFirst { it.id == itemId }
        if (currentIndex < 0) return
        L.d("Going to queue item $itemId at $currentIndex")
        playbackManager.goto(currentIndex)
    }

    fun gotoAdapterIndex(adapterIndex: Int) {
        queue.value.getOrNull(adapterIndex)?.queueItem?.id?.let(::goto)
    }

    /**
     * Remove a queue item at the given index.
     *
     * @param adapterIndex The index of the queue item to play. Does nothing if the index is out of
     *   range.
     */
    fun removeQueueDataItem(itemId: QueueItemId) {
        activeCrew()?.let { active ->
            if (active.presentation.crewState.queue.none { it.id == itemId }) return
            viewModelScope.launch { activeCrewRuntime.submit(CrewAction.QueueItemRemoved(itemId)) }
            return
        }
        val currentIndex = playbackManager.queueItems.indexOfFirst { it.id == itemId }
        if (currentIndex < 0) return
        L.d("Removing queue item $itemId at $currentIndex")
        playbackManager.removeQueueItem(currentIndex)
    }

    /**
     * Move a queue item from one index to another index.
     *
     * @param adapterFrom The index of the queue item to move.
     * @param adapterTo The destination index for the queue item.
     * @return true if the items were moved, false otherwise.
     */
    fun moveQueueDataItem(
        itemId: QueueItemId,
        beforeId: QueueItemId?,
        afterId: QueueItemId?,
    ): Boolean {
        activeCrew()?.let { active ->
            val current = active.presentation.crewState.queue
            val withoutMoved = current.filterNot { it.id == itemId }
            if (withoutMoved.size == current.size) return false
            val destination =
                beforeId?.let { id -> withoutMoved.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                    ?: afterId?.let { id ->
                        withoutMoved.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
                    }
                    ?: return false
            viewModelScope.launch {
                activeCrewRuntime.submit(
                    CrewAction.QueueItemMoved(
                        itemId = itemId,
                        newIndex = destination,
                        beforeItemId = beforeId,
                        afterItemId = if (beforeId == null) afterId else null,
                    )
                )
            }
            return true
        }
        val current = playbackManager.queueItems
        val from = current.indexOfFirst { it.id == itemId }
        if (from < 0) return false
        val before = beforeId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
        val after = afterId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
        val to =
            when {
                before >= 0 -> if (from < before) before - 1 else before
                after >= 0 -> if (from < after) after else after + 1
                else -> return false
            }.coerceIn(current.indices)
        if (from == to) return true
        L.d("Moving queue item $itemId from $from to $to")
        playbackManager.moveQueueItem(from, to)
        return true
    }

    private fun activeCrew(): ActiveCrewRuntimeState.Active? =
        activeCrewRuntime.state.value as? ActiveCrewRuntimeState.Active
}
