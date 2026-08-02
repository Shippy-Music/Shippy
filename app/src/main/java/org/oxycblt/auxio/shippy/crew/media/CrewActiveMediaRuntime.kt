/*
 * Copyright (c) 2026 Auxio Project
 * CrewActiveMediaRuntime.kt is part of Auxio.
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaEntry
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailability
import org.oxycblt.auxio.shippy.crew.preparation.CrewPrefetchKey
import org.oxycblt.auxio.shippy.crew.preparation.CrewPrefetchPlanner
import org.oxycblt.auxio.shippy.crew.preparation.CrewPrefetchPolicy
import org.oxycblt.auxio.shippy.crew.preparation.CrewPrefetchRequest
import org.oxycblt.auxio.shippy.crew.preparation.PeerSupplySource
import org.oxycblt.auxio.shippy.crew.preparation.QueueItemAvailabilitySummary
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings

@Singleton
class CrewActiveMediaRuntimeFactory
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: CrewSettings,
    private val downloadRepository: DownloadJobRepository,
    private val temporaryIndex: CrewTemporaryMediaIndex,
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val privateSources: CrewPrivateSourceRegistry,
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
            providerSettings = providerSettings,
            temporaryIndex = temporaryIndex,
            initialDownloads = CrewActiveMediaSelector.availableDownloads(downloads),
            observeDownloads = downloadRepository::observeAvailable,
            enabledProviderIds = {
                providerRegistry
                    .enabled(
                        providerSettings.selection(providerRegistry.descriptors().map { it.id })
                    )
                    .map { it.descriptor.id }
            },
            privateSources = privateSources,
        )
    }
}

/** One active Crew's private media composition. It never owns playback or transport discovery. */
class CrewActiveMediaRuntime
internal constructor(
    private val context: Context,
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val stateProvider: () -> CrewState,
    private val settings: CrewSettings,
    private val providerSettings: ProviderSettings,
    private val temporaryIndex: CrewTemporaryMediaIndex,
    initialDownloads:
        Map<CrewActiveMediaSelector.DownloadKey, CrewActiveMediaSelector.DownloadSource>,
    private val observeDownloads: () -> kotlinx.coroutines.flow.Flow<List<PersistedDownload>>,
    private val enabledProviderIds: () -> List<ProviderId>,
    private val privateSources: CrewPrivateSourceRegistry,
) : CrewAuthenticatedMediaLifecycle, AutoCloseable {
    private val policy = ActiveCrewPushPullPolicy()
    private val cache = CrewTemporaryMediaCache(File(context.cacheDir, "crew-media"))
    private val receiver = CrewMediaReceiver(sessionId, cache)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileMutex = Mutex()
    @Volatile private var availableDownloads = initialDownloads
    @Volatile private var closed = false
    private var schedulerJob: Job? = null
    private var completionJob: Job? = null
    private var retryJob: Job? = null
    private var latestState: CrewState? = null
    private var latestAvailability: Map<QueueItemId, QueueItemAvailabilitySummary> = emptyMap()
    private var publishAvailability: (suspend (Map<QueueItemId, CrewAvailability>) -> Boolean)? =
        null
    private var lastPublishedAvailability: Map<QueueItemId, CrewAvailability> = emptyMap()
    private val attachedPeers = mutableSetOf<CrewMemberId>()
    private val inFlight = mutableMapOf<CrewLocalMediaKey, CrewMediaTransferRef>()
    private val lastAttempt = mutableMapOf<CrewLocalMediaKey, Long>()
    private val supplierCooldownUntil = mutableMapOf<CrewMemberId, Long>()
    private val mutablePeerMediaBlocked = MutableStateFlow(false)
    val peerMediaBlocked: StateFlow<Boolean> = mutablePeerMediaBlocked

    private val router =
        CrewMediaSessionRouter(
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

                override fun onTemporaryMediaProgress(progress: CrewMediaReceiveProgress): Boolean =
                    temporaryIndex.progress(
                        progress.manifest,
                        progress.file,
                        progress.contiguousBytes,
                    )

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

    private val settingsListener =
        object : CrewSettings.Listener {
            override fun onPushPullEnabledChanged(enabled: Boolean) {
                if (!closed) {
                    policy.activate(sessionId, enabled)
                    if (!enabled) router.onPushPullDisabled()
                    scope.launch { reconcileLocalMedia() }
                }
            }
        }
    private val providerSettingsListener =
        object : ProviderSettings.Listener {
            override fun onProviderPriorityChanged() {
                if (!closed) scope.launch { reconcileLocalMedia() }
            }
        }

    init {
        cache.beginSession(sessionId)
        privateSources.beginSession(sessionId)
        temporaryIndex.beginSession(sessionId)
        policy.activate(sessionId, settings.pushPullEnabled)
        settings.registerListener(settingsListener)
        providerSettings.registerListener(providerSettingsListener)
        scope.launch {
            observeDownloads()
                .catch {
                    reconcileMutex.withLock {
                        availableDownloads = emptyMap()
                        reconcileLocalMediaLocked()
                    }
                }
                .collect {
                    reconcileMutex.withLock {
                        availableDownloads = CrewActiveMediaSelector.availableDownloads(it)
                        reconcileLocalMediaLocked()
                    }
                }
        }
    }

    override fun onPeerAttached(peer: CrewAuthenticatedMediaPeer) {
        if (closed) return
        router.onPeerAttached(peer)
        scope.launch {
            reconcileMutex.withLock {
                attachedPeers += peer.memberId
                reconcileLocalMediaLocked()
            }
        }
    }

    override fun onMediaFrame(
        peer: CrewAuthenticatedMediaPeer,
        frame: org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame,
    ) = router.onMediaFrame(peer, frame)

    override fun onPeerDetached(peer: CrewAuthenticatedMediaPeer) {
        if (closed) return
        router.onPeerDetached(peer)
        scope.launch {
            reconcileMutex.withLock {
                attachedPeers -= peer.memberId
                supplierCooldownUntil[peer.memberId] = monotonicNow() + SUPPLIER_COOLDOWN_MS
                inFlight.entries.removeIf { (_, transfer) ->
                    transfer.supplierMemberId == peer.memberId
                }
                scheduleRetry()
                reconcileLocalMediaLocked()
            }
        }
    }

    fun requestTemporaryMedia(
        supplier: CrewMemberId,
        transfer: CrewMediaTransferRef,
    ): CrewSendResult? = router.requestTemporaryMedia(supplier, transfer)

    fun cancelTemporaryMediaRequest(
        supplier: CrewMemberId,
        transfer: CrewMediaTransferRef,
    ): CrewSendResult? = router.cancelTemporaryMediaRequest(supplier, transfer)

    fun resumeSupplierTransfer(supplier: CrewMemberId, transfer: CrewMediaTransferRef) =
        router.resumeSupplierTransfer(supplier, transfer)

    /** Binds once after the session engine exists; all requests remain scoped to this runtime. */
    fun bind(
        state: StateFlow<CrewState>,
        availability: StateFlow<Map<QueueItemId, QueueItemAvailabilitySummary>>,
        publishLocalAvailability: suspend (Map<QueueItemId, CrewAvailability>) -> Boolean,
    ) {
        check(schedulerJob == null) { "Crew media runtime is already bound" }
        publishAvailability = publishLocalAvailability
        completionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                temporaryIndex.completions.collect { completion ->
                    if (completion.sessionId != sessionId) return@collect
                    reconcileMutex.withLock {
                        val key = CrewLocalMediaKey(completion.queueItemId, completion.candidateId)
                        inFlight.remove(key)
                        lastAttempt.remove(key)
                        reconcileLocalMediaLocked()
                    }
                }
            }
        schedulerJob =
            scope.launch {
                state.collect {
                    reconcileMutex.withLock {
                        latestState = it
                        reconcileLocalMediaLocked()
                    }
                }
            }
        scope.launch {
            availability.collect {
                reconcileMutex.withLock {
                    latestAvailability = it.toMap()
                    reconcileLocalMediaLocked()
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { settings.unregisterListener(settingsListener) }
        runCatching { providerSettings.unregisterListener(providerSettingsListener) }
        schedulerJob?.cancel()
        completionJob?.cancel()
        retryJob?.cancel()
        inFlight.values.forEach { cancelTemporaryMediaRequest(it.supplierMemberId, it) }
        inFlight.clear()
        lastAttempt.clear()
        supplierCooldownUntil.clear()
        attachedPeers.clear()
        mutablePeerMediaBlocked.value = false
        runCatching { policy.deactivate(sessionId) }
        runCatching { router.close() }
        runCatching { privateSources.endSession(sessionId) }
        runCatching { temporaryIndex.endSession(sessionId) { cache.endSession(sessionId) } }
        runCatching { scope.cancel() }
    }

    private suspend fun reconcileLocalMedia() =
        reconcileMutex.withLock { reconcileLocalMediaLocked() }

    private suspend fun reconcileLocalMediaLocked() {
        val state = latestState?.withPrivateSources() ?: return
        if (closed || state.sessionId != sessionId) return
        val available =
            state.queue
                .asSequence()
                .flatMap { item ->
                    item.track.candidates.asSequence().map { CrewLocalMediaKey(item.id, it.id) }
                }
                .filter { key ->
                    temporaryIndex.findActive(sessionId, key.queueItemId, key.candidateId) != null
                }
                .toSet()
        val enabledProviders = enabledProviderIds()
        val window = state.queueWindow()
        val localAvailability =
            window.associate { item ->
                val other =
                    latestAvailability[item.id]
                        ?.members
                        .orEmpty()
                        .filter { it.memberId != localMemberId }
                        .map { it.availability }
                item.id to
                    CrewLocalAvailabilityEvaluator.evaluate(
                        item,
                        localMemberId,
                        available,
                        availableDownloads.keys,
                        enabledProviders,
                        other,
                    )
            }
        mutablePeerMediaBlocked.value =
            state.playback.currentQueueItemId?.let {
                localAvailability[it] == CrewAvailability.PEER_ONLY && !settings.pushPullEnabled
            } == true
        if (localAvailability != lastPublishedAvailability) {
            if (publishAvailability?.invoke(localAvailability) == true) {
                lastPublishedAvailability = localAvailability.toMap()
            }
        }
        val now = monotonicNow()
        supplierCooldownUntil.entries.removeIf { (_, until) -> until <= now }
        val usableAvailability =
            reachableCrewAvailability(
                latestAvailability,
                localMemberId,
                attachedPeers,
                supplierCooldownUntil.keys,
            )
        val plan =
            CrewPrefetchPlanner.plan(
                state.queue,
                state.playback.currentQueueItemId,
                usableAvailability,
                state.members.map { it.id },
                settings.pushPullEnabled,
                CrewPrefetchPolicy(2),
                inFlight.values.map { transfer ->
                    CrewPrefetchRequest(
                        CrewPrefetchKey(
                            transfer.queueItemId,
                            localMemberId,
                            transfer.supplierMemberId,
                        ),
                        PeerSupplySource.LOCAL_EXACT,
                        0,
                    )
                },
            )
        val wanted =
            (plan.start + plan.keep + plan.reprioritize)
                .filter { it.key.targetMemberId == localMemberId }
                .associateBy { it.key.queueItemId }
        inFlight.entries.removeIf { (_, transfer) ->
            if (transfer.queueItemId in wanted) {
                false
            } else {
                cancelTemporaryMediaRequest(transfer.supplierMemberId, transfer)
                true
            }
        }
        wanted.forEach { (queueItemId, desired) ->
            if (desired.key.supplierMemberId !in attachedPeers) return@forEach
            val item = state.queue.firstOrNull { it.id == queueItemId } ?: return@forEach
            val candidateId =
                CrewLocalAvailabilityEvaluator.candidateId(item, enabledProviders) ?: return@forEach
            val key = CrewLocalMediaKey(queueItemId, candidateId)
            val existing = inFlight[key]
            if (existing != null && existing.supplierMemberId == desired.key.supplierMemberId)
                return@forEach
            if ((lastAttempt[key] ?: Long.MIN_VALUE) + 750L > now) return@forEach
            existing?.let { cancelTemporaryMediaRequest(it.supplierMemberId, it) }
            val transfer =
                CrewMediaTransferRef(
                    sessionId,
                    CrewMediaRequestId(UUID.randomUUID().toString()),
                    queueItemId,
                    candidateId,
                    localMemberId,
                    desired.key.supplierMemberId,
                )
            lastAttempt[key] = now
            when (requestTemporaryMedia(desired.key.supplierMemberId, transfer)) {
                is CrewSendResult.Sent -> inFlight[key] = transfer
                else -> {
                    inFlight.remove(key)
                    supplierCooldownUntil[desired.key.supplierMemberId] = now + SUPPLIER_COOLDOWN_MS
                    scheduleRetry()
                }
            }
        }
        lastAttempt.keys.retainAll(
            inFlight.keys +
                wanted.keys.mapNotNull { id ->
                    state.queue
                        .firstOrNull { it.id == id }
                        ?.let {
                            CrewLocalAvailabilityEvaluator.candidateId(it, enabledProviders)?.let {
                                candidate ->
                                CrewLocalMediaKey(id, candidate)
                            }
                        }
                }
        )
    }

    private fun onRequestFinished(transfer: CrewMediaTransferRef, peerMemberId: CrewMemberId) {
        if (
            transfer.sessionId != sessionId ||
                transfer.targetMemberId != localMemberId ||
                transfer.supplierMemberId != peerMemberId
        ) {
            return
        }
        scope.launch {
            reconcileMutex.withLock {
                val key = CrewLocalMediaKey(transfer.queueItemId, transfer.candidateId)
                if (inFlight[key] == transfer) {
                    inFlight.remove(key)
                    supplierCooldownUntil[peerMemberId] = monotonicNow() + SUPPLIER_COOLDOWN_MS
                    scheduleRetry()
                    reconcileLocalMediaLocked()
                }
            }
        }
    }

    private fun scheduleRetry() {
        if (closed || retryJob?.isActive == true) return
        retryJob =
            scope.launch {
                delay(SUPPLIER_COOLDOWN_MS)
                retryJob = null
                reconcileLocalMedia()
            }
    }

    private fun authorize(
        transfer: CrewMediaTransferRef,
        requestingMemberId: CrewMemberId,
    ): CrewAuthorizedMediaSource? {
        val selected =
            CrewActiveMediaSelector.select(
                sessionId,
                localMemberId,
                stateProvider().withPrivateSources(),
                transfer,
                requestingMemberId,
                temporaryIndex,
                availableDownloads,
            ) ?: return null
        return when (selected) {
            is CrewActiveMediaSelector.Selection.Temporary -> FileSource(selected.entry)
            is CrewActiveMediaSelector.Selection.Content ->
                ContentSource(
                    context.contentResolver,
                    selected.uri,
                    selected.lengthBytes,
                    selected.mimeType,
                )
        }
    }

    private fun CrewState.withPrivateSources(): CrewState =
        copy(queue = queue.map { privateSources.overlay(sessionId, localMemberId, it) })

    override fun toString() = "CrewActiveMediaRuntime(redacted)"

    private fun monotonicNow() = System.nanoTime() / 1_000_000L

    private companion object {
        const val SUPPLIER_COOLDOWN_MS = 3_000L
    }
}

private fun CrewState.queueWindow(): List<QueueItem> {
    val current =
        playback.currentQueueItemId?.let { id ->
            queue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        } ?: return queue.take(3)
    return queue.drop(current).take(3)
}

internal object CrewActiveMediaSelector {
    data class DownloadKey(val trackId: TrackId, val requestedCandidateId: CandidateId)

    data class DownloadSource(val uri: String, val lengthBytes: Long, val mimeType: String?)

    sealed interface Selection {
        data class Temporary(val entry: CrewTemporaryMediaEntry) : Selection

        data class Content(val uri: String, val lengthBytes: Long, val mimeType: String?) :
            Selection
    }

    fun availableDownloads(downloads: List<PersistedDownload>): Map<DownloadKey, DownloadSource> =
        downloads
            .asSequence()
            .mapNotNull { download ->
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
        if (
            transfer.sessionId != activeSessionId ||
                state.sessionId != activeSessionId ||
                transfer.supplierMemberId != localMemberId ||
                transfer.targetMemberId != requestingMemberId ||
                requestingMemberId == localMemberId ||
                state.members.none { it.id == requestingMemberId }
        )
            return null
        val item = state.queue.firstOrNull { it.id == transfer.queueItemId } ?: return null
        val original =
            item.track.candidates.firstOrNull { it.id == transfer.candidateId } ?: return null
        temporaryIndex.findActive(activeSessionId, item.id, original.id)?.let {
            return Selection.Temporary(it)
        }
        val localMedia = original.media
        if (
            item.track.realm == TrackRealm.LOCAL &&
                item.contributorId == localMemberId.value &&
                original.kind == CandidateKind.LOCAL &&
                original.availability == CandidateAvailability.AVAILABLE &&
                validOrUnknownLength(localMedia?.contentLength) &&
                isContentUri(original.locator)
        ) {
            return Selection.Content(
                original.locator!!,
                localMedia?.contentLength ?: CrewAuthorizedMediaSource.UNKNOWN_LENGTH,
                localMedia?.mimeType,
            )
        }
        val downloaded = downloads[DownloadKey(item.track.id, original.id)] ?: return null
        return Selection.Content(downloaded.uri, downloaded.lengthBytes, downloaded.mimeType)
    }

    private fun validLength(length: Long?) =
        length != null && length in 1..CREW_MEDIA_MAX_OBJECT_BYTES

    private fun validOrUnknownLength(length: Long?) =
        length == null || length in 1..CREW_MEDIA_MAX_OBJECT_BYTES

    /** Deliberately pure so authorization selection remains a JVM-testable policy. */
    private fun isContentUri(value: String?) =
        value
            ?.substringBefore(':', missingDelimiterValue = "")
            ?.equals("content", ignoreCase = true) == true
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
