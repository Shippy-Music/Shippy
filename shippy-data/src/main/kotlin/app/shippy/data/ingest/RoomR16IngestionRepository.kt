/*
 * Copyright (c) 2026 Auxio Project
 * RoomR16IngestionRepository.kt is part of Auxio.
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
package app.shippy.data.ingest

import androidx.room.withTransaction
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.MetadataNormalizer
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.ExternalIdentifierEntity
import app.shippy.data.db.entity.IdentityDecisionEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class RoomR16IngestionRepository(private val database: ShippyR16Database) :
    R16IngestionRepository {
    override suspend fun <T> transaction(block: suspend R16IngestionSession.() -> T): T =
        database.withTransaction { block(RoomR16IngestionSession(database)) }
}

private class RoomR16IngestionSession(private val database: ShippyR16Database) :
    R16IngestionSession {
    override suspend fun exactSource(sourceKey: SourceKey): R16ExistingSource? =
        database
            .sourceDao()
            .exact(sourceKey.providerId.value, sourceKey.itemType.name, sourceKey.sourceItemId)
            ?.let { R16ExistingSource(sourceKey, it.recordingId?.let(::RecordingId)) }

    override suspend fun managedAssetCandidates(asset: R16ObservedAsset): List<R16ManagedAsset> =
        database
            .assetDao()
            .managedCandidates(
                locationType = asset.locationType,
                location = asset.location.opaqueHandle,
                documentId = asset.documentId,
                mediaStoreId = asset.mediaStoreId,
                normalizedPathToken = asset.normalizedPathToken,
                contentChecksum = asset.checksum?.encode(),
                fingerprintId = asset.fingerprintId,
                downloadJobId = asset.downloadJobId,
                limit = MAX_CANDIDATES,
            )
            .map(MediaAssetEntity::toManagedAsset)

    override suspend fun identityCandidates(
        observation: R16SourceObservation,
        features: MatchingFeatures,
    ): List<R16IdentityCandidate> {
        val identifiers =
            observation.externalIdentifiers.mapTo(linkedSetOf()) { "${it.kind.name}:${it.value}" }
        val recordings = linkedMapOf<String, RecordingEntity>()
        if (identifiers.isNotEmpty()) {
            database
                .recordingDao()
                .candidatesByExternalIdentifier(identifiers, MAX_CANDIDATES)
                .forEach { recordings[it.recordingId] = it }
        }
        if (features.fingerprintHashes.isNotEmpty()) {
            database
                .recordingDao()
                .candidatesByFingerprint(features.fingerprintHashes, MAX_CANDIDATES)
                .forEach { recordings[it.recordingId] = it }
        }
        observation.title?.let { title ->
            database.recordingDao().candidatesByTitle(title, MAX_CANDIDATES).forEach {
                recordings[it.recordingId] = it
            }
        }
        val sourceSubjectId = StableIngestionIds.source(observation.sourceKey)
        val rejected =
            database.identityDao().rejectionsFor(SUBJECT_SOURCE, sourceSubjectId).mapTo(
                hashSetOf()
            ) {
                it.rejectedRecordingId
            }
        return recordings.values.take(MAX_CANDIDATES).map { recording ->
            R16IdentityCandidate(
                recordingId = RecordingId(recording.recordingId),
                features = recording.toMatchingFeatures(database),
                explicitlyRejected = recording.recordingId in rejected,
            )
        }
    }

    override suspend fun persist(command: R16IngestionCommand) {
        val observation = command.observation
        val now = observation.capturedAt.toEpochMilli()
        val sourceId = StableIngestionIds.source(observation.sourceKey)
        val observationId = StableIngestionIds.observation(observation)
        val assetId =
            if (
                observation.asset != null &&
                    command.recordingId != null &&
                    command.assetWriteMode != R16AssetWriteMode.NONE
            ) {
                command.exactManagedAssetId?.value
                    ?: StableIngestionIds.asset(observation.sourceKey)
            } else {
                null
            }

        command.newRecording?.let { draft ->
            val recordingId = requireNotNull(command.recordingId).value
            val existing = database.recordingDao().get(recordingId)
            if (existing == null) {
                val artistNames = observation.artistNames.ifEmpty { listOf(draft.primaryArtist) }
                val artists =
                    artistNames.distinct().map { name ->
                        ArtistEntity(
                            artistId = StableIngestionIds.artist(name),
                            canonicalName = name,
                            sortName = null,
                            disambiguation = null,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                        )
                    }
                val artistIds = artists.associateBy(ArtistEntity::canonicalName)
                val durable = observation.asset?.kind?.isDurable == true
                database
                    .recordingDao()
                    .upsertRecordingGraph(
                        recording =
                            RecordingEntity(
                                recordingId = recordingId,
                                canonicalTitle = draft.title,
                                durationMs = draft.durationMs,
                                versionKind = draft.version.kind.name,
                                versionLabel = draft.version.label,
                                explicitness = observation.explicitness.name,
                                preferredReleaseId = null,
                                preferredArtworkId = null,
                                retentionKind =
                                    if (durable) RETENTION_DURABLE else RETENTION_TRANSIENT,
                                retainedUntilEpochMs =
                                    if (durable) null else now + TRANSIENT_RETENTION.toMillis(),
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now,
                            ),
                        artists = artists,
                        credits =
                            artistNames.mapIndexed { index, name ->
                                RecordingArtistCreditEntity(
                                    recordingId = recordingId,
                                    position = index,
                                    artistId = requireNotNull(artistIds[name]).artistId,
                                    creditedName = name,
                                    joinPhrase = if (index == artistNames.lastIndex) "" else ", ",
                                )
                            },
                    )
            } else {
                check(
                    existing.canonicalTitle == draft.title &&
                        existing.durationMs == draft.durationMs &&
                        existing.versionKind == draft.version.kind.name
                ) {
                    "Generated recording identity already belongs to different metadata"
                }
            }
        }

        val observationEntity =
            MetadataObservationEntity(
                observationId = observationId,
                sourceType = observation.sourceKind.toMetadataSourceType(),
                sourceReferenceId = sourceId,
                assetId = assetId,
                title = observation.title,
                artistCreditJson = JSONArray(observation.artistNames).toString(),
                releaseTitle = observation.releaseTitle,
                releaseArtist = null,
                durationMs = observation.durationMs,
                artworkJson = JSONArray(observation.artwork.map { it.value }).toString(),
                releaseYear = null,
                trackNumber = null,
                discNumber = null,
                genresJson = null,
                versionHintsJson =
                    JSONArray(
                            buildList {
                                observation.version.label?.let(::add)
                                addAll(observation.version.traits.map { it.name })
                            }
                        )
                        .toString(),
                externalIdsJson =
                    JSONArray()
                        .apply {
                            observation.externalIdentifiers
                                .sortedWith(compareBy({ it.kind.name }, { it.value }))
                                .forEach { identifier ->
                                    put(
                                        JSONObject()
                                            .put("kind", identifier.kind.name)
                                            .put("value", identifier.value)
                                    )
                                }
                        }
                        .toString(),
                extrasJson = JSONObject().put("sourceKind", observation.sourceKind.name).toString(),
                capturedAtEpochMs = now,
            )
        val source =
            SourceReferenceEntity(
                sourceReferenceId = sourceId,
                recordingId = command.recordingId?.value,
                providerId = observation.sourceKey.providerId.value,
                sourceKind = observation.sourceKind.toReferenceKind().name,
                itemType = observation.sourceKey.itemType.name,
                sourceItemId = observation.sourceKey.sourceItemId,
                originalUrl = observation.originalUrl,
                availabilityState = observation.availabilityState(assetId != null),
                availabilityCheckedAtEpochMs = now,
                availabilityExpiresAtEpochMs = null,
                failureKind = null,
                failureRetryable = null,
                identityStatus =
                    if (command.recordingId == null) "UNRESOLVED" else "AUTOMATICALLY_LINKED",
                rawMetadataObservationId = observationId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        val stored = database.sourceDao().ingestExact(observationEntity, source)
        command.recordingId?.let { recordingId ->
            database
                .sourceDao()
                .link(stored.sourceReferenceId, recordingId.value, "AUTOMATICALLY_LINKED", now)
        }

        if (assetId != null) {
            val observedAsset = requireNotNull(observation.asset)
            val recordingId = requireNotNull(command.recordingId).value
            database
                .assetDao()
                .exactLocation(observedAsset.locationType, observedAsset.location.opaqueHandle)
                ?.let { existing ->
                    check(existing.assetId == assetId) {
                        "Managed asset location already belongs to another asset"
                    }
                }
            val existing = database.assetDao().get(assetId)
            check(existing == null || existing.recordingId == recordingId) {
                "Managed asset identity already belongs to another recording"
            }
            val incoming =
                observedAsset.toEntity(
                    assetId = assetId,
                    recordingId = recordingId,
                    sourceReferenceId = stored.sourceReferenceId,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                )
            database.assetDao().upsert(existing?.mergeObservation(incoming) ?: incoming)
        }

        command.recordingId?.let { recordingId ->
            database
                .recordingDao()
                .insertExternalIdentifiers(
                    observation.externalIdentifiers.map { identifier ->
                        ExternalIdentifierEntity(
                            externalIdentifierId =
                                StableIngestionIds.externalIdentifier(
                                    recordingId,
                                    identifier.kind.name,
                                    identifier.value,
                                ),
                            ownerType = "RECORDING",
                            ownerId = recordingId.value,
                            scheme = identifier.kind.name,
                            value = identifier.value,
                            verified = observation.sourceKind == SourceKind.LOCAL_FILE,
                            sourceObservationId = observationId,
                            createdAtEpochMs = now,
                        )
                    }
                )
            if (command.newRecording != null) {
                database
                    .legacyImportDao()
                    .upsertProvenance(observation.provenance(recordingId, observationId, now))
            }
            database.searchDao().refresh(recordingId.value)
        }

        command.reviewCandidateIds
            .sortedBy { it.value }
            .forEach { candidateId ->
                database
                    .identityDao()
                    .insertDecisionIfAbsent(
                        IdentityDecisionEntity(
                            decisionId = StableIngestionIds.review(sourceId, candidateId),
                            subjectType = SUBJECT_SOURCE,
                            subjectId = sourceId,
                            targetRecordingId = candidateId.value,
                            decisionKind = "REVIEW",
                            confidence = null,
                            evidenceJson = "[]",
                            userConfirmed = false,
                            createdAtEpochMs = now,
                        )
                    )
            }
        if (command.resolution == "VERIFIED_IDENTITY" && command.recordingId != null) {
            database
                .identityDao()
                .insertDecisionIfAbsent(
                    IdentityDecisionEntity(
                        decisionId = StableIngestionIds.autoLink(sourceId, command.recordingId),
                        subjectType = SUBJECT_SOURCE,
                        subjectId = sourceId,
                        targetRecordingId = command.recordingId.value,
                        decisionKind = "AUTO_LINK",
                        confidence = command.identityEvidence?.score,
                        evidenceJson = command.identityEvidence.toEvidenceJson(),
                        userConfirmed = false,
                        createdAtEpochMs = now,
                    )
                )
        }
    }
}

private suspend fun RecordingEntity.toMatchingFeatures(
    database: ShippyR16Database
): MatchingFeatures {
    val credits = database.recordingDao().artistCredits(recordingId)
    val release = preferredReleaseId?.let { database.recordingDao().release(it) }
    val identifiers = database.recordingDao().externalIdentifiers(recordingId)
    val sources = database.sourceDao().forRecording(recordingId)
    val assets = database.assetDao().forRecording(recordingId)
    return MatchingFeatures(
        normalizedTitle = MetadataNormalizer.comparisonKey(canonicalTitle),
        normalizedPrimaryArtist =
            MetadataNormalizer.comparisonKey(credits.firstOrNull()?.creditedName),
        normalizedArtistSet =
            credits.mapNotNullTo(linkedSetOf()) {
                MetadataNormalizer.comparisonKey(it.creditedName)
            },
        normalizedRelease = MetadataNormalizer.comparisonKey(release?.canonicalTitle),
        durationMs = durationMs,
        version =
            RecordingVersion(
                kind = enumValueOrDefault(versionKind, VersionKind.UNKNOWN),
                label = versionLabel,
            ),
        explicitness = enumValueOrDefault(explicitness, Explicitness.UNKNOWN),
        isrcs = identifiers.values(ExternalIdentifierKind.ISRC),
        musicBrainzRecordingIds = identifiers.values(ExternalIdentifierKind.MUSICBRAINZ_RECORDING),
        acoustIds = identifiers.values(ExternalIdentifierKind.ACOUST_ID),
        sourceKeys =
            sources.mapNotNullTo(linkedSetOf()) { source ->
                val itemType = runCatching { SourceItemType.valueOf(source.itemType) }.getOrNull()
                itemType?.let {
                    SourceKey(
                        providerId = app.shippy.core.identity.ProviderId(source.providerId),
                        itemType = it,
                        sourceItemId = source.sourceItemId,
                    )
                }
            },
        fingerprintHashes = assets.mapNotNullTo(linkedSetOf()) { it.fingerprintId },
    )
}

private fun MediaAssetEntity.toManagedAsset(): R16ManagedAsset =
    R16ManagedAsset(
        assetId = MediaAssetId(assetId),
        recordingId = RecordingId(recordingId),
        evidence =
            R16ObservedAsset(
                kind =
                    enumValueOrDefault(assetKind, app.shippy.core.asset.MediaAssetKind.LOCAL_FILE),
                location = AssetLocation(location),
                locationType = locationType,
                documentId = documentId,
                mediaStoreId = mediaStoreId,
                normalizedPathToken = normalizedPathToken,
                downloadJobId = downloadJobId,
                lastModifiedEpochMs = lastModifiedEpochMs,
                technical =
                    AudioTechnicalMetadata(
                        mimeType = mimeType,
                        codec = codec,
                        bitrateBps =
                            bitrateBps?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
                        sampleRateHz = sampleRateHz?.takeIf { it > 0 },
                        channelCount = channelCount?.takeIf { it > 0 },
                        contentLength = contentLength,
                    ),
                checksum = contentChecksum?.decodeChecksum(),
                fingerprintId = fingerprintId,
                verifiedAt = lastVerifiedAtEpochMs?.let(java.time.Instant::ofEpochMilli),
            ),
        verified = assetState == "AVAILABLE" && lastVerifiedAtEpochMs != null,
    )

private fun R16ObservedAsset.toEntity(
    assetId: String,
    recordingId: String,
    sourceReferenceId: String,
    createdAtEpochMs: Long,
    updatedAtEpochMs: Long,
) =
    MediaAssetEntity(
        assetId = assetId,
        recordingId = recordingId,
        sourceReferenceId = sourceReferenceId,
        assetKind = kind.name,
        assetState = if (verifiedAt == null) "VERIFYING" else "AVAILABLE",
        locationType = locationType,
        location = location.opaqueHandle,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        displayName = null,
        mimeType = technical.mimeType,
        container = null,
        codec = technical.codec,
        bitrateBps = technical.bitrateBps?.toLong(),
        sampleRateHz = technical.sampleRateHz,
        channelCount = technical.channelCount,
        contentLength = technical.contentLength,
        contentChecksum = checksum?.encode(),
        fingerprintId = fingerprintId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lastVerifiedAtEpochMs = verifiedAt?.toEpochMilli(),
        normalizedPathToken = normalizedPathToken,
        lastModifiedEpochMs = lastModifiedEpochMs,
        downloadJobId = downloadJobId,
    )

private fun R16SourceObservation.provenance(
    recordingId: RecordingId,
    observationId: String,
    selectedAtEpochMs: Long,
): List<CanonicalFieldProvenanceEntity> {
    val sourceType = sourceKind.toMetadataSourceType()
    return buildList {
            if (title != null) add("TITLE")
            if (artistNames.isNotEmpty()) add("ARTIST_CREDIT")
            if (durationMs != null) add("DURATION")
            add("VERSION")
            add("EXPLICITNESS")
            if (releaseTitle != null) add("RELEASE")
        }
        .map { field ->
            CanonicalFieldProvenanceEntity(
                recordingId = recordingId.value,
                fieldName = field,
                selectedSourceType = sourceType,
                selectedSourceId = observationId,
                confidence = 1.0,
                selectedAtEpochMs = selectedAtEpochMs,
            )
        }
}

private fun R16SourceObservation.availabilityState(assetPersisted: Boolean): String =
    when {
        assetPersisted && asset?.verifiedAt != null -> "AVAILABLE"
        sourceKind == SourceKind.JIOSAAVN ||
            sourceKind == SourceKind.YOUTUBE_MUSIC ||
            sourceKind == SourceKind.YOUTUBE -> "RESOLVABLE"
        else -> "UNKNOWN"
    }

private fun MediaAssetEntity.mergeObservation(incoming: MediaAssetEntity): MediaAssetEntity {
    check(assetId == incoming.assetId && recordingId == incoming.recordingId) {
        "Managed asset observation cannot change identity"
    }
    return incoming.copy(
        sourceReferenceId = sourceReferenceId ?: incoming.sourceReferenceId,
        assetKind = assetKind,
        assetState =
            if (assetState == "AVAILABLE" || incoming.assetState == "AVAILABLE") {
                "AVAILABLE"
            } else {
                incoming.assetState
            },
        documentId = incoming.documentId ?: documentId,
        mediaStoreId = incoming.mediaStoreId ?: mediaStoreId,
        displayName = incoming.displayName ?: displayName,
        mimeType = incoming.mimeType ?: mimeType,
        container = incoming.container ?: container,
        codec = incoming.codec ?: codec,
        bitrateBps = incoming.bitrateBps ?: bitrateBps,
        sampleRateHz = incoming.sampleRateHz ?: sampleRateHz,
        channelCount = incoming.channelCount ?: channelCount,
        contentLength = incoming.contentLength ?: contentLength,
        contentChecksum = incoming.contentChecksum ?: contentChecksum,
        fingerprintId = incoming.fingerprintId ?: fingerprintId,
        lastVerifiedAtEpochMs = incoming.lastVerifiedAtEpochMs ?: lastVerifiedAtEpochMs,
        normalizedPathToken = incoming.normalizedPathToken ?: normalizedPathToken,
        lastModifiedEpochMs = incoming.lastModifiedEpochMs ?: lastModifiedEpochMs,
        downloadJobId = downloadJobId ?: incoming.downloadJobId,
    )
}

private fun SourceKind.toMetadataSourceType(): String =
    when (this) {
        SourceKind.LOCAL_FILE,
        SourceKind.SHIPPY_DOWNLOAD -> "EMBEDDED_FILE"
        SourceKind.JIOSAAVN,
        SourceKind.YOUTUBE_MUSIC,
        SourceKind.YOUTUBE -> "MUSIC_PROVIDER"
        else -> "GENERIC_PROVIDER"
    }

private fun SourceKind.toReferenceKind(): SourceKind =
    if (this == SourceKind.YOUTUBE_MUSIC) SourceKind.YOUTUBE else this

private val app.shippy.core.asset.MediaAssetKind.isDurable: Boolean
    get() =
        this == app.shippy.core.asset.MediaAssetKind.LOCAL_FILE ||
            this == app.shippy.core.asset.MediaAssetKind.SHIPPY_DOWNLOAD

private fun List<ExternalIdentifierEntity>.values(kind: ExternalIdentifierKind): Set<String> =
    filter { it.scheme == kind.name }.mapTo(linkedSetOf()) { it.value }

private fun ContentChecksum.encode(): String = "$algorithm:$value"

private fun String.decodeChecksum(): ContentChecksum {
    val separator = indexOf(':')
    return if (separator > 0 && separator < lastIndex) {
        ContentChecksum(substring(0, separator), substring(separator + 1))
    } else {
        ContentChecksum("LEGACY", this)
    }
}

private fun app.shippy.core.identitymatch.MatchAssessment?.toEvidenceJson(): String =
    JSONArray()
        .apply {
            this@toEvidenceJson?.evidence?.forEach { evidence ->
                put(
                    JSONObject()
                        .put("kind", evidence.kind.name)
                        .put("score", evidence.score)
                        .put("detail", evidence.detail)
                )
            }
        }
        .toString()

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

private object StableIngestionIds {
    private val namespace = UUID.fromString("63bb47b0-ec0c-4d3f-8a0d-bf5d62554ae7")

    fun source(key: SourceKey): String =
        mapped("source", key.providerId.value, key.itemType.name, key.sourceItemId)

    fun observation(observation: R16SourceObservation): String =
        mapped(
            "observation",
            source(observation.sourceKey),
            observation.title.orEmpty(),
            observation.artistNames.joinToString(UNIT_SEPARATOR),
            observation.releaseTitle.orEmpty(),
            observation.durationMs?.toString().orEmpty(),
            observation.version.kind.name,
            observation.version.label.orEmpty(),
            observation.explicitness.name,
            observation.artwork.joinToString(UNIT_SEPARATOR) { it.value },
            observation.externalIdentifiers
                .sortedWith(compareBy({ it.kind.name }, { it.value }))
                .joinToString(UNIT_SEPARATOR) { "${it.kind.name}:${it.value}" },
            observation.asset?.location?.opaqueHandle.orEmpty(),
            observation.asset?.technical?.contentLength?.toString().orEmpty(),
            observation.asset?.checksum?.encode().orEmpty(),
            observation.asset?.fingerprintId.orEmpty(),
        )

    fun asset(key: SourceKey): String =
        mapped("asset", key.providerId.value, key.itemType.name, key.sourceItemId)

    fun artist(name: String): String =
        mapped("artist", MetadataNormalizer.comparisonKey(name) ?: name)

    fun externalIdentifier(recordingId: RecordingId, scheme: String, value: String): String =
        mapped("external-id", recordingId.value, scheme, value)

    fun review(sourceId: String, recordingId: RecordingId): String =
        mapped("review", sourceId, recordingId.value)

    fun autoLink(sourceId: String, recordingId: RecordingId): String =
        mapped("auto-link", sourceId, recordingId.value)

    private fun mapped(kind: String, vararg parts: String): String {
        val name = (listOf(kind) + parts).joinToString(UNIT_SEPARATOR)
        val namespaceBytes =
            ByteBuffer.allocate(16)
                .putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array()
        val digest =
            MessageDigest.getInstance("SHA-1").run {
                update(namespaceBytes)
                digest(name.toByteArray(StandardCharsets.UTF_8))
            }
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest, 0, 16)
        return UUID(bytes.long, bytes.long).toString()
    }
}

private const val MAX_CANDIDATES = 64
private const val SUBJECT_SOURCE = "SOURCE"
private const val RETENTION_DURABLE = "DURABLE"
private const val RETENTION_TRANSIENT = "TRANSIENT"
private const val UNIT_SEPARATOR = "\u001f"
private val TRANSIENT_RETENTION: Duration = Duration.ofDays(7)
