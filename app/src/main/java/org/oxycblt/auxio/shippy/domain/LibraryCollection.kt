/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollection.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

@JvmInline
value class LibraryCollectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "LibraryCollectionId cannot be blank" }
    }

    val isSystem: Boolean
        get() = value.startsWith(SYSTEM_COLLECTION_PREFIX)

    override fun toString() = value

    private companion object {
        const val SYSTEM_COLLECTION_PREFIX = "system:"
    }
}

sealed interface LibraryCollection {
    val id: LibraryCollectionId
    val displayName: String
    val canRename: Boolean
    val canDelete: Boolean

    data class System(val kind: SystemCollectionKind) : LibraryCollection {
        override val id = LibraryCollectionId("system:${kind.id}")
        override val displayName = kind.displayName
        override val canRename = false
        override val canDelete = false
    }

    data class Playlist(
        override val id: LibraryCollectionId,
        override val displayName: String,
        val isPinned: Boolean,
        val artworkUri: String? = null,
    ) : LibraryCollection {
        init {
            require(displayName.isNotBlank()) { "Playlist name cannot be blank" }
        }

        override val canRename = true
        override val canDelete = true
    }
}

enum class SystemCollectionKind(val id: String, val displayName: String) {
    LIKED("liked", "Liked"),
    DOWNLOADS("downloads", "Downloads"),
    LOCAL("local", "Local"),
}

data class LibraryRelationship(
    val trackId: TrackId,
    val liked: Boolean = false,
    val downloaded: Boolean = false,
    val playlistIds: Set<LibraryCollectionId> = emptySet(),
) {
    init {
        require(playlistIds.none(LibraryCollectionId::isSystem)) {
            "System collections are derived relationships, not user playlist memberships"
        }
    }
}
