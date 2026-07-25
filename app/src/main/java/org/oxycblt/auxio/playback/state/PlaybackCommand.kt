/*
 * Copyright (c) 2024 Auxio Project
 * PlaybackCommand.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.state

import javax.inject.Inject
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemFactory
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedPlayback
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/**
 * A playback command that can be passed to [PlaybackStateManager] to start new playback.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface PlaybackCommand {
    /** A particular queue item to play, or null to play the first item in the new queue. */
    val selectedItemId: QueueItemId?

    /** The canonical queue with a playable source prepared for each item. */
    val queue: List<ResolvedQueueItem>

    /** Local-library context compatibility. Provider-originated commands leave this null. */
    val parent: MusicParent?

    /** Whether to shuffle or not. * */
    val shuffled: Boolean

    /**
     * Local-library compatibility factory.
     *
     * Song callers remain at the Auxio boundary. Each invocation creates canonical queue items and
     * resolves their exact local URI before the synchronous player command is emitted.
     */
    interface Factory {
        fun song(song: Song, shuffle: ShuffleMode): PlaybackCommand?

        fun songFromAll(song: Song, shuffle: ShuffleMode): PlaybackCommand?

        fun songFromAlbum(song: Song, shuffle: ShuffleMode): PlaybackCommand?

        fun songFromArtist(song: Song, artist: Artist?, shuffle: ShuffleMode): PlaybackCommand?

        fun songFromGenre(song: Song, genre: Genre?, shuffle: ShuffleMode): PlaybackCommand?

        fun songFromPlaylist(song: Song, playlist: Playlist, shuffle: ShuffleMode): PlaybackCommand?

        fun all(shuffle: ShuffleMode): PlaybackCommand?

        fun songs(songs: List<Song>, shuffle: ShuffleMode): PlaybackCommand?

        fun album(album: Album, shuffle: ShuffleMode): PlaybackCommand?

        fun artist(artist: Artist, shuffle: ShuffleMode): PlaybackCommand?

        fun genre(genre: Genre, shuffle: ShuffleMode): PlaybackCommand?

        fun playlist(playlist: Playlist, shuffle: ShuffleMode): PlaybackCommand?
    }
}

enum class ShuffleMode {
    ON,
    OFF,
    IMPLICIT,
}

