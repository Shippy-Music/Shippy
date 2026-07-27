/*
 * Copyright (c) 2026 Auxio Project
 * ShippyHomeFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentShippyHomeBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.list.LibrarySystemCollectionAdapter
import org.oxycblt.auxio.home.list.SavedProviderEntityAdapter
import org.oxycblt.auxio.home.list.ShippyPlaylistProjectionAdapter
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackDisplayItem
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.search.SearchFragment
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.ui.CrewViewModel
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.library.LibraryCollectionsState
import org.oxycblt.auxio.shippy.library.LibraryCollectionsViewModel
import org.oxycblt.auxio.shippy.library.systemRows
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.provider.ui.ProviderEntityDetailFragment
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.applyBottomContentInset
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

@AndroidEntryPoint
class ShippyHomeFragment : ViewBindingFragment<FragmentShippyHomeBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val crewModel: CrewViewModel by viewModels()
    private val collectionsModel: LibraryCollectionsViewModel by viewModels()
    private val continuationModel: HomeContinuationViewModel by viewModels()
    private val lastFmModel: LastFmHomeViewModel by viewModels()
    private val lastFmTrackAdapter = HomeTrackAdapter { row ->
        openSearch("${row.subtitle} ${row.title}")
    }
    private val recentlyPlayedAdapter = HomeTrackAdapter { row ->
        openSearch("${row.subtitle} ${row.title}")
    }
    private val recentDownloadsAdapter = HomeTrackAdapter { row ->
        continuationModel.state.value.recentDownloads
            .firstOrNull { it.job.id.value == row.key }
            ?.let(continuationModel::playDownload)
    }
    private val systemCollectionAdapter = LibrarySystemCollectionAdapter { row ->
        openCollection(LibraryCollection.System(row.kind).id)
    }
    private val pinnedPlaylistAdapter = ShippyPlaylistProjectionAdapter { playlist ->
        openCollection(playlist.id)
    }
    private val pinnedProviderAdapter = SavedProviderEntityAdapter { saved ->
        openProviderEntity(saved)
    }
    private val libraryShortcutsAdapter =
        ConcatAdapter(systemCollectionAdapter, pinnedPlaylistAdapter, pinnedProviderAdapter)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentShippyHomeBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentShippyHomeBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeScroll.applyBottomContentInset()
        binding.homeToolbar.setOnMenuItemClickListener(::onToolbarItemSelected)
        binding.homeCurrent.setOnClickListener { playbackModel.openPlayback() }
        binding.homeCrew.setOnClickListener { findNavController().navigate(R.id.crew_fragment) }
        binding.homeLibraryShortcuts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = libraryShortcutsAdapter
            isNestedScrollingEnabled = false
        }
        binding.homeRecentlyPlayedTracks.bindHomeTracks(recentlyPlayedAdapter)
        binding.homeLastfmTracks.bindHomeTracks(lastFmTrackAdapter)
        binding.homeRecentDownloadsTracks.bindHomeTracks(recentDownloadsAdapter)

        collectImmediately(playbackModel.displayItem, ::updateCurrentItem)
        collectImmediately(musicModel.statistics, ::updateLibrarySummary)
        collectImmediately(crewModel.state, ::updateCrew)
        collectImmediately(continuationModel.state, homeModel.songList, ::updateContinuation)
        collectImmediately(lastFmModel.state, ::updateLastFm)
        collectImmediately(
            collectionsModel.state,
            musicModel.statistics,
            musicModel.indexingState,
            ::updateLibraryShortcuts,
        )
    }

    override fun onDestroyBinding(binding: FragmentShippyHomeBinding) {
        binding.homeToolbar.setOnMenuItemClickListener(null)
        binding.homeLibraryShortcuts.adapter = null
        binding.homeRecentlyPlayedTracks.adapter = null
        binding.homeLastfmTracks.adapter = null
        binding.homeRecentDownloadsTracks.adapter = null
        super.onDestroyBinding(binding)
    }

    private fun onToolbarItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_settings -> {
                homeModel.showSettings()
                true
            }
            else -> false
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

    private fun updateLastFm(state: LastFmHomeState) {
        val binding = requireBinding()
        binding.homeLastfm.isVisible = state !is LastFmHomeState.Hidden
        when (state) {
            LastFmHomeState.Hidden -> Unit
            LastFmHomeState.Loading -> {
                binding.homeLastfmSummary.text = ""
                binding.homeLastfmStatus.setText(R.string.lbl_lastfm_loading)
                lastFmTrackAdapter.submitList(emptyList())
            }
            LastFmHomeState.Error -> {
                binding.homeLastfmSummary.text = ""
                binding.homeLastfmStatus.setText(R.string.lbl_lastfm_unavailable)
                lastFmTrackAdapter.submitList(emptyList())
            }
            is LastFmHomeState.Content -> {
                binding.homeLastfmSummary.text =
                    getString(
                        R.string.fmt_lastfm_play_count,
                        state.overview.username,
                        state.overview.playCount,
                    )
                binding.homeLastfmStatus.setText(
                    if (state.stale) R.string.lbl_lastfm_cached else R.string.lbl_lastfm_top_tracks
                )
                lastFmTrackAdapter.submitList(
                    state.overview.topTracks.map { track ->
                        HomeTrackRow(
                            key = "lastfm:${track.artist}:${track.title}",
                            title = track.title,
                            subtitle = track.artist,
                            artwork = track.artworkUrl,
                        )
                    }
                )
            }
        }
    }

    private fun updateContinuation(state: HomeContinuationState, localSongs: List<Song>) {
        val binding = requireBinding()
        val localSongsById = localSongs.associateBy { it.uid.toString() }
        binding.homeRecentlyPlayed.isVisible = state.recentlyPlayed.isNotEmpty()
        binding.homeRecentDownloads.isVisible = state.recentDownloads.isNotEmpty()
        recentlyPlayedAdapter.submitList(
            state.recentlyPlayed.map { entry ->
                HomeTrackRow(
                    key = entry.trackId,
                    title = entry.title,
                    subtitle = entry.artists.joinToString(", "),
                    artwork = entry.artwork,
                    localSong =
                        if (entry.realm == TrackRealm.LOCAL) {
                            localSongsById[entry.trackId.removePrefix("local:")]
                        } else {
                            null
                        },
                )
            }
        )
        recentDownloadsAdapter.submitList(
            state.recentDownloads.map { download ->
                HomeTrackRow(
                    key = download.job.id.value,
                    title = download.track.title,
                    subtitle = download.track.artists.joinToString(", "),
                    artwork = download.track.artwork,
                )
            }
        )
    }

    private fun RecyclerView.bindHomeTracks(homeAdapter: HomeTrackAdapter) {
        layoutManager = LinearLayoutManager(requireContext())
        adapter = homeAdapter
        isNestedScrollingEnabled = false
    }

    private fun openSearch(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return
        findNavController()
            .navigate(
                R.id.search_fragment,
                bundleOf(SearchFragment.ARG_INITIAL_QUERY to normalized),
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
            is ActiveCrewRuntimeState.Active ->
                renderCrewMembers(state.presentation.crewState.members.size)
            is ActiveCrewRuntimeState.Ending -> {
                binding.homeCrewStatus.setText(R.string.lbl_ending_crew)
                binding.homeCrewMembers.text =
                    resources.getQuantityString(
                        R.plurals.plr_crew_members,
                        state.presentation.crewState.members.size,
                        state.presentation.crewState.members.size,
                    )
            }
            else -> Unit
        }
    }

    private fun renderCrewMembers(memberCount: Int) {
        requireBinding().homeCrewMembers.text =
            resources.getQuantityString(R.plurals.plr_crew_members, memberCount, memberCount)
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
        pinnedProviderAdapter.submitList(state.savedProviderEntities.filter { it.isPinned })
    }

    private fun openCollection(collectionId: LibraryCollectionId) {
        findNavController()
            .navigate(
                R.id.shippy_collection_detail_fragment,
                bundleOf("collectionId" to collectionId.value),
            )
    }

    private fun openProviderEntity(saved: SavedProviderEntity) {
        val entity = saved.entity
        findNavController()
            .navigate(
                R.id.provider_entity_detail_fragment,
                bundleOf(
                    ProviderEntityDetailFragment.ARG_PROVIDER_ID to entity.providerId.value,
                    ProviderEntityDetailFragment.ARG_SOURCE_ITEM_ID to entity.sourceItemId,
                    ProviderEntityDetailFragment.ARG_ENTITY_TYPE to entity.type.name,
                    ProviderEntityDetailFragment.ARG_TITLE to entity.title,
                    ProviderEntityDetailFragment.ARG_SUBTITLE to entity.subtitle,
                    ProviderEntityDetailFragment.ARG_ARTWORK to entity.artwork,
                    ProviderEntityDetailFragment.ARG_ORIGINAL_URL to entity.originalUrl,
                ),
            )
    }
}
