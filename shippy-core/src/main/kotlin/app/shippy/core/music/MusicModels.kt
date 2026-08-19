/*
 * Copyright (c) 2026 Auxio Project
 * MusicModels.kt is part of Auxio.
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
package app.shippy.core.music

import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.ReleaseId
import java.time.Instant

data class ArtistCredit(val names: List<ArtistCreditName>) {
    init {
        require(names.isNotEmpty()) { "Artist credit requires at least one name" }
    }

    val displayName: String
        get() = names.joinToString(separator = "") { it.name + (it.joinPhrase ?: "") }
}

data class ArtistCreditName(
    val artistId: ArtistId?,
    val name: String,
    val joinPhrase: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Artist credit name cannot be blank" }
    }
}

@JvmInline
value class ArtworkReference(val value: String) {
    init {
        require(value.isNotBlank()) { "Artwork reference cannot be blank" }
    }
}

enum class Explicitness {
    CLEAN,
    EXPLICIT,
    UNKNOWN,
}

enum class VersionKind {
    ORIGINAL,
    LIVE,
    REMIX,
    ACOUSTIC,
    INSTRUMENTAL,
    RADIO_EDIT,
    REMASTER,
    COVER,
    KARAOKE,
    SPED_UP,
    SLOWED,
    OTHER,
    UNKNOWN,
}

enum class VersionTrait {
    LIVE,
    REMIX,
    ACOUSTIC,
    INSTRUMENTAL,
    RADIO_EDIT,
    REMASTERED,
    COVER,
    KARAOKE,
    SPED_UP,
    SLOWED,
    REVERB,
}

data class RecordingVersion(
    val kind: VersionKind,
    val label: String? = null,
    val traits: Set<VersionTrait> = emptySet(),
) {
    init {
        require(label == null || label.isNotBlank()) { "Version label cannot be blank" }
    }
}

sealed interface RecordingRetention {
    data class Transient(val expiresAt: Instant) : RecordingRetention

    data object RecentHistory : RecordingRetention

    data object DurableReference : RecordingRetention
}

data class Recording(
    val id: RecordingId,
    val title: String,
    val artistCredit: ArtistCredit,
    val durationMs: Long?,
    val version: RecordingVersion,
    val explicitness: Explicitness,
    val preferredReleaseId: ReleaseId?,
    val preferredArtwork: ArtworkReference?,
    val retention: RecordingRetention,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(title.isNotBlank()) { "Recording title cannot be blank" }
        require(durationMs == null || durationMs >= 0) { "Recording duration cannot be negative" }
        require(updatedAt >= createdAt) { "Recording update cannot precede creation" }
    }
}

data class Artist(val id: ArtistId, val name: String) {
    init {
        require(name.isNotBlank()) { "Artist name cannot be blank" }
    }
}

enum class ReleaseKind {
    ALBUM,
    SINGLE,
    EP,
    COMPILATION,
    SOUNDTRACK,
    OTHER,
    UNKNOWN,
}

data class Release(
    val id: ReleaseId,
    val title: String,
    val artistCredit: ArtistCredit,
    val kind: ReleaseKind,
    val artwork: ArtworkReference? = null,
) {
    init {
        require(title.isNotBlank()) { "Release title cannot be blank" }
    }
}
