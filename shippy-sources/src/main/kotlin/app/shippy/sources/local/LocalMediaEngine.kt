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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

data class LocalMediaSnapshotDescriptor(val fingerprint: String, val count: Long) {
    init {
        require(fingerprint.isNotBlank()) { "Local snapshot fingerprint cannot be blank" }
        require(count >= 0) { "Local snapshot count cannot be negative" }
    }
}

data class LocalMediaSnapshotPage(
    val descriptor: LocalMediaSnapshotDescriptor,
    val offset: Long,
    val observations: List<SourceTrackObservation>,
) {
    init {
        require(offset >= 0) { "Local snapshot page offset cannot be negative" }
    }
}

interface LocalMediaEngine {
    val scanState: StateFlow<LocalScanState>

    fun observeChanges(): Flow<LocalMediaChange>

    suspend fun scan(request: LocalScanRequest)

    suspend fun snapshot(): List<SourceTrackObservation>

    /**
     * Returns only stable catalog metadata; implementations should avoid observation conversion.
     */
    suspend fun snapshotDescriptor(): LocalMediaSnapshotDescriptor {
        val ordered = snapshot().sortedBy(SourceTrackObservation::localStableKey)
        return LocalMediaSnapshotDescriptor(
            fingerprint = ordered.localSnapshotFingerprint(),
            count = ordered.size.toLong(),
        )
    }

    /** Returns one bounded, deterministically ordered page for a previously obtained descriptor. */
    suspend fun snapshotPage(
        offset: Long,
        limit: Int,
        expectedFingerprint: String? = null,
    ): LocalMediaSnapshotPage {
        require(offset >= 0) { "Local snapshot page offset cannot be negative" }
        require(limit > 0) { "Local snapshot page size must be positive" }
        expectedFingerprint?.let {
            require(it.isNotBlank()) { "Expected snapshot fingerprint is blank" }
        }
        val ordered = snapshot().sortedBy(SourceTrackObservation::localStableKey)
        val descriptor =
            LocalMediaSnapshotDescriptor(
                fingerprint = ordered.localSnapshotFingerprint(),
                count = ordered.size.toLong(),
            )
        require(offset <= ordered.size.toLong()) { "Local snapshot page offset exceeds snapshot" }
        val start = offset.toInt()
        val end = minOf(ordered.size, Math.addExact(start, limit))
        return LocalMediaSnapshotPage(
            descriptor = descriptor,
            offset = offset,
            observations = ordered.subList(start, end),
        )
    }

    suspend fun open(asset: LocalAssetKey): LocalAssetHandle

    suspend fun delete(asset: LocalAssetKey): LocalDeleteResult

    suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult
}

private fun SourceTrackObservation.localStableKey(): String =
    listOf(sourceKey.providerId.value, sourceKey.itemType.name, sourceKey.sourceItemId)
        .joinToString(LOCAL_SNAPSHOT_UNIT_SEPARATOR)

private fun List<SourceTrackObservation>.localSnapshotFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { observation ->
        digest.put(observation.localStableKey())
        digest.put(observation.title)
        observation.artistNames.forEach(digest::put)
        digest.put(observation.releaseTitle)
        digest.put(observation.durationMs?.toString())
        digest.put(observation.version.kind.name)
        digest.put(observation.version.label)
        digest.put(observation.explicitness.name)
        observation.version.traits.map(Enum<*>::name).sorted().forEach(digest::put)
        observation.artwork.map { it.value }.forEach(digest::put)
        observation.externalIdentifiers
            .map { "${it.kind.name}:${it.value}" }
            .sorted()
            .forEach(digest::put)
        digest.put(observation.originalUrl)
        observation.asset?.let { asset ->
            digest.put(asset.kind.name)
            digest.put(asset.locationType)
            digest.put(asset.location.opaqueHandle)
            digest.put(asset.documentId)
            digest.put(asset.mediaStoreId?.toString())
            digest.put(asset.normalizedPathToken)
            digest.put(asset.downloadJobId)
            digest.put(asset.lastModifiedEpochMs?.toString())
            digest.put(asset.technical.mimeType)
            digest.put(asset.technical.codec)
            digest.put(asset.technical.bitrateBps?.toString())
            digest.put(asset.technical.sampleRateHz?.toString())
            digest.put(asset.technical.channelCount?.toString())
            digest.put(asset.technical.contentLength?.toString())
            digest.put(asset.checksum?.let { "${it.algorithm}:${it.value}" })
            digest.put(asset.fingerprint)
        }
    }
    return digest.digest().joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
}

private fun MessageDigest.put(value: String?) {
    if (value == null) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
        return
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private const val LOCAL_SNAPSHOT_UNIT_SEPARATOR = "\u001f"
