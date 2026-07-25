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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var schedulerJob: Job? = null
    private var completionJob: Job? = null
    private var retryJob: Job? = null
    private var latestState: CrewState? = null
    private val attachedPeers = mutableSetOf<CrewMemberId>()
    private val inFlight = mutableMapOf<CrewLocalMediaKey, CrewMediaTransferRef>()
    private val lastAttempt = mutableMapOf<CrewLocalMediaKey, Long>()
    private val mutablePeerMediaBlocked = MutableStateFlow(false)
    val peerMediaBlocked: StateFlow<Boolean> = mutablePeerMediaBlocked

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

            override fun onTransferRetryLater(
                transfer: CrewMediaTransferRef,
                peerMemberId: CrewMemberId,
            ) {
                onRequestFinished(transfer, peerMemberId)
            }

            override fun onTransferRejected(
                transfer: CrewMediaTransferRef,
                peerMemberId: CrewMemberId,
            ) {
                onRequestFinished(transfer, peerMemberId)
            }
        },
    )

    private val settingsListener = object : CrewSettings.Listener {
        override fun onPushPullEnabledChanged(enabled: Boolean) {
            if (!closed) {
                policy.activate(sessionId, enabled)
                scope.launch { reconcileLocalMedia() }
            }
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

    override fun onPeerAttached(peer: CrewAuthenticatedMediaPeer) {
        if (closed) return
        router.onPeerAttached(peer)
        scope.launch {
            attachedPeers += peer.memberId
            reconcileLocalMedia()
        }
    }
    override fun onMediaFrame(peer: CrewAuthenticatedMediaPeer, frame: org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame) =
        router.onMediaFrame(peer, frame)
    override fun onPeerDetached(peer: CrewAuthenticatedMediaPeer) {
        if (closed) return
        router.onPeerDetached(peer)
        scope.launch {
            attachedPeers -= peer.memberId
            inFlight.entries.removeIf { (_, transfer) ->
                transfer.supplierMemberId == peer.memberId
            }
        }
    }

    fun requestTemporaryMedia(supplier: CrewMemberId, transfer: CrewMediaTransferRef): CrewSendResult? =
        router.requestTemporaryMedia(supplier, transfer)

    fun cancelTemporaryMediaRequest(supplier: CrewMemberId, transfer: CrewMediaTransferRef): CrewSendResult? =
        router.cancelTemporaryMediaRequest(supplier, transfer)

    fun resumeSupplierTransfer(supplier: CrewMemberId, transfer: CrewMediaTransferRef) =
        router.resumeSupplierTransfer(supplier, transfer)

    /** Binds once after the session engine exists; all requests remain scoped to this runtime. */
    fun bind(state: StateFlow<CrewState>) {
        check(schedulerJob == null) { "Crew media runtime is already bound" }
        completionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                temporaryIndex.completions.collect { completion ->
                    if (completion.sessionId != sessionId) return@collect
                    val key = CrewLocalMediaKey(completion.queueItemId, completion.candidateId)
                    inFlight.remove(key)
                    lastAttempt.remove(key)
                    reconcileLocalMedia()
                }
            }
        schedulerJob =
            scope.launch {
                state.collect {
                    latestState = it
                    reconcileLocalMedia()
                }
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { settings.unregisterListener(settingsListener) }
        schedulerJob?.cancel()
        completionJob?.cancel()
        retryJob?.cancel()
        inFlight.values.forEach { cancelTemporaryMediaRequest(it.supplierMemberId, it) }
        inFlight.clear()
        lastAttempt.clear()
        attachedPeers.clear()
        mutablePeerMediaBlocked.value = false
        runCatching { policy.deactivate(sessionId) }
        runCatching { temporaryIndex.endSession(sessionId) }
        runCatching { cache.endSession(sessionId) }
        runCatching { scope.cancel() }
    }

    private fun reconcileLocalMedia() {
        val state = latestState ?: return
        if (closed || state.sessionId != sessionId) return
        val available =
            state.queue
                .asSequence()
                .flatMap { item ->
                    item.track.candidates
                        .asSequence()
                        .filter { it.kind == CandidateKind.LOCAL }
                        .map { CrewLocalMediaKey(item.id, it.id) }
                }
                .filter { key ->
                    temporaryIndex.findActive(
                        sessionId,
                        key.queueItemId,
                        key.candidateId,
                    ) != null
                }
                .toSet()
        val plan =
            planLocalMedia(
                state,
                localMemberId,
                settings.pushPullEnabled,
                available,
            )
        mutablePeerMediaBlocked.value = plan.blockedCurrent
        val wanted = plan.desired.associateBy { it.key }
        inFlight.entries.removeIf { (key, transfer) ->
            if (key in wanted) false else { cancelTemporaryMediaRequest(transfer.supplierMemberId, transfer); true }
        }
        val now = System.nanoTime() / 1_000_000L
        plan.desired.forEach { desired ->
            if (desired.supplier !in attachedPeers) return@forEach
            val existing = inFlight[desired.key]
            if (existing != null && existing.supplierMemberId == desired.supplier) return@forEach
            if ((lastAttempt[desired.key] ?: Long.MIN_VALUE) + 750L > now) return@forEach
            existing?.let { cancelTemporaryMediaRequest(it.supplierMemberId, it) }
            val transfer = CrewMediaTransferRef(sessionId, CrewMediaRequestId(UUID.randomUUID().toString()), desired.key.queueItemId, desired.key.candidateId, localMemberId, desired.supplier)
            lastAttempt[desired.key] = now
            when (requestTemporaryMedia(desired.supplier, transfer)) {
                is CrewSendResult.Sent -> inFlight[desired.key] = transfer
                else -> {
                    inFlight.remove(desired.key)
                    scheduleRetry()
                }
            }
        }
        lastAttempt.keys.retainAll(wanted.keys)
    }

    private fun onRequestFinished(
        transfer: CrewMediaTransferRef,
        peerMemberId: CrewMemberId,
    ) {
        if (
            transfer.sessionId != sessionId ||
                transfer.targetMemberId != localMemberId ||
                transfer.supplierMemberId != peerMemberId
        ) {
            return
        }
        scope.launch {
            val key = CrewLocalMediaKey(transfer.queueItemId, transfer.candidateId)
            if (inFlight[key] == transfer) {
                inFlight.remove(key)
                scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        if (closed || retryJob?.isActive == true) return
        retryJob =
            scope.launch {
                delay(750L)
                retryJob = null
                reconcileLocalMedia()
            }
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
