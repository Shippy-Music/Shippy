/*
 * Copyright (c) 2026 Auxio Project
 * DownloadJobRepository.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadEvent
import org.oxycblt.auxio.shippy.download.DownloadFailure
import org.oxycblt.auxio.shippy.download.DownloadJob
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadReducer
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.download.DownloadTransition
import org.oxycblt.auxio.shippy.download.PendingDownloadDocument

data class PersistedDownload(
    val job: DownloadJob,
    val track: Track,
    val pendingDocument: PendingDownloadDocument?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

interface DownloadJobRepository {
    fun observeAll(): Flow<List<PersistedDownload>>

    fun observeAvailable(): Flow<List<PersistedDownload>>

    fun observeForTrack(trackId: TrackId): Flow<List<PersistedDownload>>

    fun observeForTracks(trackIds: List<TrackId>): Flow<List<PersistedDownload>>

    suspend fun getAll(): List<PersistedDownload>

    suspend fun get(jobId: DownloadJobId): PersistedDownload?

    suspend fun getLatestForTrack(trackId: TrackId): PersistedDownload?

    suspend fun hasAvailableForTrack(trackId: TrackId): Boolean

    suspend fun create(
        jobId: DownloadJobId,
        track: Track,
        requestedCandidateId: CandidateId,
        nowEpochMs: Long,
    ): PersistedDownload

    suspend fun apply(
        jobId: DownloadJobId,
        event: DownloadEvent,
        nowEpochMs: Long,
    ): DownloadTransition

    suspend fun setPendingDocument(
        jobId: DownloadJobId,
        document: PendingDownloadDocument?,
        nowEpochMs: Long,
    )

    suspend fun delete(jobId: DownloadJobId)
}

@Singleton
internal class RoomDownloadJobRepository @Inject constructor(private val dao: DownloadJobDao) :
    DownloadJobRepository {
    private val reducer = DownloadReducer()
    private val transitionMutex = Mutex()

    override fun observeAll(): Flow<List<PersistedDownload>> =
        dao.observeAll().map { jobs -> jobs.map(StoredDownloadJob::toDomain) }

    override fun observeAvailable(): Flow<List<PersistedDownload>> =
        dao.observeAvailable().map { jobs -> jobs.map(StoredDownloadJob::toDomain) }

    override fun observeForTrack(trackId: TrackId): Flow<List<PersistedDownload>> =
        dao.observeForTrack(trackId.value).map { jobs -> jobs.map(StoredDownloadJob::toDomain) }

    override fun observeForTracks(trackIds: List<TrackId>): Flow<List<PersistedDownload>> {
        val chunks = trackIds.distinct().chunked(ROOM_QUERY_ID_CHUNK_SIZE)
        if (chunks.isEmpty()) return flowOf(emptyList())
        return combine(chunks.map { chunk -> dao.observeForTracks(chunk.map(TrackId::value)) }) {
            storedChunks ->
            storedChunks
                .flatMap { jobs -> jobs.map(StoredDownloadJob::toDomain) }
                .distinctBy { it.job.id }
        }
    }

    override suspend fun getAll(): List<PersistedDownload> =
        dao.getAll().map(StoredDownloadJob::toDomain)

    override suspend fun get(jobId: DownloadJobId): PersistedDownload? =
        dao.get(jobId.value)?.toDomain()

    override suspend fun getLatestForTrack(trackId: TrackId): PersistedDownload? =
        dao.getLatestForTrack(trackId.value)?.toDomain()

    override suspend fun hasAvailableForTrack(trackId: TrackId): Boolean =
        dao.hasAvailableForTrack(trackId.value)

    override suspend fun create(
        jobId: DownloadJobId,
        track: Track,
        requestedCandidateId: CandidateId,
        nowEpochMs: Long,
    ): PersistedDownload {
        require(nowEpochMs >= 0) { "Creation timestamp cannot be negative" }
        require(track.candidates.any { it.id == requestedCandidateId }) {
            "Requested candidate must belong to the download track"
        }
        val job = DownloadJob(jobId, track.id, requestedCandidateId)
        val entity = job.toEntity(track, null, nowEpochMs, nowEpochMs)
        val candidates =
            track.candidates.mapIndexed { position, candidate ->
                candidate.toEntity(jobId, position)
            }
        dao.insert(entity, candidates)
        return StoredDownloadJob(entity, candidates).toDomain()
    }

    override suspend fun apply(
        jobId: DownloadJobId,
        event: DownloadEvent,
        nowEpochMs: Long,
    ): DownloadTransition =
        transitionMutex.withLock {
            require(nowEpochMs >= 0) { "Update timestamp cannot be negative" }
            val stored = dao.get(jobId.value) ?: error("Download job does not exist")
            when (val transition = reducer.apply(stored.job.toDomainJob(), event)) {
                is DownloadTransition.Applied -> {
                    dao.updateJob(
                        transition.job.toEntity(
                            track = stored.toDomainTrack(),
                            pending = stored.job.toPendingDocument(),
                            createdAtEpochMs = stored.job.createdAtEpochMs,
                            updatedAtEpochMs = nowEpochMs,
                        )
                    )
                    transition
                }
                is DownloadTransition.Rejected -> transition
            }
        }

    override suspend fun setPendingDocument(
        jobId: DownloadJobId,
        document: PendingDownloadDocument?,
        nowEpochMs: Long,
    ) {
        transitionMutex.withLock {
            val stored = dao.get(jobId.value) ?: error("Download job does not exist")
            dao.updateJob(
                stored.job.copy(
                    pendingUri = document?.contentUri,
                    pendingDisplayName = document?.displayName,
                    pendingMimeType = document?.mimeType,
                    updatedAtEpochMs = nowEpochMs,
                )
            )
        }
    }

    override suspend fun delete(jobId: DownloadJobId) {
        check(dao.delete(jobId.value) == 1) { "Download job does not exist" }
    }
}

// Android SQLite historically limits one statement to 999 bind parameters.
private const val ROOM_QUERY_ID_CHUNK_SIZE = 900

internal fun StoredDownloadJob.toDomain(): PersistedDownload =
    PersistedDownload(
        job = job.toDomainJob(),
        track = toDomainTrack(),
        pendingDocument = job.toPendingDocument(),
        createdAtEpochMs = job.createdAtEpochMs,
        updatedAtEpochMs = job.updatedAtEpochMs,
    )

private fun DownloadJobEntity.toDomainJob(): DownloadJob {
    val artifact =
        artifactUri?.let { uri ->
            DownloadArtifact(
                contentUri = uri,
                contentLength = requireNotNull(artifactLength),
                mimeType = artifactMimeType,
                verifiedAtEpochMs = requireNotNull(artifactVerifiedAtEpochMs),
            )
        }
    val failure = failureCode?.let { DownloadFailure(it, failureMessage) }
    return DownloadJob(
        id = DownloadJobId(jobId),
        trackId = TrackId(trackId),
        candidateId = CandidateId(requestedCandidateId),
        state = DownloadState.valueOf(state),
        bytesTransferred = bytesTransferred,
        expectedBytes = expectedBytes,
        failure = failure,
        artifact = artifact,
    )
}

private fun StoredDownloadJob.toDomainTrack(): Track =
    Track(
        id = TrackId(job.trackId),
        realm = TrackRealm.valueOf(job.trackRealm),
        title = job.title,
        artists = decodeArtists(job.artists),
        album = job.album,
        durationMs = job.durationMs,
        version =
            TrackVersion(
                label = job.versionLabel,
                explicit = job.explicit,
                isLive = job.live,
                isRemix = job.remix,
            ),
        artwork = job.artwork,
        candidates =
            candidates.sortedBy(DownloadCandidateEntity::position).map { candidate ->
                candidate.toDomain(TrackId(job.trackId))
            },
    )

private fun DownloadCandidateEntity.toDomain(trackId: TrackId): TrackCandidate =
    TrackCandidate(
        id = CandidateId(candidateId),
        trackId = trackId,
        kind = CandidateKind.valueOf(kind),
        sourceId = sourceId,
        sourceItemId = sourceItemId,
        availability = CandidateAvailability.valueOf(availability),
        locator = locator,
        providerId = providerId?.let(::ProviderId),
        media =
            if (
                mimeType != null || container != null || bitrateBps != null || contentLength != null
            ) {
                MediaDescriptor(mimeType, container, bitrateBps, contentLength)
            } else {
                null
            },
    )

private fun DownloadJob.toEntity(
    track: Track,
    pending: PendingDownloadDocument?,
    createdAtEpochMs: Long,
    updatedAtEpochMs: Long,
): DownloadJobEntity =
    DownloadJobEntity(
        jobId = id.value,
        trackId = trackId.value,
        requestedCandidateId = candidateId.value,
        trackRealm = track.realm.name,
        title = track.title,
        artists = encodeArtists(track.artists),
        album = track.album,
        durationMs = track.durationMs,
        versionLabel = track.version.label,
        explicit = track.version.explicit,
        live = track.version.isLive,
        remix = track.version.isRemix,
        artwork = track.artwork,
        state = state.name,
        bytesTransferred = bytesTransferred,
        expectedBytes = expectedBytes,
        failureCode = failure?.code,
        failureMessage = failure?.message,
        artifactUri = artifact?.contentUri,
        artifactLength = artifact?.contentLength,
        artifactMimeType = artifact?.mimeType,
        artifactVerifiedAtEpochMs = artifact?.verifiedAtEpochMs,
        pendingUri = pending?.contentUri,
        pendingDisplayName = pending?.displayName,
        pendingMimeType = pending?.mimeType,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

private fun TrackCandidate.toEntity(jobId: DownloadJobId, position: Int): DownloadCandidateEntity =
    DownloadCandidateEntity(
        jobId = jobId.value,
        candidateId = id.value,
        position = position,
        kind = kind.name,
        sourceId = sourceId,
        sourceItemId = sourceItemId,
        availability = availability.name,
        locator = locator,
        providerId = providerId?.value,
        mimeType = media?.mimeType,
        container = media?.container,
        bitrateBps = media?.bitrateBps,
        contentLength = media?.contentLength,
    )

private fun DownloadJobEntity.toPendingDocument(): PendingDownloadDocument? {
    val uri = pendingUri ?: return null
    return PendingDownloadDocument(
        contentUri = uri,
        displayName = requireNotNull(pendingDisplayName),
        mimeType = requireNotNull(pendingMimeType),
    )
}

internal fun encodeArtists(artists: List<String>): String = buildString {
    artists.forEach { artist ->
        append(artist.length)
        append(ARTIST_LENGTH_SEPARATOR)
        append(artist)
    }
}

internal fun decodeArtists(encoded: String): List<String> = buildList {
    var cursor = 0
    while (cursor < encoded.length) {
        val separator = encoded.indexOf(ARTIST_LENGTH_SEPARATOR, cursor)
        require(separator > cursor) { "Invalid persisted artist list" }
        val length =
            encoded.substring(cursor, separator).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid persisted artist length")
        require(length >= 0 && separator + 1 + length <= encoded.length) {
            "Invalid persisted artist length"
        }
        val start = separator + 1
        add(encoded.substring(start, start + length))
        cursor = start + length
    }
}

private const val ARTIST_LENGTH_SEPARATOR = ':'
