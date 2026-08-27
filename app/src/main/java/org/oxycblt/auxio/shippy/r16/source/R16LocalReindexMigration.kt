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
import app.shippy.sources.local.LocalMediaSnapshotDescriptor
import app.shippy.sources.local.LocalMediaSnapshotPage
import app.shippy.sources.observation.SourceTrackObservation

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

    /**
     * Processes one deterministic snapshot page. The supplied checkpoint is authoritative for this
     * invocation; if its fingerprint no longer matches the current Musikr snapshot, the page
     * restarts at the beginning instead of applying a stale ordinal.
     */
    internal suspend fun runPage(
        migrationId: String,
        checkpoint: R16LocalReindexCheckpoint? = null,
        pageSize: Int = this.pageSize,
        persistAudit: Boolean = true,
    ): R16LocalReindexCheckpoint {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "Local re-index page size is out of bounds" }

        val descriptor = localMediaEngine.snapshotDescriptor()
        val candidate = checkpoint ?: if (persistAudit) audit.load(migrationId) else null
        val resumable = candidate?.takeIf { it.matches(descriptor) }
        if (resumable?.complete == true) return resumable

        val processed = resumable?.processedCount ?: 0L
        val linked = resumable?.linkedCount ?: 0L
        val unresolved = resumable?.unresolvedCount ?: 0L
        val pageResult =
            localMediaEngine.snapshotPage(
                offset = processed,
                limit = pageSize,
                expectedFingerprint = descriptor.fingerprint,
            )
        if (pageResult.descriptor != descriptor) {
            return makeCheckpoint(
                    fingerprint = pageResult.descriptor.fingerprint,
                    snapshotCount = pageResult.descriptor.count,
                    processedCount = 0,
                    linkedCount = 0,
                    unresolvedCount = 0,
                    lastSourceKey = null,
                    complete = false,
                )
                .also { if (persistAudit) audit.save(migrationId, it) }
        }

        val page = validatePage(pageResult, processed, resumable?.lastSourceKey, pageSize)
        if (page.isEmpty()) {
            require(processed == descriptor.count) {
                "Local snapshot page ended before the declared snapshot count"
            }
            return makeCheckpoint(
                    fingerprint = descriptor.fingerprint,
                    snapshotCount = descriptor.count,
                    processedCount = processed,
                    linkedCount = linked,
                    unresolvedCount = unresolved,
                    lastSourceKey = resumable?.lastSourceKey,
                    complete = true,
                )
                .also { if (persistAudit) audit.save(migrationId, it) }
        }

        var nextLinked = linked
        var nextUnresolved = unresolved
        page.forEach { observation ->
            val result = ingestor.ingest(observation)
            if (result.recordingId == null) nextUnresolved++ else nextLinked++
        }
        val nextProcessed = Math.addExact(processed, page.size.toLong())
        require(nextProcessed <= descriptor.count) {
            "Local snapshot page exceeds the declared snapshot count"
        }
        require(page.size == pageSize || nextProcessed == descriptor.count) {
            "Local snapshot page ended before the declared snapshot count"
        }
        return makeCheckpoint(
                fingerprint = descriptor.fingerprint,
                snapshotCount = descriptor.count,
                processedCount = nextProcessed,
                linkedCount = nextLinked,
                unresolvedCount = nextUnresolved,
                lastSourceKey = page.last().stableKey(),
                complete = nextProcessed == descriptor.count,
            )
            .also { if (persistAudit) audit.save(migrationId, it) }
    }

    /** Completes formal M12 for internal callers; M5 uses only [runPage] with its own cursor. */
    internal suspend fun run(
        migrationId: String,
        onProgress: suspend (R16LocalReindexProgress) -> Unit = {},
    ): R16LocalReindexProgress {
        var current = runPage(migrationId)
        while (true) {
            onProgress(current.toProgress())
            if (current.complete) return current.toProgress()
            current = runPage(migrationId, current)
        }
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

private fun makeCheckpoint(
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

private fun R16LocalReindexCheckpoint.matches(descriptor: LocalMediaSnapshotDescriptor): Boolean {
    if (snapshotFingerprint != descriptor.fingerprint || snapshotCount != descriptor.count) {
        return false
    }
    if (processedCount > descriptor.count) return false
    return (processedCount == 0L) == (lastSourceKey == null)
}

private fun validatePage(
    result: LocalMediaSnapshotPage,
    processed: Long,
    lastSourceKey: String?,
    pageSize: Int,
): List<SourceTrackObservation> {
    require(result.offset == processed) { "Local snapshot page offset does not match checkpoint" }
    require(result.observations.size <= pageSize) {
        "Local snapshot page exceeds the requested page size"
    }
    result.observations.forEach { observation ->
        require(
            observation.sourceKind == SourceKind.LOCAL_FILE &&
                observation.sourceKey.itemType == SourceItemType.LOCAL_FILE
        ) {
            "M12 accepts only exact local-file observations"
        }
    }
    val keys = result.observations.map(SourceTrackObservation::stableKey)
    require(keys.size == keys.toSet().size) { "M12 snapshot page contains duplicate source keys" }
    require(keys.zipWithNext().all { (first, second) -> first < second }) {
        "M12 snapshot page is not in stable source order"
    }
    if (processed > 0L && keys.isNotEmpty()) {
        require(lastSourceKey != null && lastSourceKey < keys.first()) {
            "M12 snapshot page does not resume after the prior source key"
        }
    }
    return result.observations
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

private const val UNIT_SEPARATOR = "\u001f"
