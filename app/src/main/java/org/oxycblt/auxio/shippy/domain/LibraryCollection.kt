/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryCollection.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

@JvmInline
value class LibraryCollectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "LibraryCollectionId cannot be blank" }
    }

    override fun toString() = value
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
    ) : LibraryCollection {
        init {
            require(displayName.isNotBlank()) { "Playlist name cannot be blank" }
        }

        override val canRename = true
        override val canDelete = true
    }
}

enum class SystemCollectionKind(
    val id: String,
    val displayName: String,
) {
    LIKED("liked", "Liked"),
    DOWNLOADS("downloads", "Downloads"),
    LOCAL("local", "Local"),
}

data class LibraryRelationship(
    val trackId: TrackId,
    val liked: Boolean = false,
    val downloaded: Boolean = false,
    val playlistIds: Set<LibraryCollectionId> = emptySet(),
)
