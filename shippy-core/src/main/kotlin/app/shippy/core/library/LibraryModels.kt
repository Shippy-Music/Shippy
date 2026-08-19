/*
 * Copyright (c) 2026 Auxio Project
 * LibraryModels.kt is part of Auxio.
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
package app.shippy.core.library

import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import java.time.Instant

data class LibraryRelationship(
    val recordingId: RecordingId,
    val liked: Boolean,
    val explicitlySaved: Boolean,
    val firstAddedAt: Instant?,
    val userEdited: Boolean,
    val manuallyIdentified: Boolean,
)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "Playlist name cannot be blank" }
        require(updatedAt >= createdAt) { "Playlist update cannot precede creation" }
    }
}

data class PlaylistEntry(
    val id: PlaylistEntryId,
    val playlistId: PlaylistId,
    val recordingId: RecordingId,
    val orderKey: Long,
    val addedAt: Instant,
)
