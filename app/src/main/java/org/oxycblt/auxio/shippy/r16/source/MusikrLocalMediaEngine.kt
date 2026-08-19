/*
 * Copyright (c) 2026 Auxio Project
 * MusikrLocalMediaEngine.kt is part of Auxio.
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

import android.content.Context
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.ProviderId
import app.shippy.core.identitymatch.RecordingVersionParser
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifier
import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.sources.local.LocalAssetHandle
import app.shippy.sources.local.LocalAssetKey
import app.shippy.sources.local.LocalDeleteResult
import app.shippy.sources.local.LocalMediaChange
import app.shippy.sources.local.LocalMediaEngine
import app.shippy.sources.local.LocalScanRequest
import app.shippy.sources.local.LocalScanState
import app.shippy.sources.local.TagPatch
import app.shippy.sources.local.TagWriteResult
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.IndexingProgress
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Volume

/** Reuses the process-owned legacy Musikr repository while R16 remains inactive. */
@Singleton
class MusikrLocalMediaEngine
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) : LocalMediaEngine, MusicRepository.IndexingListener {
    private val mutableScanState = MutableStateFlow(musicRepository.indexingState.toR16State())
    override val scanState: StateFlow<LocalScanState> = mutableScanState

    init {
        musicRepository.addIndexingListener(this)
    }

    override fun onIndexingStateChanged() {
        mutableScanState.value = musicRepository.indexingState.toR16State()
    }

    override fun observeChanges(): Flow<LocalMediaChange> = flow {
        var previous = emptyMap<String, Song>()
        deviceLibrarySignals().collect {
            val current = musicRepository.library?.songs.orEmpty().associateBy { it.uid.toString() }
            (current.keys - previous.keys).sorted().forEach {
                emit(LocalMediaChange.Added(LocalAssetKey(it)))
            }
            (previous.keys - current.keys).sorted().forEach {
                emit(LocalMediaChange.Removed(LocalAssetKey(it)))
            }
            current.keys
                .intersect(previous.keys)
                .filter { current[it] != previous[it] }
                .sorted()
                .forEach { emit(LocalMediaChange.Updated(LocalAssetKey(it))) }
            previous = current
        }
    }

    override suspend fun scan(request: LocalScanRequest) {
        musicRepository.requestIndex(withCache = !request.force)
    }

    override suspend fun snapshot(): List<SourceTrackObservation> {
        val capturedAt = Instant.now()
        return musicRepository.library
            ?.songs
            .orEmpty()
            .sortedBy { it.uid.toString() }
            .map { song -> song.toSnapshot(context).toObservation(capturedAt) }
    }

    override suspend fun open(asset: LocalAssetKey): LocalAssetHandle {
        val uid = requireNotNull(Music.UID.fromString(asset.value)) { "Invalid Musikr asset key" }
        val song =
            requireNotNull(musicRepository.library?.findSong(uid)) { "Local asset is missing" }
        return LocalAssetHandle(asset, AssetLocation(song.uri.toString()), song.size)
    }

    override suspend fun delete(asset: LocalAssetKey): LocalDeleteResult =
        LocalDeleteResult.Unsupported

    override suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult =
        TagWriteResult.Unsupported

    private fun deviceLibrarySignals(): Flow<Unit> =
        callbackFlow {
                val listener =
                    object : MusicRepository.UpdateListener {
                        override fun onMusicChanges(changes: MusicRepository.Changes) {
                            if (changes.deviceLibrary) trySend(Unit)
                        }
                    }
                musicRepository.addUpdateListener(listener)
                awaitClose { musicRepository.removeUpdateListener(listener) }
            }
            .buffer(Channel.CONFLATED)
}

