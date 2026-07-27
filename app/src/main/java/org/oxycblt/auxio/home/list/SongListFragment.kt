/*
 * Copyright (c) 2021 Auxio Project
 * SongListFragment.kt is part of Auxio.
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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackDisplayItem
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.provider.ui.ProviderTrackActionsSheet
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Song]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class SongListFragment :
    ListFragment<Song, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var downloads: DownloadJobRepository
    @Inject lateinit var shippyPlayback: ShippyPlaybackController
    private val songAdapter =
        UnifiedSongAdapter(
            localListener = this,
            onDownloadedClick = ::playDownloaded,
            onDownloadedMenu = ::openDownloadedMenu,
        )
    private var localSongs: List<Song> = emptyList()
    private var availableDownloads: List<PersistedDownload> = emptyList()
    private var indexingState: IndexingState? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_song_recycler
            adapter = songAdapter
            popupProvider = this@SongListFragment
            listener = this@SongListFragment
            itemAnimator = null
            setItemViewCacheSize(12)
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_song_48)
            contentDescription = getString(R.string.lbl_songs)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_songs)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.songList, ::updateSongs)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloads.observeAvailable().collect(::updateDownloads)
            }
        }
        collectImmediately(musicModel.indexingState, ::updateIndexingState)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.displayItem,
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
        val row = songAdapter.currentList.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        // Note: We don't use the more correct individual artist name here, as sorts are largely
        // based off the names of the parent objects and not the child objects.
        return when (homeModel.songSort.mode) {
            // Name -> Use name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    row.title(requireContext()).popupThumb()
                )

            // Artist -> Use name of first artist
            is Sort.Mode.ByArtist ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    row.artist(requireContext()).popupThumb()
                )

            // Album -> Use Album Name
            is Sort.Mode.ByAlbum ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    row.album(requireContext()).popupThumb()
                )

            // Date -> Use year of the range minimum
            is Sort.Mode.ByDate -> {
                val year = row.releaseYear() ?: return null
                FastScrollRecyclerView.PopupProvider.PopupData(getString(R.string.fmt_number, year))
            }

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    (row.durationMs() ?: 0L).formatDurationMsPopup()
                )

            // Last added -> Use year
            is Sort.Mode.ByDateAdded -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = row.addedMs()
                FastScrollRecyclerView.PopupProvider.PopupData(
                    getString(R.string.fmt_number, calendar.get(Calendar.YEAR))
                )
            }

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Song) {
        playbackModel.play(item, homeModel.playWith)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, homeModel.playWith)
    }

    private fun updateSongs(songs: List<Song>) {
        localSongs = songs
        renderRows()
    }

    private fun updateDownloads(downloads: List<PersistedDownload>) {
        availableDownloads =
            downloads
                .groupBy { it.track.id }
                .values
                .mapNotNull { jobs -> jobs.maxByOrNull(PersistedDownload::updatedAtEpochMs) }
        renderRows()
    }

    private fun updateIndexingState(state: IndexingState?) {
        indexingState = state
        updateNoMusicIndicator()
    }

    private fun renderRows() {
        val context = context ?: return
        val downloadUris =
            availableDownloads.mapNotNullTo(mutableSetOf()) { it.job.artifact?.contentUri }
        // The selected download folder is also indexed by Musikr. Hide that metadata-poor indexed
        // copy and render the canonical persisted download instead, otherwise users see both a
        // code-suffixed filename with no artwork and the real track.
        val visibleLocalSongs =
            localSongs.filterNot { local -> local.uri.toString() in downloadUris }
        val downloadedRows =
            availableDownloads.filterNot { download ->
                visibleLocalSongs.any { local -> download.matchesLocal(local, context) }
            }
        songAdapter.submitList(
            buildList {
                    addAll(visibleLocalSongs.map(UnifiedSongRow::Local))
                    addAll(downloadedRows.map(UnifiedSongRow::Downloaded))
                }
                .sortedWith(homeModel.songSort.rowComparator(context))
        )
        updateNoMusicIndicator()
    }

    private fun updateNoMusicIndicator() {
        val binding = requireBinding()
        val empty = songAdapter.currentList.isEmpty()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        songAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(
        item: PlaybackDisplayItem?,
        parent: MusicParent?,
        isPlaying: Boolean,
    ) {
        // Only indicate playback that is from all songs
        songAdapter.setPlaying(
            localSong = item?.localSong.takeIf { parent == null },
            trackId = item?.queueItem?.track?.id.takeIf { parent == null },
            isPlaying = isPlaying,
        )
    }

    private fun playDownloaded(download: PersistedDownload) {
        viewLifecycleOwner.lifecycleScope.launch {
            shippyPlayback.play(download.track, "library:songs")
        }
    }

    private fun openDownloadedMenu(download: PersistedDownload) {
        ProviderTrackActionsSheet.show(parentFragmentManager, download.track)
    }

    private class UnifiedSongAdapter(
        private val localListener: SelectableListListener<Song>,
        private val onDownloadedClick: (PersistedDownload) -> Unit,
        private val onDownloadedMenu: (PersistedDownload) -> Unit,
    ) : ListAdapter<UnifiedSongRow, RecyclerView.ViewHolder>(DIFF) {
        private var selected = emptySet<Song>()
        private var playingSong: Song? = null
        private var playingTrackId: TrackId? = null
        private var isPlaying = false

        override fun getItemViewType(position: Int) =
            when (getItem(position)) {
                is UnifiedSongRow.Local -> SongViewHolder.VIEW_TYPE
                is UnifiedSongRow.Downloaded -> VIEW_TYPE_DOWNLOADED
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            if (viewType == SongViewHolder.VIEW_TYPE) {
                SongViewHolder.from(parent)
            } else {
                DownloadedViewHolder(
                    ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is UnifiedSongRow.Local -> {
                    (holder as SongViewHolder).apply {
                        bind(row.song, localListener)
                        updateSelectionIndicator(row.song in selected)
                        updatePlayingIndicator(row.song == playingSong, isPlaying)
                    }
                }
                is UnifiedSongRow.Downloaded ->
                    (holder as DownloadedViewHolder).bind(
                        row.download,
                        isActive = row.download.track.id == playingTrackId,
                        isPlaying = isPlaying,
                        onDownloadedClick,
                        onDownloadedMenu,
                    )
            }
        }

        fun setSelected(songs: Set<Song>) {
            if (selected == songs) return
            val changed = selected + songs
            selected = songs
            currentList.forEachIndexed { index, row ->
                if (row is UnifiedSongRow.Local && row.song in changed) {
                    notifyItemChanged(index)
                }
            }
        }

        fun setPlaying(localSong: Song?, trackId: TrackId?, isPlaying: Boolean) {
            if (
                playingSong == localSong && playingTrackId == trackId && this.isPlaying == isPlaying
            ) {
                return
            }
            val affectedSongs = listOfNotNull(playingSong, localSong).toSet()
            val affectedTrackIds = listOfNotNull(playingTrackId, trackId).toSet()
            playingSong = localSong
            playingTrackId = trackId
            this.isPlaying = isPlaying
            currentList.forEachIndexed { index, row ->
                if (
                    (row is UnifiedSongRow.Local && row.song in affectedSongs) ||
                        (row is UnifiedSongRow.Downloaded &&
                            row.download.track.id in affectedTrackIds)
                ) {
                    notifyItemChanged(index)
                }
            }
        }

        private class DownloadedViewHolder(private val binding: ItemSongBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(
                download: PersistedDownload,
                isActive: Boolean,
                isPlaying: Boolean,
                onClick: (PersistedDownload) -> Unit,
                onMenu: (PersistedDownload) -> Unit,
            ) {
                val track = download.track
                binding.songName.text = track.title
                binding.songInfo.text = track.artists.joinToString(", ")
                binding.songAlbumCover.apply {
                    bindArtwork(track.artwork, track.title)
                    setPlaying(isActive && isPlaying)
                }
                binding.songMenu.apply {
                    setIconResource(R.drawable.ic_more_24)
                    contentDescription = context.getString(R.string.lbl_more)
                    setOnClickListener { onMenu(download) }
                }
                binding.root.apply {
                    isSelected = isActive
                    isActivated = false
                    setOnClickListener { onClick(download) }
                    contentDescription = "${track.title}, ${binding.songInfo.text}"
                }
            }
        }

        private companion object {
            const val VIEW_TYPE_DOWNLOADED = 10_001
            val DIFF =
                object : DiffUtil.ItemCallback<UnifiedSongRow>() {
                    override fun areItemsTheSame(
                        old: UnifiedSongRow,
                        new: UnifiedSongRow,
                    ): Boolean =
                        when {
                            old is UnifiedSongRow.Local && new is UnifiedSongRow.Local ->
                                old.song.uid == new.song.uid
                            old is UnifiedSongRow.Downloaded && new is UnifiedSongRow.Downloaded ->
                                old.download.track.id == new.download.track.id
                            else -> false
                        }

                    override fun areContentsTheSame(old: UnifiedSongRow, new: UnifiedSongRow) =
                        old == new
                }
        }
    }
}

private sealed interface UnifiedSongRow {
    data class Local(val song: Song) : UnifiedSongRow

    data class Downloaded(val download: PersistedDownload) : UnifiedSongRow
}

private fun Sort.rowComparator(context: Context): Comparator<UnifiedSongRow> {
    val ascending =
        when (mode) {
            Sort.Mode.ByName -> compareBy { it.title(context).lowercase() }
            Sort.Mode.ByArtist ->
                compareBy<UnifiedSongRow> { it.artist(context).lowercase() }
                    .thenBy { it.album(context).lowercase() }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByAlbum ->
                compareBy<UnifiedSongRow> { it.album(context).lowercase() }
                    .thenBy { it.discNumber() }
                    .thenBy { it.trackNumber() }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByDate ->
                compareBy<UnifiedSongRow> { it.releaseYear() ?: Int.MIN_VALUE }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByDuration ->
                compareBy<UnifiedSongRow> { it.durationMs() ?: Long.MIN_VALUE }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByDisc ->
                compareBy<UnifiedSongRow> { it.discNumber() }
                    .thenBy { it.trackNumber() }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByTrack ->
                compareBy<UnifiedSongRow> { it.discNumber() }
                    .thenBy { it.trackNumber() }
                    .thenBy { it.title(context).lowercase() }
            Sort.Mode.ByDateAdded ->
                compareBy<UnifiedSongRow> { it.addedMs() }.thenBy { it.title(context).lowercase() }
            else -> compareBy { it.title(context).lowercase() }
        }
    return if (direction == Sort.Direction.ASCENDING) ascending else ascending.reversed()
}

private fun UnifiedSongRow.title(context: Context) =
    when (this) {
        is UnifiedSongRow.Local -> song.name.resolve(context)
        is UnifiedSongRow.Downloaded -> download.track.title
    }

private fun UnifiedSongRow.artist(context: Context) =
    when (this) {
        is UnifiedSongRow.Local -> song.artists.resolveNames(context)
        is UnifiedSongRow.Downloaded -> download.track.artists.firstOrNull().orEmpty()
    }

private fun UnifiedSongRow.album(context: Context) =
    when (this) {
        is UnifiedSongRow.Local -> song.album.name.resolve(context)
        is UnifiedSongRow.Downloaded -> download.track.album.orEmpty()
    }

private fun UnifiedSongRow.durationMs() =
    when (this) {
        is UnifiedSongRow.Local -> song.durationMs
        is UnifiedSongRow.Downloaded -> download.track.durationMs
    }

private fun UnifiedSongRow.addedMs() =
    when (this) {
        is UnifiedSongRow.Local -> song.addedMs
        is UnifiedSongRow.Downloaded -> download.updatedAtEpochMs
    }

private fun UnifiedSongRow.releaseYear() =
    when (this) {
        is UnifiedSongRow.Local -> song.album.dates?.min?.year
        is UnifiedSongRow.Downloaded -> null
    }

private fun UnifiedSongRow.discNumber() =
    when (this) {
        is UnifiedSongRow.Local -> song.disc?.number ?: Int.MIN_VALUE
        is UnifiedSongRow.Downloaded -> Int.MIN_VALUE
    }

private fun UnifiedSongRow.trackNumber() =
    when (this) {
        is UnifiedSongRow.Local -> song.track ?: Int.MIN_VALUE
        is UnifiedSongRow.Downloaded -> Int.MIN_VALUE
    }

private fun PersistedDownload.matchesLocal(song: Song, context: Context): Boolean {
    val localTitle = song.name.resolve(context).normalizedSongKey()
    val localArtists = song.artists.resolveNames(context).normalizedSongKey()
    val providerTitle = track.title.normalizedSongKey()
    val providerArtists = track.artists.joinToString(" ").normalizedSongKey()
    if (localTitle != providerTitle || localArtists != providerArtists) return false
    val providerDuration = track.durationMs ?: return true
    return abs(song.durationMs - providerDuration) <= 2_000L
}

private fun String.normalizedSongKey() = lowercase().filter(Char::isLetterOrDigit)

private fun String.popupThumb() = trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
