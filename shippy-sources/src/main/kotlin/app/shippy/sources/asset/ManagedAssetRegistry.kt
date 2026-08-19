/*
 * Copyright (c) 2026 Auxio Project
 * ManagedAssetRegistry.kt is part of Auxio.
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
package app.shippy.sources.asset

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId

data class ManagedAssetEvidence(
    val locationType: String,
    val location: AssetLocation,
    val documentId: String? = null,
    val mediaStoreId: Long? = null,
    val normalizedPathToken: String? = null,
    val contentLength: Long? = null,
    val lastModifiedEpochMs: Long? = null,
    val checksum: ContentChecksum? = null,
    val fingerprint: String? = null,
    val downloadJobId: String? = null,
) {
    init {
        require(locationType.isNotBlank()) { "Managed asset location type cannot be blank" }
        require(documentId == null || documentId.isNotBlank()) {
            "Managed asset document ID cannot be blank"
        }
        require(mediaStoreId == null || mediaStoreId >= 0) {
            "Managed asset MediaStore ID cannot be negative"
        }
        require(normalizedPathToken == null || normalizedPathToken.isNotBlank()) {
            "Managed asset path token cannot be blank"
        }
        require(contentLength == null || contentLength >= 0) {
            "Managed asset length cannot be negative"
        }
        require(lastModifiedEpochMs == null || lastModifiedEpochMs >= 0) {
            "Managed asset last-modified time cannot be negative"
        }
        require(fingerprint == null || fingerprint.isNotBlank()) {
            "Managed asset fingerprint cannot be blank"
        }
        require(downloadJobId == null || downloadJobId.isNotBlank()) {
            "Managed asset download job ID cannot be blank"
        }
    }
}

data class ManagedAssetRecord(
    val assetId: MediaAssetId,
    val recordingId: RecordingId,
    val evidence: ManagedAssetEvidence,
    val verified: Boolean,
)

enum class ManagedAssetMatchEvidence {
    EXACT_LOCATION,
    DOCUMENT_ID,
    MEDIASTORE_ID,
    DOWNLOAD_JOB_ID,
    VERIFIED_PATH_STAT,
    CONTENT_CHECKSUM,
    AUDIO_FINGERPRINT,
}

sealed interface ManagedAssetMatch {
    data class Exact(val record: ManagedAssetRecord, val evidence: Set<ManagedAssetMatchEvidence>) :
        ManagedAssetMatch

    data class Probable(
        val candidates: List<ManagedAssetRecord>,
        val evidence: Set<ManagedAssetMatchEvidence>,
    ) : ManagedAssetMatch

    data object None : ManagedAssetMatch
}

/**
 * Classifies only exact storage/content evidence; title/artist similarity is intentionally absent.
 */
class ManagedAssetRegistry(records: Collection<ManagedAssetRecord>) {
    private val records = records.distinctBy { it.assetId }

    fun classify(scanned: ManagedAssetEvidence): ManagedAssetMatch {
        val direct = linkedMapOf<ManagedAssetRecord, MutableSet<ManagedAssetMatchEvidence>>()
        fun directMatches(
            evidence: ManagedAssetMatchEvidence,
            predicate: (ManagedAssetEvidence) -> Boolean,
        ) {
            records
                .filter { predicate(it.evidence) }
                .forEach { record -> direct.getOrPut(record) { linkedSetOf() } += evidence }
        }

        directMatches(ManagedAssetMatchEvidence.EXACT_LOCATION) {
            it.locationType == scanned.locationType && it.location == scanned.location
        }
        scanned.documentId?.let { documentId ->
            directMatches(ManagedAssetMatchEvidence.DOCUMENT_ID) { it.documentId == documentId }
        }
        scanned.mediaStoreId?.let { mediaStoreId ->
            directMatches(ManagedAssetMatchEvidence.MEDIASTORE_ID) {
                it.mediaStoreId == mediaStoreId
            }
        }
        scanned.downloadJobId?.let { jobId ->
            directMatches(ManagedAssetMatchEvidence.DOWNLOAD_JOB_ID) { it.downloadJobId == jobId }
        }
        direct.singleExactOrProbable()?.let {
            return it
        }

        val verified = linkedMapOf<ManagedAssetRecord, MutableSet<ManagedAssetMatchEvidence>>()
        fun verifiedMatches(
            evidence: ManagedAssetMatchEvidence,
            predicate: (ManagedAssetEvidence) -> Boolean,
        ) {
            records
                .filter { it.verified && predicate(it.evidence) }
                .forEach { record -> verified.getOrPut(record) { linkedSetOf() } += evidence }
        }
        if (
            scanned.normalizedPathToken != null &&
                scanned.contentLength != null &&
                scanned.lastModifiedEpochMs != null
        ) {
            verifiedMatches(ManagedAssetMatchEvidence.VERIFIED_PATH_STAT) {
                it.normalizedPathToken == scanned.normalizedPathToken &&
                    it.contentLength == scanned.contentLength &&
                    it.lastModifiedEpochMs == scanned.lastModifiedEpochMs
            }
        }
        scanned.checksum?.let { checksum ->
            verifiedMatches(ManagedAssetMatchEvidence.CONTENT_CHECKSUM) {
                it.checksum == checksum &&
                    (scanned.contentLength == null || it.contentLength == scanned.contentLength)
            }
        }
        scanned.fingerprint?.let { fingerprint ->
            verifiedMatches(ManagedAssetMatchEvidence.AUDIO_FINGERPRINT) {
                it.fingerprint == fingerprint &&
                    (scanned.contentLength == null || it.contentLength == scanned.contentLength)
            }
        }
        verified.singleExactOrProbable()?.let {
            return it
        }

        val probable =
            records.filter { record ->
                (scanned.checksum != null && record.evidence.checksum == scanned.checksum) ||
                    (scanned.fingerprint != null &&
                        record.evidence.fingerprint == scanned.fingerprint) ||
                    (scanned.normalizedPathToken != null &&
                        record.evidence.normalizedPathToken == scanned.normalizedPathToken)
            }
        return if (probable.isEmpty()) {
            ManagedAssetMatch.None
        } else {
            ManagedAssetMatch.Probable(probable.sortedBy { it.assetId.value }, emptySet())
        }
    }
}

private fun Map<ManagedAssetRecord, Set<ManagedAssetMatchEvidence>>.singleExactOrProbable():
    ManagedAssetMatch? {
    if (isEmpty()) return null
    val evidence = values.flatten().toSet()
    return if (size == 1) {
        ManagedAssetMatch.Exact(keys.single(), evidence)
    } else {
        ManagedAssetMatch.Probable(keys.sortedBy { it.assetId.value }, evidence)
    }
}
