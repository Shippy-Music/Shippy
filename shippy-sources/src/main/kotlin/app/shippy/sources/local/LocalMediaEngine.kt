/*
 * Copyright (c) 2026 Auxio Project
 * LocalMediaEngine.kt is part of Auxio.
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
package app.shippy.sources.local

import app.shippy.core.asset.AssetLocation
import app.shippy.sources.observation.SourceTrackObservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class LocalAssetKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Local asset key cannot be blank" }
    }
}

@JvmInline
value class LocalRootToken(val value: String) {
    init {
        require(value.isNotBlank()) { "Local root token cannot be blank" }
    }
}

enum class LocalScanReason {
    INITIAL,
    USER_REQUEST,
    SOURCE_CHANGED,
    DOWNLOAD_RECONCILIATION,
    MIGRATION,
}

data class LocalScanRequest(
    val roots: Set<LocalRootToken>,
    val reason: LocalScanReason,
    val force: Boolean,
) {
    init {
        require(roots.isNotEmpty()) { "Local scan requires at least one approved root" }
    }
}

sealed interface LocalScanState {
    data object Idle : LocalScanState

    data class Scanning(val scannedCount: Long) : LocalScanState {
        init {
            require(scannedCount >= 0) { "Scanned count cannot be negative" }
        }
    }

    data class Failed(val code: String, val retryable: Boolean) : LocalScanState {
        init {
            require(code.isNotBlank()) { "Local scan failure code cannot be blank" }
        }
    }
}

sealed interface LocalMediaChange {
    val key: LocalAssetKey

    data class Added(override val key: LocalAssetKey) : LocalMediaChange

    data class Updated(override val key: LocalAssetKey) : LocalMediaChange

    data class Removed(override val key: LocalAssetKey) : LocalMediaChange
}

data class LocalAssetHandle(
    val key: LocalAssetKey,
    val location: AssetLocation,
    val contentLength: Long?,
) {
    init {
        require(contentLength == null || contentLength >= 0) {
            "Local asset length cannot be negative"
        }
    }
}

data class TagPatch(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
) {
    init {
        require(title == null || title.isNotBlank()) { "Tag title cannot be blank" }
        require(artist == null || artist.isNotBlank()) { "Tag artist cannot be blank" }
        require(album == null || album.isNotBlank()) { "Tag album cannot be blank" }
        require(trackNumber == null || trackNumber > 0) { "Track number must be positive" }
        require(discNumber == null || discNumber > 0) { "Disc number must be positive" }
    }
}

sealed interface LocalDeleteResult {
    data object Deleted : LocalDeleteResult

    data object Unsupported : LocalDeleteResult

    data class PermissionRequired(val requestToken: String) : LocalDeleteResult {
        init {
            require(requestToken.isNotBlank()) { "Delete permission token cannot be blank" }
        }
    }

    data object Missing : LocalDeleteResult

    data class Failed(val code: String, val retryable: Boolean) : LocalDeleteResult {
        init {
            require(code.isNotBlank()) { "Local delete failure code cannot be blank" }
        }
    }
}

sealed interface TagWriteResult {
    data object Written : TagWriteResult

    data object Unsupported : TagWriteResult

    data class PermissionRequired(val requestToken: String) : TagWriteResult {
        init {
            require(requestToken.isNotBlank()) { "Tag permission token cannot be blank" }
        }
    }

    data class Failed(val code: String, val retryable: Boolean) : TagWriteResult {
        init {
            require(code.isNotBlank()) { "Tag write failure code cannot be blank" }
        }
    }
}

interface LocalMediaEngine {
    val scanState: StateFlow<LocalScanState>

    fun observeChanges(): Flow<LocalMediaChange>

    suspend fun scan(request: LocalScanRequest)

    suspend fun snapshot(): List<SourceTrackObservation>

    suspend fun open(asset: LocalAssetKey): LocalAssetHandle

    suspend fun delete(asset: LocalAssetKey): LocalDeleteResult

    suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult
}
