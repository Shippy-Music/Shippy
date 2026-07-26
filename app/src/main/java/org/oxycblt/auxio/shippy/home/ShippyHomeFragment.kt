/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyHomeFragment.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.home

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentShippyHomeBinding
import org.oxycblt.auxio.home.list.LibrarySystemCollectionAdapter
import org.oxycblt.auxio.home.list.ShippyPlaylistProjectionAdapter
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackDisplayItem
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.ui.CrewViewModel
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.library.LibraryCollectionsState
import org.oxycblt.auxio.shippy.library.LibraryCollectionsViewModel
import org.oxycblt.auxio.shippy.library.systemRows
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

@AndroidEntryPoint
class ShippyHomeFragment : ViewBindingFragment<FragmentShippyHomeBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val crewModel: CrewViewModel by viewModels()
    private val collectionsModel: LibraryCollectionsViewModel by viewModels()
    private val systemCollectionAdapter =
        LibrarySystemCollectionAdapter { row ->
            if (row.kind == SystemCollectionKind.LOCAL) {
                findNavController().navigate(R.id.library_fragment)
            } else {
                openCollection(LibraryCollection.System(row.kind).id)
            }
        }
    private val pinnedPlaylistAdapter =
        ShippyPlaylistProjectionAdapter { playlist -> openCollection(playlist.id) }
    private val libraryShortcutsAdapter = ConcatAdapter(systemCollectionAdapter, pinnedPlaylistAdapter)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentShippyHomeBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentShippyHomeBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeCurrent.setOnClickListener { playbackModel.openPlayback() }
        binding.homeCrew.setOnClickListener {
            findNavController().navigate(R.id.crew_fragment)
        }
        binding.homeLibraryShortcuts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = libraryShortcutsAdapter
            isNestedScrollingEnabled = false
        }

        collectImmediately(playbackModel.displayItem, ::updateCurrentItem)
        collectImmediately(musicModel.statistics, ::updateLibrarySummary)
        collectImmediately(crewModel.state, ::updateCrew)
        collectImmediately(
            collectionsModel.state,
            musicModel.statistics,
            musicModel.indexingState,
            ::updateLibraryShortcuts,
        )
    }

    override fun onDestroyBinding(binding: FragmentShippyHomeBinding) {
        binding.homeLibraryShortcuts.adapter = null
        super.onDestroyBinding(binding)
    }

    private fun updateCurrentItem(item: PlaybackDisplayItem?) {
        val binding = requireBinding()
        binding.homeCurrent.isVisible = item != null
        binding.homeNoCurrent.isVisible = item == null
        if (item == null) return

        val context = requireContext()
        val track = item.queueItem.track
        val localSong = item.localSong
        if (localSong != null) {
            binding.homeCurrentCover.bind(localSong)
            binding.homeCurrentTitle.text = localSong.name.resolve(context)
            binding.homeCurrentArtist.text = localSong.artists.resolveNames(context)
        } else {
            binding.homeCurrentCover.bindArtwork(track.artwork, track.album ?: track.title)
            binding.homeCurrentTitle.text = track.title
            binding.homeCurrentArtist.text = track.artists.joinToString(", ")
        }
    }

    private fun updateLibrarySummary(statistics: MusicViewModel.Statistics?) {
        requireBinding().homeLibrarySummary.text =
            getString(
                R.string.fmt_shippy_library_summary,
                statistics?.songs ?: 0,
                statistics?.albums ?: 0,
                statistics?.artists ?: 0,
            )
    }

    private fun updateCrew(state: ActiveCrewRuntimeState) {
        val binding = requireBinding()
        binding.homeCrew.isVisible =
            state is ActiveCrewRuntimeState.Starting ||
                state is ActiveCrewRuntimeState.Active ||
                state is ActiveCrewRuntimeState.Ending
        when (state) {
            is ActiveCrewRuntimeState.Starting ->
                with(binding) {
                    homeCrewStatus.setText(
                        if (state.mode == ActiveCrewMode.HOST) {
                            R.string.lbl_starting_crew
                        } else {
                            R.string.lbl_joining_crew
                        }
                    )
                    homeCrewMembers.text = ""
                }
            is ActiveCrewRuntimeState.Active -> renderCrewMembers(state.presentation.crewState.members.size)
            is ActiveCrewRuntimeState.Ending -> {
                binding.homeCrewStatus.setText(R.string.lbl_ending_crew)
                binding.homeCrewMembers.text = resources.getQuantityString(
                    R.plurals.plr_crew_members,
                    state.presentation.crewState.members.size,
                    state.presentation.crewState.members.size,
                )
            }
            else -> Unit
        }
    }

    private fun renderCrewMembers(memberCount: Int) {
        requireBinding().homeCrewMembers.text = resources.getQuantityString(
            R.plurals.plr_crew_members,
            memberCount,
            memberCount,
        )
        requireBinding().homeCrewStatus.setText(R.string.lng_crew_active)
    }

    private fun updateLibraryShortcuts(
        state: LibraryCollectionsState,
        statistics: MusicViewModel.Statistics?,
        indexingState: IndexingState?,
    ) {
        systemCollectionAdapter.submitList(
            state.systemRows(
                localSongCount = statistics?.songs ?: 0,
                isLocalIndexing = indexingState is IndexingState.Indexing,
            )
        )
        pinnedPlaylistAdapter.update(state.userPlaylists.filter { it.isPinned })
    }

    private fun openCollection(collectionId: LibraryCollectionId) {
        findNavController().navigate(
            R.id.shippy_collection_detail_fragment,
            bundleOf("collectionId" to collectionId.value),
        )
    }
}
