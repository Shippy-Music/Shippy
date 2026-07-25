/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyCollectionDetailFragment.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.library.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.library.CollectionDetailMessage
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailState
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailViewModel
import org.oxycblt.auxio.shippy.library.messageKind
import org.oxycblt.auxio.ui.AuxioToolbar

/**
 * Small, truthful detail route for Shippy-owned collection relationships.
 *
 * This deliberately does not render raw track IDs as songs. Local is routed back to Auxio's
 * mature local Songs surface; a future canonical-metadata store can replace this message with a
 * proper mixed-source track list without changing navigation or management behavior.
 */
@AndroidEntryPoint
class ShippyCollectionDetailFragment : Fragment(R.layout.fragment_shippy_collection_detail) {
    private val model: ShippyCollectionDetailViewModel by viewModels()

    private lateinit var collectionId: LibraryCollectionId
    private lateinit var toolbar: AuxioToolbar
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private val tracksAdapter = ShippyCollectionTrackAdapter { model.play(collectionId, it) }
    private var currentState: ShippyCollectionDetailState? = null
    private var playlistMenuInflated = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectionId = LibraryCollectionId(requireArguments().getString(ARG_COLLECTION_ID).orEmpty())
        toolbar = view.findViewById(R.id.shippy_collection_toolbar)
        title = view.findViewById(R.id.shippy_collection_title)
        count = view.findViewById(R.id.shippy_collection_count)
        progress = view.findViewById(R.id.shippy_collection_progress)
        message = view.findViewById(R.id.shippy_collection_message)
        view.findViewById<RecyclerView>(R.id.shippy_collection_tracks).adapter = tracksAdapter

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        toolbar.setOnMenuItemClickListener { item ->
            val playlist = (currentState as? ShippyCollectionDetailState.Playlist)?.playlist
                ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog(playlist.id, playlist.displayName)
                    true
                }
                R.id.action_shippy_pin -> {
                    model.setPinned(playlist.id, !playlist.isPinned)
                    true
                }
                R.id.action_delete -> {
                    showDeleteDialog(playlist.id, playlist.displayName)
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.observe(collectionId).collect(::render)
            }
        }
    }

    private fun render(state: ShippyCollectionDetailState) {
        currentState = state
        progress.isGone = true
        toolbar.title = state.title
        title.text = state.title
        val totalTrackCount = state.rows.size + state.unresolvedTrackCount
        count.text =
            resources.getQuantityString(
                R.plurals.fmt_song_count,
                totalTrackCount,
                totalTrackCount,
            )
        message.setText(
            when (state.messageKind()) {
                CollectionDetailMessage.DELETED -> R.string.lng_collection_deleted
                CollectionDetailMessage.EMPTY -> R.string.lng_collection_empty
                CollectionDetailMessage.METADATA_PENDING -> R.string.lng_collection_metadata_pending
            }
        )
        tracksAdapter.submitList(state.rows)
        message.isGone = state.rows.isNotEmpty() && state.unresolvedTrackCount == 0
        if (state is ShippyCollectionDetailState.Playlist) {
            ensurePlaylistMenu(state)
        }
    }

    private fun ensurePlaylistMenu(state: ShippyCollectionDetailState.Playlist) {
        if (!playlistMenuInflated) {
            toolbar.inflateMenu(R.menu.shippy_collection_detail)
            playlistMenuInflated = true
        }
        toolbar.menu.findItem(R.id.action_shippy_pin)?.title =
            getString(if (state.playlist.isPinned) R.string.lbl_unpin else R.string.lbl_pin)
    }

    private fun showRenameDialog(playlistId: LibraryCollectionId, currentName: String) {
        val input =
            EditText(requireContext()).apply {
                setText(currentName)
                selectAll()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_rename_playlist)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_rename) { _, _ -> model.rename(playlistId, input.text.toString()) }
            .show()
    }

    private fun showDeleteDialog(playlistId: LibraryCollectionId, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.lbl_delete))
            .setMessage(getString(R.string.lng_delete_shippy_playlist, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                model.delete(playlistId)
                findNavController().navigateUp()
            }
            .show()
    }

    companion object {
        const val ARG_COLLECTION_ID = "collectionId"
    }
}
