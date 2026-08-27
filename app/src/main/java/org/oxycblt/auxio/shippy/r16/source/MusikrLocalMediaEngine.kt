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
import app.shippy.sources.local.LocalMediaSnapshotDescriptor
import app.shippy.sources.local.LocalMediaSnapshotPage
import app.shippy.sources.local.LocalScanRequest
import app.shippy.sources.local.LocalScanState
import app.shippy.sources.local.TagPatch
import app.shippy.sources.local.TagWriteResult
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private val catalogLock = Any()
    @Volatile private var snapshotCatalog: LocalSnapshotCatalog? = null

    init {
        musicRepository.addIndexingListener(this)
    }

    override fun onIndexingStateChanged() {
        snapshotCatalog = null
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
        val catalog = currentSnapshotCatalog()
        val limit = catalog.songs.size.coerceAtLeast(1)
        return snapshotPage(0, limit, catalog.descriptor.fingerprint).observations
    }

    override suspend fun snapshotDescriptor(): LocalMediaSnapshotDescriptor =
        currentSnapshotCatalog().descriptor

    override suspend fun snapshotPage(
        offset: Long,
        limit: Int,
        expectedFingerprint: String?,
    ): LocalMediaSnapshotPage {
        require(offset >= 0) { "Local snapshot page offset cannot be negative" }
        require(limit > 0) { "Local snapshot page size must be positive" }
        expectedFingerprint?.let {
            require(it.isNotBlank()) { "Expected snapshot fingerprint is blank" }
        }
        val catalog = currentSnapshotCatalog()
        require(offset <= catalog.songs.size.toLong()) {
            "Local snapshot page offset exceeds snapshot"
        }
        val start = offset.toInt()
        val end = minOf(catalog.songs.size, Math.addExact(start, limit))
        val capturedAt = Instant.now()
        val observations =
            catalog.songs.subList(start, end).map { song ->
                song.toSnapshot(context).toObservation(capturedAt)
            }
        val descriptor =
            if (musicRepository.library === catalog.libraryIdentity) {
                catalog.descriptor
            } else {
                currentSnapshotCatalog().descriptor
            }
        return LocalMediaSnapshotPage(
            descriptor = descriptor,
            offset = offset,
            observations = observations,
        )
    }

    private fun currentSnapshotCatalog(): LocalSnapshotCatalog {
        val library = musicRepository.library
        snapshotCatalog
            ?.takeIf { it.libraryIdentity === library }
            ?.let {
                return it
            }
        return synchronized(catalogLock) {
            snapshotCatalog
                ?.takeIf { it.libraryIdentity === library }
                ?.let {
                    return@synchronized it
                }
            val songs = library?.songs.orEmpty().sortedBy { it.uid.toString() }
            LocalSnapshotCatalog(
                    libraryIdentity = library,
                    songs = songs,
                    descriptor = songs.snapshotDescriptor(context),
                )
                .also { snapshotCatalog = it }
        }
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

private data class LocalSnapshotCatalog(
    val libraryIdentity: Any?,
    val songs: List<Song>,
    val descriptor: LocalMediaSnapshotDescriptor,
)

private fun List<Song>.snapshotDescriptor(context: Context): LocalMediaSnapshotDescriptor {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { song ->
        val uid = song.uid.toString()
        digest.put(
            listOf("local-file", SourceItemType.LOCAL_FILE.name, uid)
                .joinToString(LOCAL_SNAPSHOT_UNIT_SEPARATOR)
        )
        val title = song.name.raw
        digest.put(title)
        song.artists.map { it.name.resolve(context) }.forEach(digest::put)
        digest.put(song.album.name.resolve(context))
        digest.put(song.durationMs.toString())
        val version = extractLocalRecordingVersion(title)
        digest.put(version.kind.name)
        digest.put(version.label)
        digest.put(Explicitness.UNKNOWN.name)
        version.traits.map(Enum<*>::name).sorted().forEach(digest::put)
        uid.takeIf { it.startsWith(MUSICBRAINZ_SONG_PREFIX) }
            ?.let {
                digest.put("${ExternalIdentifierKind.MUSICBRAINZ_RECORDING.name}:${it.drop(3)}")
            }
        digest.put(null)
        digest.put(MediaAssetKind.LOCAL_FILE.name)
        digest.put(CONTENT_URI)
        digest.put(song.uri.toString())
        digest.put(song.documentId(context))
        digest.put(song.mediaStoreId()?.toString())
        digest.put(song.path.stableToken())
        digest.put(null)
        digest.put(song.modifiedMs.takeIf { it >= 0 }?.toString())
        digest.put(song.format.mimeType)
        digest.put(null)
        digest.put(song.bitrateBps()?.toString())
        digest.put(song.sampleRateHz.takeIf { it > 0 }?.toString())
        digest.put(null)
        digest.put(song.size.toString())
        digest.put(null)
        digest.put(null)
    }
    return LocalMediaSnapshotDescriptor(
        fingerprint =
            digest.digest().joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') },
        count = size.toLong(),
    )
}

private fun Song.documentId(context: Context): String? =
    runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                null
            }
        }
        .getOrNull()

