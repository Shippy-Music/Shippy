/*
 * Copyright (c) 2023 Auxio Project
 * PlaylistListFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.PlaylistViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.shippy.library.LibraryCollectionsState
import org.oxycblt.auxio.shippy.library.LibraryCollectionsViewModel
import org.oxycblt.auxio.shippy.library.systemRows
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Playlist]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class PlaylistListFragment :
    ListFragment<Playlist, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val collectionsModel: LibraryCollectionsViewModel by viewModels()
    private val collectionsHeaderAdapter = LibrarySectionHeaderAdapter(R.string.lbl_collections)
    private val systemCollectionAdapter = LibrarySystemCollectionAdapter()
    private val onboardingAdapter =
        LibraryOnboardingAdapter { homeModel.startChooseMusicLocations() }
    private val shippyPlaylistsHeaderAdapter = LibrarySectionHeaderAdapter(R.string.lbl_shippy_playlists)
    private val shippyPlaylistAdapter = ShippyPlaylistProjectionAdapter()
    private val devicePlaylistsHeaderAdapter = LibrarySectionHeaderAdapter(R.string.lbl_on_this_device)
    private val playlistAdapter = PlaylistAdapter(this)
    private val libraryAdapter =
        ConcatAdapter(
            collectionsHeaderAdapter,
            systemCollectionAdapter,
            onboardingAdapter,
            shippyPlaylistsHeaderAdapter,
            shippyPlaylistAdapter,
            devicePlaylistsHeaderAdapter,
            playlistAdapter,
        )
    private var localSongCount = 0
    private var devicePlaylistCount = 0
    private var isLocalIndexing = false
    private var collectionState = LibraryCollectionsState()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_playlist_recycler
            adapter = libraryAdapter
            popupProvider = this@PlaylistListFragment
            listener = this@PlaylistListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_playlist_48)
            contentDescription = getString(R.string.lbl_playlists)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_playlists)
        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.playlistList, ::updatePlaylists)
        collectImmediately(collectionsModel.state, ::updateCollections)
        collectImmediately(homeModel.songList, musicModel.indexingState, ::updateLocalCollection)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val playlistPosition = pos - (libraryAdapter.itemCount - playlistAdapter.itemCount)
        val playlist = homeModel.playlistList.value.getOrNull(playlistPosition) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.playlistSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(playlist.name.thumb() ?: "?")

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    playlist.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(playlist.songs.size.toString())

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Playlist) {
        detailModel.showPlaylist(item)
    }

    override fun onOpenMenu(item: Playlist) {
        listModel.openMenu(R.menu.playlist, item)
    }

    private fun updatePlaylists(playlists: List<Playlist>) {
        devicePlaylistCount = playlists.size
        playlistAdapter.update(playlists, homeModel.playlistInstructions.consume())
        devicePlaylistsHeaderAdapter.setShown(playlists.isNotEmpty())
        renderSystemCollections()
    }

    private fun updateCollections(state: LibraryCollectionsState) {
        collectionState = state
        collectionsHeaderAdapter.setShown(true)
        shippyPlaylistsHeaderAdapter.setShown(state.userPlaylists.isNotEmpty())
        shippyPlaylistAdapter.submitList(state.userPlaylists)
        renderSystemCollections()
    }

    private fun updateLocalCollection(songs: List<Song>, indexingState: IndexingState?) {
        localSongCount = songs.size
        isLocalIndexing = indexingState is IndexingState.Indexing
        renderSystemCollections()
    }

    private fun renderSystemCollections() {
        systemCollectionAdapter.submitList(
            collectionState.systemRows(localSongCount, isLocalIndexing)
        )
        onboardingAdapter.setShown(
            collectionState.shouldShowOnboarding(
                localSongCount = localSongCount,
                devicePlaylistCount = devicePlaylistCount,
                isLocalIndexing = isLocalIndexing,
            )
        )
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = false
        binding.homeNoMusic.isInvisible = true
    }

    private fun updateSelection(selection: List<Music>) {
        playlistAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only highlight the playlist if it is currently playing, and if the currently
        // playing song is also contained within.
        val playlist = (parent as? Playlist)?.takeIf { it.songs.contains(song) }
        playlistAdapter.setPlaying(playlist, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Playlist]s using [PlaylistViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class PlaylistAdapter(private val listener: SelectableListListener<Playlist>) :
        SelectionIndicatorAdapter<Playlist, PlaylistViewHolder>(PlaylistViewHolder.DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PlaylistViewHolder.from(parent)

        override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
