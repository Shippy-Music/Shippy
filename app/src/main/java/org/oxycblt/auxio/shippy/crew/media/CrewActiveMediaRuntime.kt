/*
 * Copyright (c) 2026 Shippy contributors
 * CrewActiveMediaRuntime.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaEntry
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload

@Singleton
class CrewActiveMediaRuntimeFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: CrewSettings,
    private val downloadRepository: DownloadJobRepository,
    private val temporaryIndex: CrewTemporaryMediaIndex,
) {
    suspend fun create(
        sessionId: CrewSessionId,
        localMemberId: CrewMemberId,
        stateProvider: () -> CrewState,
    ): CrewActiveMediaRuntime {
        val downloads =
            try {
                downloadRepository.getAll()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        return CrewActiveMediaRuntime(
            context = context,
            sessionId = sessionId,
            localMemberId = localMemberId,
            stateProvider = stateProvider,
            settings = settings,
            temporaryIndex = temporaryIndex,
            initialDownloads = CrewActiveMediaSelector.availableDownloads(downloads),
            observeDownloads = downloadRepository::observeAvailable,
        )
    }
}

/** One active Crew's private media composition. It never owns playback or transport discovery. */
class CrewActiveMediaRuntime internal constructor(
    private val context: Context,
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val stateProvider: () -> CrewState,
    private val settings: CrewSettings,
    private val temporaryIndex: CrewTemporaryMediaIndex,
    initialDownloads: Map<CrewActiveMediaSelector.DownloadKey, CrewActiveMediaSelector.DownloadSource>,
    private val observeDownloads: () -> kotlinx.coroutines.flow.Flow<List<PersistedDownload>>,
) : CrewAuthenticatedMediaLifecycle, AutoCloseable {
    private val policy = ActiveCrewPushPullPolicy()
    private val cache = CrewTemporaryMediaCache(File(context.cacheDir, "crew-media"))
    private val receiver = CrewMediaReceiver(sessionId, cache)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var availableDownloads = initialDownloads
    @Volatile private var closed = false

    private val router = CrewMediaSessionRouter(
        sessionId,
        localMemberId,
        policy,
        receiver,
        object : CrewMediaSessionCallbacks {
            override fun authorizeSupplierSource(
                transfer: CrewMediaTransferRef,
                requestingMemberId: CrewMemberId,
            ): CrewAuthorizedMediaSource? = authorize(transfer, requestingMemberId)

            override fun onTemporaryMediaComplete(
                manifest: CrewMediaManifest,
                supplyingMemberId: CrewMemberId,
                file: File,
            ): Boolean =
                manifest.sessionId == sessionId &&
                    manifest.transfer.targetMemberId == localMemberId &&
                    manifest.transfer.supplierMemberId == supplyingMemberId &&
                    temporaryIndex.complete(manifest, file)
        },
    )

    private val settingsListener = object : CrewSettings.Listener {
        override fun onPushPullEnabledChanged(enabled: Boolean) {
            if (!closed) policy.activate(sessionId, enabled)
        }
    }

    init {
        cache.beginSession(sessionId)
        temporaryIndex.beginSession(sessionId)
        policy.activate(sessionId, settings.pushPullEnabled)
        settings.registerListener(settingsListener)
        scope.launch {
            observeDownloads()
                .catch { availableDownloads = emptyMap() }
                .collect { availableDownloads = CrewActiveMediaSelector.availableDownloads(it) }
        }
    }

    override fun onPeerAttached(peer: CrewAuthenticatedMediaPeer) = router.onPeerAttached(peer)
    override fun onMediaFrame(peer: CrewAuthenticatedMediaPeer, frame: org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame) =
        router.onMediaFrame(peer, frame)
    override fun onPeerDetached(peer: CrewAuthenticatedMediaPeer) = router.onPeerDetached(peer)

    fun requestTemporaryMedia(supplier: CrewMemberId, transfer: CrewMediaTransferRef): CrewSendResult? =
        router.requestTemporaryMedia(supplier, transfer)

    fun cancelTemporaryMediaRequest(supplier: CrewMemberId, transfer: CrewMediaTransferRef): CrewSendResult? =
        router.cancelTemporaryMediaRequest(supplier, transfer)

    fun resumeSupplierTransfer(supplier: CrewMemberId, transfer: CrewMediaTransferRef) =
        router.resumeSupplierTransfer(supplier, transfer)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { settings.unregisterListener(settingsListener) }
        runCatching { policy.deactivate(sessionId) }
        runCatching { temporaryIndex.endSession(sessionId) }
        runCatching { cache.endSession(sessionId) }
        runCatching { scope.cancel() }
    }

    private fun authorize(transfer: CrewMediaTransferRef, requestingMemberId: CrewMemberId): CrewAuthorizedMediaSource? {
        val selected = CrewActiveMediaSelector.select(
            sessionId, localMemberId, stateProvider(), transfer, requestingMemberId,
            temporaryIndex, availableDownloads,
        ) ?: return null
        return when (selected) {
            is CrewActiveMediaSelector.Selection.Temporary -> FileSource(selected.entry)
            is CrewActiveMediaSelector.Selection.Content -> ContentSource(context.contentResolver, selected.uri, selected.lengthBytes, selected.mimeType)
        }
    }

    override fun toString() = "CrewActiveMediaRuntime(redacted)"
}

