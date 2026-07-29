/*
 * Copyright (c) 2021 Auxio Project
 * QueueFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.databinding.FragmentQueueBinding
import org.oxycblt.auxio.list.EditClickListListener
import org.oxycblt.auxio.playback.PlaybackDisplayItem
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] that displays an editable queue.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class QueueFragment :
    ViewBindingFragment<FragmentQueueBinding>(), EditClickListListener<PlaybackDisplayItem> {
    private val queueModel: QueueViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val queueAdapter = QueueAdapter(this)
    private var touchHelper: ItemTouchHelper? = null
    private var scrollToCurrentPending = false

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentQueueBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentQueueBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.queueRecycler.apply {
            adapter = queueAdapter
            touchHelper =
                ItemTouchHelper(QueueDragCallback(queueModel, queueAdapter)).also {
                    it.attachToRecyclerView(this)
                }
        }

        // QueueBottomSheetBehavior intentionally rewrites the inset it sends to descendants with
        // its logical playback-bar offset. Queue rows only need the physical bottom system/gesture
        // inset, otherwise the inherited offset becomes extra RecyclerView padding.
        val initialQueuePaddingBottom = binding.queueRecycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.queueRecycler) { recycler, _ ->
            val rootInsets = ViewCompat.getRootWindowInsets(recycler)
            val physicalBottomInset =
                rootInsets?.let {
                    max(
                        it.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                        it.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
                    )
                } ?: 0
            recycler.updatePadding(bottom = initialQueuePaddingBottom + physicalBottomInset)
            WindowInsetsCompat.CONSUMED
        }
        binding.queueRecycler.requestApplyInsets()

        // Sometimes the scroll can change without the listener being updated, so we also
        // check for relayout events.
        binding.queueRecycler.apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDivider() }
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        updateDivider()
                    }
                }
            )
        }

        // --- VIEWMODEL SETUP ----
        collectImmediately(
            queueModel.queue,
            queueModel.index,
            playbackModel.isPlaying,
            ::updateQueue,
        )
    }

    override fun onDestroyBinding(binding: FragmentQueueBinding) {
        super.onDestroyBinding(binding)
        touchHelper = null
        binding.queueRecycler.adapter = null
        // Avoid possible race conditions that could cause a bad instruction to be consumed
        // during list initialization and crash the app. Could happen if the user is fast enough.
        queueModel.queueInstructions.consume()
    }

    override fun onClick(item: PlaybackDisplayItem, viewHolder: RecyclerView.ViewHolder) {
        queueModel.goto(item.queueItem.id)
    }

    override fun onPickUp(viewHolder: RecyclerView.ViewHolder) {
        requireNotNull(touchHelper) { "ItemTouchHelper was not available" }.startDrag(viewHolder)
    }

    /**
     * Position the active queue item near the upper third of the sheet so it is immediately
     * recognizable while preserving useful context for upcoming songs.
     */
    fun scrollToCurrent() {
        scrollToCurrentPending = true
        scrollToCurrentIfReady()
    }

    private fun scrollToCurrentIfReady() {
        val binding = binding ?: return
        val current = queueModel.index.value
        if (current !in queueAdapter.currentList.indices) return

        scrollToCurrentPending = false
        binding.queueRecycler.post {
            val recycler = binding.queueRecycler
            val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return@post
            val offset = (recycler.height * CURRENT_ITEM_OFFSET_RATIO).toInt()
            layoutManager.scrollToPositionWithOffset(current, offset)
        }
    }

    private fun updateDivider() {
        val binding = requireBinding()
        binding.queueDivider.isInvisible =
            (binding.queueRecycler.layoutManager as LinearLayoutManager)
                .findFirstCompletelyVisibleItemPosition() < 1
    }

    private fun updateQueue(queue: List<PlaybackDisplayItem>, index: Int, isPlaying: Boolean) {
        val binding = requireBinding()

        val requested = queueModel.queueInstructions.consume()
        val instructions =
            reconcileQueueUpdate(
                current = queueAdapter.currentList.map { it.queueItem.id },
                next = queue.map { it.queueItem.id },
                requested = requested,
            )
        queueAdapter.update(queue, instructions)
        queueAdapter.setPosition(index, isPlaying)
        if (scrollToCurrentPending) {
            scrollToCurrentIfReady()
        }

        // If requested, scroll to a new item (occurs when the index moves)
        val scrollTo = queueModel.scrollTo.consume()
        if (scrollTo != null) {
            val lmm = binding.queueRecycler.layoutManager as LinearLayoutManager
            val start = lmm.findFirstCompletelyVisibleItemPosition()
            val end = lmm.findLastCompletelyVisibleItemPosition()
            val notInitialized =
                start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION
            // When we scroll, we want to scroll to the almost-top so the user can see
            // future songs instead of past songs. The way we have to do this however is
            // dependent on where we have to scroll to get to the currently playing song.
            if (notInitialized || scrollTo < start) {
                // We need to scroll upwards, or initialize the scroll, no need to offset
                L.d("Not scrolling downwards, no offset needed")
                binding.queueRecycler.scrollToPosition(scrollTo)
            } else if (scrollTo > end) {
                // We need to scroll downwards, we need to offset by a screen of songs.
                // This does have some error due to how many completely visible items on-screen
                // can vary. This is considered okay.
                val offset = scrollTo + (end - start)
                L.d("Scrolling downwards, offsetting by $offset")
                binding.queueRecycler.scrollToPosition(min(queue.lastIndex, offset))
            }
        }
    }

    private companion object {
        const val CURRENT_ITEM_OFFSET_RATIO = 0.28f
    }
}
