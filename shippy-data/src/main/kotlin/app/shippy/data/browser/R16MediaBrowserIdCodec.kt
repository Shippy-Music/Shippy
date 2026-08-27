/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserIdCodec.kt is part of Auxio.
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
package app.shippy.data.browser

import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.R16SystemCollection

/** The finite set of canonical node kinds understood by the inactive R16 browser seam. */
sealed interface R16MediaBrowserId {
    data object Root : R16MediaBrowserId

    data object LibrarySongs : R16MediaBrowserId

    data object LibraryArtists : R16MediaBrowserId

    data class SystemCollection(val collection: R16SystemCollection) : R16MediaBrowserId

    /** A recording scoped to one rule-driven system collection. */
    data class SystemCollectionRecording(
        val collection: R16SystemCollection,
        val recordingId: RecordingId,
    ) : R16MediaBrowserId

    data class Playlist(val id: PlaylistId) : R16MediaBrowserId

    data class PlaylistEntry(val id: PlaylistEntryId) : R16MediaBrowserId

    data class Recording(val id: RecordingId) : R16MediaBrowserId

    data class Artist(val id: ArtistId) : R16MediaBrowserId

    data class ArtistRecording(val artistId: ArtistId, val recordingId: RecordingId) :
        R16MediaBrowserId

    data class Album(val id: app.shippy.core.identity.ReleaseId) : R16MediaBrowserId

    data class AlbumRecording(
        val releaseId: app.shippy.core.identity.ReleaseId,
        val recordingId: RecordingId,
    ) : R16MediaBrowserId

    data class Genre(val name: String) : R16MediaBrowserId

    data class GenreRecording(val genre: String, val recordingId: RecordingId) : R16MediaBrowserId
}

/**
 * Strict, R16-only browser IDs.
 *
 * The codec deliberately does not accept legacy MediaBrowser IDs or arbitrary provider IDs. A
 * syntactically valid canonical ID is not proof that a row exists; the repository resolves that
 * distinction with an exact Room query.
 */
object R16MediaBrowserIdCodec {
    const val ROOT = "r16:root"
    const val LIBRARY_SONGS = "r16:library-songs"
    const val LIBRARY_ARTISTS = "r16:library-artists"

    private const val PREFIX = "r16"

    fun encode(id: R16MediaBrowserId): String =
        when (id) {
            R16MediaBrowserId.Root -> ROOT
            R16MediaBrowserId.LibrarySongs -> LIBRARY_SONGS
            R16MediaBrowserId.LibraryArtists -> LIBRARY_ARTISTS
            is R16MediaBrowserId.SystemCollection -> "$PREFIX:collection:${id.collection.id}"
            is R16MediaBrowserId.SystemCollectionRecording ->
                "$PREFIX:collection-recording:${id.collection.id}:${id.recordingId.value}"
            is R16MediaBrowserId.Playlist -> "$PREFIX:playlist:${id.id.value}"
            is R16MediaBrowserId.PlaylistEntry -> "$PREFIX:playlist-entry:${id.id.value}"
            is R16MediaBrowserId.Recording -> "$PREFIX:recording:${id.id.value}"
            is R16MediaBrowserId.Artist -> "$PREFIX:artist:${id.id.value}"
            is R16MediaBrowserId.ArtistRecording ->
                "$PREFIX:artist-recording:${id.artistId.value}:${id.recordingId.value}"
            is R16MediaBrowserId.Album -> "$PREFIX:album:${id.id.value}"
            is R16MediaBrowserId.AlbumRecording ->
                "$PREFIX:album-recording:${id.releaseId.value}:${id.recordingId.value}"
            is R16MediaBrowserId.Genre ->
                "$PREFIX:genre:${java.net.URLEncoder.encode(id.name, Charsets.UTF_8.name())}"
            is R16MediaBrowserId.GenreRecording ->
                "$PREFIX:genre-recording:${java.net.URLEncoder.encode(id.genre, Charsets.UTF_8.name())}:${id.recordingId.value}"
        }

    /** Returns null for malformed, legacy, or unknown node-kind IDs. */
    fun decode(raw: String): R16MediaBrowserId? {
        if (raw == ROOT) return R16MediaBrowserId.Root
        if (raw == LIBRARY_SONGS) return R16MediaBrowserId.LibrarySongs
        if (raw == LIBRARY_ARTISTS) return R16MediaBrowserId.LibraryArtists

        val parts = raw.split(':')
        if (parts.size < 2 || parts[0] != PREFIX) return null

        return try {
            when (parts[1]) {
                "collection" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.get(2)
                        ?.let(R16SystemCollection::fromId)
                        ?.let(R16MediaBrowserId::SystemCollection)
                "collection-recording" ->
                    parts
                        .takeIf { it.size == 4 }
                        ?.let { segments ->
                            R16SystemCollection.fromId(segments[2])?.let { collection ->
                                R16MediaBrowserId.SystemCollectionRecording(
                                    collection,
                                    RecordingId(segments[3]),
                                )
                            }
                        }
                "playlist" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.let { R16MediaBrowserId.Playlist(PlaylistId(it[2])) }
                "playlist-entry" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.let { R16MediaBrowserId.PlaylistEntry(PlaylistEntryId(it[2])) }
                "recording" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.let { R16MediaBrowserId.Recording(RecordingId(it[2])) }
                "artist" ->
                    parts.takeIf { it.size == 3 }?.let { R16MediaBrowserId.Artist(ArtistId(it[2])) }
                "artist-recording" ->
                    parts
                        .takeIf { it.size == 4 }
                        ?.let { segments ->
                            R16MediaBrowserId.ArtistRecording(
                                artistId = ArtistId(segments[2]),
                                recordingId = RecordingId(segments[3]),
                            )
                        }
                "album" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.let { R16MediaBrowserId.Album(app.shippy.core.identity.ReleaseId(it[2])) }
                "album-recording" ->
                    parts
                        .takeIf { it.size == 4 }
                        ?.let { segments ->
                            R16MediaBrowserId.AlbumRecording(
                                releaseId = app.shippy.core.identity.ReleaseId(segments[2]),
                                recordingId = RecordingId(segments[3]),
                            )
                        }
                "genre" ->
                    parts
                        .takeIf { it.size == 3 }
                        ?.let {
                            R16MediaBrowserId.Genre(
                                java.net.URLDecoder.decode(it[2], Charsets.UTF_8.name())
                            )
                        }
                "genre-recording" ->
                    parts
                        .takeIf { it.size == 4 }
                        ?.let { segments ->
                            R16MediaBrowserId.GenreRecording(
                                genre =
                                    java.net.URLDecoder.decode(segments[2], Charsets.UTF_8.name()),
                                recordingId = RecordingId(segments[3]),
                            )
                        }
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
