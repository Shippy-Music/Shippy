/*
 * Copyright (c) 2026 Auxio Project
 * R16QueueFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16QueueBinding
import org.oxycblt.auxio.shippy.r16.playback.service.R16QueueMediaCommands
import org.oxycblt.auxio.shippy.r16.playback.service.R16QueuePageEndpoint
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionProjection

/** ACTIVE-only bounded queue page. Queue reads and GoTo commands cross the service boundary. */
internal class R16QueueFragment : Fragment(R.layout.fragment_r16_queue) {
    private var binding: FragmentR16QueueBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter =
        R16QueueAdapter(
            onGoTo = ::goTo,
            onMoveUp = ::moveUp,
            onMoveDown = ::moveDown,
            onMoveTop = ::moveTop,
            onMoveBottom = ::moveBottom,
            onRemove = ::removeSingle,
            onToggleSelect = ::toggleSelect,
            onLongClick = ::onLongClick,
        )
    private var selectedEntryIds: Set<String> = emptySet()
    private var requestSerial = 0L
    private var queueRevision: Long? = null
    private var currentQueueEntryId: String? = null
    private var totalCount = 0
    private var loading = false
    private var pageOffset = 0
    private var pageLimit = R16QueuePageEndpoint.MAX_PAGE_SIZE
    private var previousOffset: Int? = null
    private var nextOffset: Int? = null
    private var hasPrevious = false
    private var hasNext = false
    private var refreshCurrentPageWhenIdle = false

    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }

    private val itemTouchHelper =
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                private var dragFrom = -1
                private var dragTo = -1

                override fun isLongPressDragEnabled(): Boolean = selectedEntryIds.isEmpty()

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION)
                        return false
                    if (dragFrom == -1) dragFrom = from
                    dragTo = to
                    val current = adapter.currentList.toMutableList()
                    val item = current.removeAt(from)
                    current.add(to, item)
                    adapter.submitList(current)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    val from = dragFrom
                    val to = dragTo
                    dragFrom = -1
                    dragTo = -1
                    if (from != -1 && to != -1 && from != to) {
                        val rows = adapter.currentList
                        val movedRow = rows.getOrNull(to) ?: return
                        val without = rows.filter { it.queueEntryId != movedRow.queueEntryId }
                        val before = without.getOrNull(to - 1)?.queueEntryId
                        val after = without.getOrNull(to)?.queueEntryId
                        moveEntry(movedRow.queueEntryId, before, after)
                    }
                }
            }
        )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16QueueBinding.bind(view).also { bound ->
                bound.r16QueueEntries.adapter = adapter
                itemTouchHelper.attachToRecyclerView(bound.r16QueueEntries)
                bound.r16QueueBack.setOnClickListener { handleBackNavigation() }
                bound.r16QueuePrevious.setOnClickListener { requestPreviousPage() }
                bound.r16QueueNext.setOnClickListener { requestNextPage() }
                bound.r16QueueDeleteSelected.setOnClickListener { showDeleteSelectedDialog() }
                bound.r16QueueClearSelection.setOnClickListener { clearSelection() }
            }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        renderLoading()
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = true
        if (mediaBrowser != null) return
        mediaBrowser =
            MediaBrowserCompat(
                    requireContext(),
                    ComponentName(requireContext(), AuxioService::class.java),
                    browserConnection,
                    null,
                )
                .also(MediaBrowserCompat::connect)
    }

    override fun onPause() {
        backCallback.isEnabled = false
        requestSerial++
        loading = false
        refreshCurrentPageWhenIdle = false
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.r16QueueEntries?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                val controller = MediaControllerCompat(requireContext(), browser.sessionToken)
                mediaController = controller
                currentQueueEntryId = currentQueueEntryId(controller.metadata)
                controller.registerCallback(controllerCallback)
                requestPage()
            }

            override fun onConnectionSuspended() = disconnectController()

            override fun onConnectionFailed() = disconnectController()
        }

    private val controllerCallback =
        object : MediaControllerCompat.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                updateCurrentQueueEntry(metadata)
            }
        }

    private fun requestPage(offset: Int? = null, expectedRevision: Long? = null) {
        val controller = mediaController ?: return
        val serial = ++requestSerial
        loading = true
        renderLoading()
        val extras =
            bundleOf(R16QueueMediaCommands.EXTRA_LIMIT to R16QueuePageEndpoint.MAX_PAGE_SIZE)
        offset?.let { extras.putInt(R16QueueMediaCommands.EXTRA_OFFSET, it) }
        expectedRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.PAGE,
            extras,
            receiver(serial) { resultCode, resultData ->
                handlePageResult(serial, resultCode, resultData)
            },
        )
    }

    private fun handlePageResult(serial: Long, resultCode: Int, data: Bundle?) {
        if (serial != requestSerial || binding == null) return
        loading = false
        val revision =
            data?.getLong(R16QueueMediaCommands.KEY_QUEUE_REVISION) ?: return renderError()
        if (resultCode == R16QueueMediaCommands.RESULT_STALE_QUEUE_REVISION) {
            queueRevision = revision
            requestPage()
            return
        }
        if (resultCode != R16QueueMediaCommands.RESULT_OK || data == null) {
            renderError()
            return
        }
        queueRevision = revision
        pageOffset = data.getInt(R16QueueMediaCommands.KEY_OFFSET, 0)
        pageLimit =
            data
                .getInt(R16QueueMediaCommands.KEY_LIMIT, R16QueuePageEndpoint.MAX_PAGE_SIZE)
                .coerceAtLeast(1)
        totalCount = data.getInt(R16QueueMediaCommands.KEY_TOTAL_COUNT, 0)
        previousOffset = data.optionalOffset(R16QueueMediaCommands.KEY_PREVIOUS_OFFSET)
        nextOffset = data.optionalOffset(R16QueueMediaCommands.KEY_NEXT_OFFSET)
        hasPrevious =
            data.getBoolean(R16QueueMediaCommands.KEY_HAS_PREVIOUS, previousOffset != null)
        hasNext = data.getBoolean(R16QueueMediaCommands.KEY_HAS_NEXT, nextOffset != null)
        val rows = decodeRows(data) ?: return renderError()
        if (totalCount == 0 && rows.isNotEmpty()) totalCount = rows.size
        adapter.submitList(rows)
        renderLoaded(rows.size)
        refreshCurrentPageIfNeeded()
    }

    private fun requestPreviousPage() {
        if (loading || !hasPrevious) return
        val offset = previousOffset ?: (pageOffset - pageLimit).coerceAtLeast(0)
        requestPage(offset = offset, expectedRevision = queueRevision)
    }

    private fun requestNextPage() {
        if (loading || !hasNext) return
        val offset = nextOffset ?: (pageOffset + pageLimit)
        requestPage(offset = offset, expectedRevision = queueRevision)
    }

    private fun decodeRows(data: Bundle): List<R16QueueRow>? {
        val itemBundles =
            runCatching { data.getParcelableArrayList<Bundle>(R16QueueMediaCommands.KEY_ITEMS) }
                .getOrNull() ?: return emptyList()
        return itemBundles.mapNotNull { item ->
            val queueEntryId =
                item
                    .getString(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID)
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val recordingId = item.getString(R16QueueMediaCommands.KEY_RECORDING_ID).orEmpty()
            val title =
                item.getString(R16QueueMediaCommands.KEY_TITLE)?.takeIf(String::isNotBlank)
                    ?: recordingId.takeIf(String::isNotBlank)
                    ?: getString(R.string.r16_queue_unknown_track)
            val artist = item.getString(R16QueueMediaCommands.KEY_ARTIST).orEmpty()
            val release = item.getString(R16QueueMediaCommands.KEY_RELEASE_TITLE).orEmpty()
            R16QueueRow(
                queueEntryId = queueEntryId,
                title = title,
                subtitle = listOf(artist, release).filter(String::isNotBlank).joinToString(" - "),
                artworkLocation = item.getString(R16QueueMediaCommands.KEY_ARTWORK_LOCATION),
                current = queueEntryId == currentQueueEntryId,
            )
        }
    }

    private fun goTo(queueEntryId: String) {
        val controller = mediaController ?: return
        val serial = ++requestSerial
        val extras = bundleOf(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID to queueEntryId)
        queueRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.GO_TO,
            extras,
            receiver(serial) { resultCode, resultData ->
                handleGoToResult(serial, resultCode, resultData)
            },
        )
    }

    private fun handleGoToResult(serial: Long, resultCode: Int, data: Bundle?) {
        if (serial != requestSerial || binding == null) return
        val revision =
            data?.getLong(R16QueueMediaCommands.KEY_QUEUE_REVISION) ?: return renderError()
        if (
            resultCode == R16QueueMediaCommands.RESULT_STALE_QUEUE_REVISION ||
                resultCode == R16QueueMediaCommands.RESULT_ENTRY_NOT_FOUND
        ) {
            queueRevision = revision
            requestPage()
            return
        }
        if (resultCode != R16QueueMediaCommands.RESULT_OK || data == null) {
            renderError()
            return
        }
        queueRevision = revision
        val metadataCurrent = currentQueueEntryId(mediaController?.metadata)
        if (metadataCurrent != null) {
            updateCurrentQueueEntry(metadataCurrent)
        } else {
            updateCurrentQueueEntry(
                data.getString(R16QueueMediaCommands.KEY_CURRENT_QUEUE_ENTRY_ID)
            )
        }
    }

    private fun handleBackNavigation() {
        if (selectedEntryIds.isNotEmpty()) {
            clearSelection()
            return
        }
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun moveEntry(queueEntryId: String, beforeId: String?, afterId: String?) {
        val controller = mediaController ?: return
        val serial = ++requestSerial
        val extras = bundleOf(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID to queueEntryId)
        beforeId?.let { extras.putString(R16QueueMediaCommands.EXTRA_ANCHOR_BEFORE, it) }
        afterId?.let { extras.putString(R16QueueMediaCommands.EXTRA_ANCHOR_AFTER, it) }
        queueRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.MOVE,
            extras,
            receiver(serial) { resultCode, resultData ->
                handleMutationResult(serial, resultCode, resultData)
            },
        )
    }

    private fun removeEntries(queueEntryIds: Set<String>) {
        if (queueEntryIds.isEmpty()) return
        val controller = mediaController ?: return
        val serial = ++requestSerial
        val extras =
            bundleOf(R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_IDS to ArrayList(queueEntryIds))
        queueRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.REMOVE,
            extras,
            receiver(serial) { resultCode, resultData ->
                handleMutationResult(serial, resultCode, resultData)
            },
        )
    }

    private fun handleMutationResult(serial: Long, resultCode: Int, data: Bundle?) {
        if (serial != requestSerial || binding == null) return
        val revision = data?.getLong(R16QueueMediaCommands.KEY_QUEUE_REVISION)
        if (revision != null) queueRevision = revision
        if (resultCode == R16QueueMediaCommands.RESULT_OK) {
            val nextCurrent = data?.getString(R16QueueMediaCommands.KEY_CURRENT_QUEUE_ENTRY_ID)
            if (nextCurrent != null) {
                currentQueueEntryId = nextCurrent
            }
            requestPage(offset = pageOffset, expectedRevision = queueRevision)
        } else {
            requestPage(offset = pageOffset)
        }
    }

    private fun moveUp(row: R16QueueRow, position: Int) {
        if (position <= 0) {
            moveTop(row)
            return
        }
        val rows = adapter.currentList
        val without = rows.filter { it.queueEntryId != row.queueEntryId }
        val targetPos = position - 1
        val before = without.getOrNull(targetPos - 1)?.queueEntryId
        val after = without.getOrNull(targetPos)?.queueEntryId
        moveEntry(row.queueEntryId, before, after)
    }

    private fun moveDown(row: R16QueueRow, position: Int) {
        val rows = adapter.currentList
        if (position >= rows.size - 1) {
            moveBottom(row)
            return
        }
        val without = rows.filter { it.queueEntryId != row.queueEntryId }
        val targetPos = position + 1
        val before = without.getOrNull(targetPos - 1)?.queueEntryId
        val after = without.getOrNull(targetPos)?.queueEntryId
        moveEntry(row.queueEntryId, before, after)
    }

    private fun moveTop(row: R16QueueRow) {
        val controller = mediaController ?: return
        val serial = ++requestSerial
        val extras =
            bundleOf(
                R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID to row.queueEntryId,
                R16QueueMediaCommands.EXTRA_MOVE_TOP to true,
            )
        queueRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.MOVE,
            extras,
            receiver(serial) { resultCode, resultData ->
                handleMutationResult(serial, resultCode, resultData)
            },
        )
    }

    private fun moveBottom(row: R16QueueRow) {
        val controller = mediaController ?: return
        val serial = ++requestSerial
        val extras =
            bundleOf(
                R16QueueMediaCommands.EXTRA_QUEUE_ENTRY_ID to row.queueEntryId,
                R16QueueMediaCommands.EXTRA_MOVE_BOTTOM to true,
            )
        queueRevision?.let { extras.putLong(R16QueueMediaCommands.EXTRA_QUEUE_REVISION, it) }
        controller.sendCommand(
            R16QueueMediaCommands.MOVE,
            extras,
            receiver(serial) { resultCode, resultData ->
                handleMutationResult(serial, resultCode, resultData)
            },
        )
    }

    private fun removeSingle(row: R16QueueRow) {
        removeEntries(setOf(row.queueEntryId))
    }

    private fun toggleSelect(row: R16QueueRow) {
        selectedEntryIds =
            if (row.queueEntryId in selectedEntryIds) selectedEntryIds - row.queueEntryId
            else selectedEntryIds + row.queueEntryId
        renderSelection()
    }

    private fun onLongClick(row: R16QueueRow): Boolean {
        toggleSelect(row)
        return true
    }

    private fun clearSelection() {
        selectedEntryIds = emptySet()
        renderSelection()
    }

    private fun renderSelection() {
        adapter.selectedEntryIds = selectedEntryIds
        binding?.apply {
            if (selectedEntryIds.isNotEmpty()) {
                r16QueueSelectionBar.isVisible = true
                r16QueueSelectionCount.text =
                    getString(R.string.r16_queue_selection_count, selectedEntryIds.size)
            } else {
                r16QueueSelectionBar.isVisible = false
            }
        }
    }

    private fun showDeleteSelectedDialog() {
        val count = selectedEntryIds.size
        if (count == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_remove)
            .setMessage(getString(R.string.r16_queue_delete_selected_confirm, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_remove) { _, _ ->
                val toDelete = selectedEntryIds
                clearSelection()
                removeEntries(toDelete)
            }
            .show()
    }

    private fun receiver(serial: Long, onResult: (Int, Bundle?) -> Unit): ResultReceiver =
        object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (serial == requestSerial) onResult(resultCode, resultData)
            }
        }

    private fun renderLoading() {
        binding?.apply {
            r16QueueState.isVisible = true
            r16QueueState.setText(R.string.r16_queue_loading)
            renderPagingControls()
        }
    }

    private fun renderLoaded(visibleCount: Int) {
        binding?.apply {
            r16QueueState.isVisible = true
            r16QueueState.text =
                if (totalCount == 0) {
                    getString(R.string.r16_queue_empty)
                } else {
                    getString(R.string.r16_queue_count, visibleCount, totalCount)
                }
            renderPagingControls()
        }
    }

    private fun renderError() {
        hasPrevious = false
        hasNext = false
        previousOffset = null
        nextOffset = null
        binding?.apply {
            r16QueueState.isVisible = true
            r16QueueState.setText(R.string.r16_queue_unavailable)
            renderPagingControls()
        }
    }

    private fun renderPagingControls() {
        binding?.apply {
            r16QueuePrevious.isEnabled = !loading && mediaController != null && hasPrevious
            r16QueueNext.isEnabled = !loading && mediaController != null && hasNext
        }
    }

    private fun disconnectController() {
        requestSerial++
        loading = false
        refreshCurrentPageWhenIdle = false
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        renderError()
    }

    /** Keep the visible page in step with the sole MediaSession current-occurrence identity. */
    private fun updateCurrentQueueEntry(metadata: MediaMetadataCompat?) {
        updateCurrentQueueEntry(currentQueueEntryId(metadata))
    }

    private fun updateCurrentQueueEntry(nextQueueEntryId: String?) {
        if (binding == null || mediaController == null || nextQueueEntryId == currentQueueEntryId)
            return
        currentQueueEntryId = nextQueueEntryId
        val rows = adapter.currentList
        if (nextQueueEntryId == null || rows.any { it.queueEntryId == nextQueueEntryId }) {
            refreshCurrentHighlight()
            refreshCurrentPageWhenIdle = false
        } else if (loading) {
            refreshCurrentPageWhenIdle = true
        } else {
            requestPage()
        }
    }

    private fun refreshCurrentHighlight() {
        adapter.submitList(
            adapter.currentList.map { row ->
                row.copy(current = row.queueEntryId == currentQueueEntryId)
            }
        )
    }

    private fun refreshCurrentPageIfNeeded() {
        if (!refreshCurrentPageWhenIdle || loading || currentQueueEntryId == null) return
        if (adapter.currentList.any { it.queueEntryId == currentQueueEntryId }) {
            refreshCurrentPageWhenIdle = false
            refreshCurrentHighlight()
        } else {
            refreshCurrentPageWhenIdle = false
            requestPage()
        }
    }

    private fun currentQueueEntryId(metadata: MediaMetadataCompat?): String? =
        metadata
            ?.getString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID)
            ?.takeIf(String::isNotBlank)

    private fun Bundle.optionalOffset(key: String): Int? =
        takeIf { containsKey(key) }?.getInt(key)?.takeIf { it >= 0 }
}