class PlaybackCommandFactoryImpl
@Inject
constructor(
    val playbackManager: PlaybackStateManager,
    val playbackSettings: PlaybackSettings,
    val listSettings: ListSettings,
    val musicRepository: MusicRepository,
    val queueItemFactory: QueueItemFactory,
) : PlaybackCommand.Factory {
    data class PlaybackCommandImpl(
        override val selectedItemId: QueueItemId?,
        override val queue: List<ResolvedQueueItem>,
        override val parent: MusicParent?,
        override val shuffled: Boolean,
    ) : PlaybackCommand {
        init {
            require(queue.map { it.item.id }.distinct().size == queue.size) {
                "Playback queue item IDs must be unique"
            }
            require(selectedItemId == null || queue.any { it.item.id == selectedItemId }) {
                "Selected queue item must exist in the playback queue"
            }
        }

        // Only show queue count to reduce memory use
        override fun toString() =
            "PlaybackCommand(selectedItemId=$selectedItemId, " +
                "queue=${queue.size} items, shuffled=$shuffled)"
    }

    override fun song(song: Song, shuffle: ShuffleMode) =
        newCommand(song, null, listOf(song), shuffle)

    override fun songFromAll(song: Song, shuffle: ShuffleMode) = newCommand(song, shuffle)

    override fun songFromAlbum(song: Song, shuffle: ShuffleMode) =
        newCommand(song, song.album, listSettings.albumSongSort, shuffle)

    override fun songFromArtist(song: Song, artist: Artist?, shuffle: ShuffleMode) =
        newCommand(song, artist, song.artists, listSettings.artistSongSort, shuffle)

    override fun songFromGenre(song: Song, genre: Genre?, shuffle: ShuffleMode) =
        newCommand(song, genre, song.genres, listSettings.genreSongSort, shuffle)

    override fun songFromPlaylist(song: Song, playlist: Playlist, shuffle: ShuffleMode) =
        newCommand(song, playlist, playlist.songs, shuffle)

    override fun all(shuffle: ShuffleMode) = newCommand(null, shuffle)

    override fun songs(songs: List<Song>, shuffle: ShuffleMode) =
        newCommand(null, null, songs, shuffle)

    override fun album(album: Album, shuffle: ShuffleMode) =
        newCommand(null, album, listSettings.albumSongSort, shuffle)

    override fun artist(artist: Artist, shuffle: ShuffleMode) =
        newCommand(null, artist, listSettings.artistSongSort, shuffle)

    override fun genre(genre: Genre, shuffle: ShuffleMode) =
        newCommand(null, genre, listSettings.genreSongSort, shuffle)

    override fun playlist(playlist: Playlist, shuffle: ShuffleMode) =
        newCommand(null, playlist, playlist.songs, shuffle)

    private fun <T : MusicParent> newCommand(
        song: Song,
        parent: T?,
        parents: List<T>,
        sort: Sort,
        shuffle: ShuffleMode,
    ): PlaybackCommand? {
        return if (parent != null) {
            newCommand(song, parent, sort, shuffle)
        } else if (song.genres.size == 1) {
            newCommand(song, parents.first(), sort, shuffle)
        } else {
            null
        }
    }

    private fun newCommand(song: Song?, shuffle: ShuffleMode): PlaybackCommand? {
        val library = musicRepository.library ?: return null
        return newCommand(song, null, library.songs, listSettings.songSort, shuffle)
    }

    private fun newCommand(
        song: Song?,
        parent: MusicParent,
        sort: Sort,
        shuffle: ShuffleMode,
    ): PlaybackCommand? {
        val songs = sort.songs(parent.songs)
        return newCommand(song, parent, songs, sort, shuffle)
    }

    private fun newCommand(
        song: Song?,
        parent: MusicParent?,
        queue: Collection<Song>,
        sort: Sort,
        shuffle: ShuffleMode,
    ): PlaybackCommand? {
        if (queue.isEmpty() || (song != null && song !in queue)) {
            return null
        }
        return newCommand(song, parent, sort.songs(queue), shuffle)
    }

    private fun newCommand(
        song: Song?,
        parent: MusicParent?,
        queue: List<Song>,
        shuffle: ShuffleMode,
    ): PlaybackCommand {
        val selectedIndex =
            song?.let { selected ->
                queue.indexOfFirst { it === selected }.takeIf { it >= 0 }
                    ?: queue.indexOf(selected).takeIf { it >= 0 }
            }
        val contextId = parent?.uid?.toString()
        val resolvedQueue =
            queue.map { localSong ->
                queueItemFactory.fromLocal(localSong, contextId = contextId).resolveLocal()
            }
        return PlaybackCommandImpl(
            selectedItemId = selectedIndex?.let { resolvedQueue[it].item.id },
            queue = resolvedQueue,
            parent = parent,
            shuffled = isShuffled(shuffle),
        )
    }

    private fun QueueItem.resolveLocal(): ResolvedQueueItem {
        val candidate =
            track.candidates.singleOrNull { it.kind == CandidateKind.LOCAL }
                ?: error("Local queue item must contain exactly one local candidate")
        val uri = candidate.locator?.takeIf(String::isNotBlank)
            ?: error("Local queue item candidate must contain a playable URI")
        return ResolvedQueueItem(
            item = this,
            playback =
                ResolvedPlayback(
                    queueItemId = id,
                    candidateId = candidate.id,
                    uri = uri,
                    mimeType = candidate.media?.mimeType,
                    bitrateBps = candidate.media?.bitrateBps,
                    contentLength = candidate.media?.contentLength,
                ),
        )
    }

    private fun isShuffled(shuffle: ShuffleMode) =
        when (shuffle) {
            ShuffleMode.ON -> true
            ShuffleMode.OFF -> false
            ShuffleMode.IMPLICIT -> playbackSettings.keepShuffle && playbackManager.isShuffled
        }
}