internal object CrewActiveMediaSelector {
    data class DownloadKey(val trackId: TrackId, val requestedCandidateId: CandidateId)
    data class DownloadSource(val uri: String, val lengthBytes: Long, val mimeType: String?)
    sealed interface Selection {
        data class Temporary(val entry: CrewTemporaryMediaEntry) : Selection
        data class Content(val uri: String, val lengthBytes: Long, val mimeType: String?) : Selection
    }

    fun availableDownloads(downloads: List<PersistedDownload>): Map<DownloadKey, DownloadSource> =
        downloads.asSequence().mapNotNull { download ->
            val job = download.job
            val artifact = job.artifact
            val exactCandidate =
                download.track
                    .takeIf { it.id == job.trackId }
                    ?.candidates
                    ?.firstOrNull { it.id == job.candidateId }
            if (
                job.state != DownloadState.AVAILABLE ||
                    artifact == null ||
                    exactCandidate == null ||
                    !validLength(artifact.contentLength) ||
                    !isContentUri(artifact.contentUri)
            ) {
                null
            } else {
                DownloadKey(job.trackId, job.candidateId) to
                    DownloadSource(
                        artifact.contentUri,
                        artifact.contentLength,
                        artifact.mimeType,
                    )
            }
        }
            // DAO order is newest first. Keep the first exact artifact when a
            // historical duplicate job exists for the same requested candidate.
            .distinctBy { it.first }
            .toMap()

    fun select(
        activeSessionId: CrewSessionId,
        localMemberId: CrewMemberId,
        state: CrewState,
        transfer: CrewMediaTransferRef,
        requestingMemberId: CrewMemberId,
        temporaryIndex: CrewTemporaryMediaIndex,
        downloads: Map<DownloadKey, DownloadSource>,
    ): Selection? {
        if (transfer.sessionId != activeSessionId || state.sessionId != activeSessionId ||
            transfer.supplierMemberId != localMemberId || transfer.targetMemberId != requestingMemberId ||
            requestingMemberId == localMemberId || state.members.none { it.id == requestingMemberId }
        ) return null
        val item = state.queue.firstOrNull { it.id == transfer.queueItemId } ?: return null
        val original = item.track.candidates.firstOrNull { it.id == transfer.candidateId } ?: return null
        temporaryIndex.findActive(activeSessionId, item.id, original.id)?.let { return Selection.Temporary(it) }
        val localMedia = original.media
        if (original.kind == CandidateKind.LOCAL && original.availability == CandidateAvailability.AVAILABLE &&
            validLength(localMedia?.contentLength) && isContentUri(original.locator)
        ) return Selection.Content(original.locator!!, localMedia!!.contentLength!!, localMedia.mimeType)
        val downloaded = downloads[DownloadKey(item.track.id, original.id)] ?: return null
        return Selection.Content(downloaded.uri, downloaded.lengthBytes, downloaded.mimeType)
    }

    private fun validLength(length: Long?) = length != null && length in 1..CREW_MEDIA_MAX_OBJECT_BYTES
    /** Deliberately pure so authorization selection remains a JVM-testable policy. */
    private fun isContentUri(value: String?) =
        value?.substringBefore(':', missingDelimiterValue = "")?.equals("content", ignoreCase = true) == true
}

private class FileSource(private val entry: CrewTemporaryMediaEntry) : CrewAuthorizedMediaSource {
    override val lengthBytes = entry.lengthBytes
    override val mimeType = entry.mimeType
    override fun open(): InputStream = entry.file.inputStream()
    override fun toString() = "CrewAuthorizedMediaSource(redacted)"
}

private class ContentSource(
    private val resolver: ContentResolver,
    private val uri: String,
    override val lengthBytes: Long,
    override val mimeType: String?,
) : CrewAuthorizedMediaSource {
    override fun open(): InputStream = requireNotNull(resolver.openInputStream(Uri.parse(uri)))
    override fun toString() = "CrewAuthorizedMediaSource(redacted)"
}
