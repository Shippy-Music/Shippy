/*
 * Copyright (c) 2026 Auxio Project
 * PortableRecordingDescriptor.kt is part of Auxio.
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
package app.shippy.core.crew

import app.shippy.core.identity.PortableRecordingId
import app.shippy.core.identity.ProviderId
import app.shippy.core.music.VersionKind
import app.shippy.core.music.VersionTrait
import app.shippy.core.source.SourceItemType
import java.net.URI

data class PortableArtistCredit(val name: String, val joinPhrase: String? = null) {
    init {
        require(name.isNotBlank()) { "Portable artist name cannot be blank" }
        require(joinPhrase == null || joinPhrase.isNotEmpty()) { "Join phrase cannot be empty" }
    }
}

data class PortableRecordingVersion(
    val kind: VersionKind,
    val label: String? = null,
    val traits: Set<VersionTrait> = emptySet(),
) {
    init {
        require(label == null || label.isNotBlank()) { "Version label cannot be blank" }
    }
}

data class PortableExternalIdentifier(val namespace: String, val value: String) {
    init {
        require(namespace.isNotBlank() && namespace == namespace.trim()) {
            "Identifier namespace must be non-blank and trimmed"
        }
        require(value.isNotBlank() && value == value.trim()) {
            "External identifier must be non-blank and trimmed"
        }
    }
}

data class PortableSourceHint(
    val providerId: ProviderId,
    val itemType: SourceItemType,
    val stableItemId: String,
) {
    init {
        require(providerId.value.lowercase() !in PRIVATE_SOURCE_NAMES) {
            "Private device sources cannot be portable hints"
        }
        require(stableItemId.isNotBlank() && stableItemId == stableItemId.trim()) {
            "Portable source item ID must be non-blank and trimmed"
        }
        require(!stableItemId.looksLikePrivateLocator()) {
            "Portable source hint cannot contain a device-private locator"
        }
    }

    private fun String.looksLikePrivateLocator(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("file:") ||
            normalized.startsWith("content:") ||
            startsWith("/") ||
            startsWith("\\") ||
            WINDOWS_PATH.matches(this)
    }

    private companion object {
        val PRIVATE_SOURCE_NAMES = setOf("local", "download", "cache", "crew", "filesystem")
        val WINDOWS_PATH = Regex("^[a-zA-Z]:[\\\\/].*")
    }
}

@JvmInline
value class PortableArtworkHint(val httpsUrl: String) {
    init {
        val uri = runCatching { URI(httpsUrl) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && uri.host != null) {
            "Portable artwork must use an absolute HTTPS URL"
        }
        require(uri.userInfo == null && uri.fragment == null) {
            "Portable artwork cannot contain credentials or fragments"
        }
    }
}

data class PortableRecordingDescriptor(
    val portableId: PortableRecordingId,
    val title: String,
    val artistCredit: List<PortableArtistCredit>,
    val durationMs: Long?,
    val version: PortableRecordingVersion,
    val externalIdentifiers: Set<PortableExternalIdentifier>,
    val sourceHints: List<PortableSourceHint>,
    val artworkHint: PortableArtworkHint?,
) {
    init {
        require(title.isNotBlank()) { "Portable recording title cannot be blank" }
        require(artistCredit.isNotEmpty()) { "Portable recording requires an artist credit" }
        require(durationMs == null || durationMs >= 0) { "Recording duration cannot be negative" }
        require(sourceHints.size == sourceHints.toSet().size) {
            "Portable source hints cannot be duplicated"
        }
    }
}