private fun Song.mediaStoreId(): Long? =
    uri.takeIf { it.authority == MediaStore.AUTHORITY }?.lastPathSegment?.toLongOrNull()

private fun Song.bitrateBps(): Int? =
    bitrateKbps.takeIf { it > 0 }?.toLong()?.times(1_000L)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

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
            sourceKey = musikrLocalSourceKey(uid),
            sourceKind = SourceKind.LOCAL_FILE,
            title = title,
            artistNames = artistNames,
            releaseTitle = releaseTitle,
            durationMs = durationMs,
            version = extractLocalRecordingVersion(title),
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
        documentId = documentId(context),
        mediaStoreId = mediaStoreId(),
        pathToken = path.stableToken(),
        mimeType = format.mimeType,
        size = size,
        durationMs = durationMs,
        bitrateBps = bitrateBps(),
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

internal fun musikrLocalSourceKey(uid: String): SourceKey =
    SourceKey(LOCAL_FILE_PROVIDER_ID, SourceItemType.LOCAL_FILE, uid)

private val LOCAL_FILE_PROVIDER_ID = ProviderId("local-file")
private const val CONTENT_URI = "CONTENT_URI"
private const val MUSICBRAINZ_SONG_PREFIX = "ums"

private val LOCAL_VERSION_QUALIFIER = Regex("(?:\\(([^)]+)\\)|\\[([^]]+)]|[-–—]\\s*([^-–—]+))\\s*$")
private val LOCAL_VERSION_MARKER =
    Regex(
        "\\b(live|remix|mix|acoustic|instrumental|radio edit|remaster(?:ed)?|cover|karaoke|sped up|slowed|reverb)\\b",
        RegexOption.IGNORE_CASE,
    )

internal fun extractLocalRecordingVersion(title: String?): app.shippy.core.music.RecordingVersion {
    if (title.isNullOrBlank())
        return app.shippy.core.music.RecordingVersion(app.shippy.core.music.VersionKind.ORIGINAL)
    val qualifier =
        LOCAL_VERSION_QUALIFIER.find(title)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull(String::isNotBlank)
            ?.trim()
            ?.takeIf(LOCAL_VERSION_MARKER::containsMatchIn)
            ?: return app.shippy.core.music.RecordingVersion(
                app.shippy.core.music.VersionKind.ORIGINAL
            )

    val parsed = RecordingVersionParser.parse(qualifier)
    return if (parsed.traits.isEmpty()) {
        app.shippy.core.music.RecordingVersion(app.shippy.core.music.VersionKind.ORIGINAL)
    } else {
        parsed
    }
}
