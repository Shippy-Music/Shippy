/*
 * Copyright (c) 2026 Auxio Project
 * R16LocalReindexMigration.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKind
import app.shippy.data.R16DataRuntime
import app.shippy.data.migration.R16LocalReindexAudit
import app.shippy.data.migration.R16LocalReindexCheckpoint
import app.shippy.sources.ingest.RecordingIngestor
import app.shippy.sources.local.LocalMediaEngine
import app.shippy.sources.observation.SourceTrackObservation
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class R16LocalReindexProgress(
    val snapshotCount: Long,
    val processedCount: Long,
    val linkedCount: Long,
    val unresolvedCount: Long,
    val complete: Boolean,
)

/** Inactive M12 bridge. Calling this does not switch database, Library, or playback authority. */
class R16LocalReindexMigration
internal constructor(
    private val localMediaEngine: LocalMediaEngine,
    private val ingestor: RecordingIngestor,
    private val audit: R16LocalReindexAudit,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Local re-index page size is out of bounds" }
    }

    suspend fun run(
        migrationId: String,
        onProgress: suspend (R16LocalReindexProgress) -> Unit = {},
    ): R16LocalReindexProgress {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        val observations =
            localMediaEngine.snapshot().onEach { observation ->
                require(
                    observation.sourceKind == SourceKind.LOCAL_FILE &&
                        observation.sourceKey.itemType == SourceItemType.LOCAL_FILE
                ) {
                    "M12 accepts only exact local-file observations"
                }
            }
        val ordered = observations.sortedBy(SourceTrackObservation::stableKey)
        require(ordered.map(SourceTrackObservation::stableKey).toSet().size == ordered.size) {
            "M12 snapshot contains duplicate exact source keys"
        }
        val fingerprint = ordered.snapshotFingerprint()
        val saved = audit.load(migrationId)
        val resumable = saved?.takeIf { it.matches(ordered, fingerprint) }
        var processed = resumable?.processedCount ?: 0L
        var linked = resumable?.linkedCount ?: 0L
        var unresolved = resumable?.unresolvedCount ?: 0L

        if (resumable?.complete == true) {
            return resumable.toProgress().also { onProgress(it) }
        }

        val pending = ordered.drop(processed.toInt())
        if (pending.isEmpty()) {
            val complete =
                checkpoint(
                    fingerprint,
                    ordered.size.toLong(),
                    processed,
                    linked,
                    unresolved,
                    resumable?.lastSourceKey,
                    true,
                )
            audit.save(migrationId, complete)
            return complete.toProgress().also { onProgress(it) }
        }

        var progress: R16LocalReindexProgress? = null
        for (page in pending.chunked(pageSize)) {
            page.forEach { observation ->
                val result = ingestor.ingest(observation)
                if (result.recordingId == null) unresolved++ else linked++
                processed++
            }
            val complete = processed == ordered.size.toLong()
            val checkpoint =
                checkpoint(
                    fingerprint,
                    ordered.size.toLong(),
                    processed,
                    linked,
                    unresolved,
                    page.last().stableKey(),
                    complete,
                )
            audit.save(migrationId, checkpoint)
            progress = checkpoint.toProgress()
            onProgress(progress)
        }
        return checkNotNull(progress)
    }

    companion object {
        fun create(
            localMediaEngine: LocalMediaEngine,
            dataRuntime: R16DataRuntime,
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): R16LocalReindexMigration =
            R16LocalReindexMigration(
                localMediaEngine = localMediaEngine,
                ingestor = RecordingIngestor(DataRecordingIngestionStore(dataRuntime.ingestion)),
                audit = dataRuntime.localReindexAudit,
                pageSize = pageSize,
            )

        const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 500
    }
}

private fun checkpoint(
    fingerprint: String,
    snapshotCount: Long,
    processedCount: Long,
    linkedCount: Long,
    unresolvedCount: Long,
    lastSourceKey: String?,
    complete: Boolean,
) =
    R16LocalReindexCheckpoint(
        snapshotFingerprint = fingerprint,
        snapshotCount = snapshotCount,
        processedCount = processedCount,
        linkedCount = linkedCount,
        unresolvedCount = unresolvedCount,
        lastSourceKey = lastSourceKey,
        complete = complete,
    )

private fun R16LocalReindexCheckpoint.matches(
    observations: List<SourceTrackObservation>,
    fingerprint: String,
): Boolean {
    if (snapshotFingerprint != fingerprint || snapshotCount != observations.size.toLong()) {
        return false
    }
    if (processedCount == 0L) return lastSourceKey == null
    if (processedCount > observations.size) return false
    return observations[processedCount.toInt() - 1].stableKey() == lastSourceKey
}

private fun R16LocalReindexCheckpoint.toProgress() =
    R16LocalReindexProgress(
        snapshotCount = snapshotCount,
        processedCount = processedCount,
        linkedCount = linkedCount,
        unresolvedCount = unresolvedCount,
        complete = complete,
    )

private fun SourceTrackObservation.stableKey(): String =
    listOf(sourceKey.providerId.value, sourceKey.itemType.name, sourceKey.sourceItemId)
        .joinToString(UNIT_SEPARATOR)

private fun List<SourceTrackObservation>.snapshotFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { observation ->
        digest.put(observation.stableKey())
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

private const val UNIT_SEPARATOR = "\u001f"