internal data class MusikrSongSnapshot(
    val uid: String,
    val title: String,
    val artistNames: List<String>,
    val releaseTitle: String?,
    val uri: String,
    val documentId: String?,
    val mediaStoreId: Long?,
    val pathToken: String,
    val mimeType: String,
    val size: Long,
    val durationMs: Long,
    val bitrateBps: Int?,
    val sampleRateHz: Int?,
    val modifiedAtEpochMs: Long?,
    val musicBrainzRecordingId: String?,
) {
    fun toObservation(capturedAt: Instant): SourceTrackObservation =
        SourceTrackObservation(
            sourceKey = SourceKey(LOCAL_FILE_PROVIDER_ID, SourceItemType.LOCAL_FILE, uid),
            sourceKind = SourceKind.LOCAL_FILE,
            title = title,
            artistNames = artistNames,
            releaseTitle = releaseTitle,
            durationMs = durationMs,
            version = RecordingVersionParser.parse(title),
            explicitness = Explicitness.UNKNOWN,
            artwork = emptyList(),
            externalIdentifiers =
                musicBrainzRecordingId
                    ?.let {
                        setOf(ExternalIdentifier(ExternalIdentifierKind.MUSICBRAINZ_RECORDING, it))
                    }
                    .orEmpty(),
            originalUrl = null,
            asset =
                ObservedMediaAsset(
                    kind = MediaAssetKind.LOCAL_FILE,
                    location = AssetLocation(uri),
                    locationType = CONTENT_URI,
                    documentId = documentId,
                    mediaStoreId = mediaStoreId,
                    normalizedPathToken = pathToken,
                    downloadJobId = null,
                    lastModifiedEpochMs = modifiedAtEpochMs,
                    technical =
                        AudioTechnicalMetadata(
                            mimeType = mimeType,
                            codec = null,
                            bitrateBps = bitrateBps,
                            sampleRateHz = sampleRateHz,
                            channelCount = null,
                            contentLength = size,
                        ),
                    checksum = null,
                    fingerprint = null,
                    verifiedAt = capturedAt,
                ),
            capturedAt = capturedAt,
        )
}

private fun Song.toSnapshot(context: Context): MusikrSongSnapshot {
    val uriString = uri.toString()
    val uidString = uid.toString()
    return MusikrSongSnapshot(
        uid = uidString,
        title = name.raw,
        artistNames = artists.map { it.name.resolve(context) },
        releaseTitle = album.name.resolve(context),
        uri = uriString,
        documentId =
            runCatching {
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        DocumentsContract.getDocumentId(uri)
                    } else {
                        null
                    }
                }
                .getOrNull(),
        mediaStoreId =
            uri.takeIf { it.authority == MediaStore.AUTHORITY }?.lastPathSegment?.toLongOrNull(),
        pathToken = path.stableToken(),
        mimeType = format.mimeType,
        size = size,
        durationMs = durationMs,
        bitrateBps =
            bitrateKbps
                .takeIf { it > 0 }
                ?.toLong()
                ?.times(1_000L)
                ?.takeIf { it <= Int.MAX_VALUE }
                ?.toInt(),
        sampleRateHz = sampleRateHz.takeIf { it > 0 },
        modifiedAtEpochMs = modifiedMs.takeIf { it >= 0 },
        musicBrainzRecordingId =
            uidString.takeIf { it.startsWith(MUSICBRAINZ_SONG_PREFIX) }?.drop(3),
    )
}

private fun org.oxycblt.musikr.fs.Path.stableToken(): String {
    val volumeToken =
        when (val value = volume) {
            is Volume.ThirdParty -> "saf:${value.uri}"
            is Volume.External -> "external:${value.id ?: value.mediaStoreName.orEmpty()}"
            is Volume.Internal -> "internal:${value.mediaStoreName.orEmpty()}"
        }
    return "$volumeToken/${components.unixString}"
}

private fun IndexingState?.toR16State(): LocalScanState =
    when (this) {
        is IndexingState.Indexing ->
            when (val value = progress) {
                is IndexingProgress.Songs -> LocalScanState.Scanning(value.loaded.toLong())
                IndexingProgress.Indeterminate -> LocalScanState.Scanning(0)
            }
        is IndexingState.Completed ->
            error?.let {
                LocalScanState.Failed(
                    code = it.javaClass.simpleName.ifBlank { "INDEX_FAILED" },
                    retryable = true,
                )
            } ?: LocalScanState.Idle
        null -> LocalScanState.Idle
    }

private val LOCAL_FILE_PROVIDER_ID = ProviderId("local-file")
private const val CONTENT_URI = "CONTENT_URI"
private const val MUSICBRAINZ_SONG_PREFIX = "ums"
