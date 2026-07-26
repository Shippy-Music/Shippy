/*
 * Copyright (c) 2026 Shippy contributors
 * RecentListeningRepository.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.history

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.domain.TrackRealm

/** A deliberately small, non-resolvable record of a selected canonical track. */
data class RecentListeningEntry(
    val trackId: String,
    val realm: TrackRealm,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val artwork: String? = null,
    val lastPlayedAtEpochMs: Long,
) {
    init {
        require(trackId.isBounded(RecentListeningCodec.MAX_TRACK_ID_BYTES))
        require(title.isBounded(RecentListeningCodec.MAX_TITLE_BYTES))
        require(artists.size <= RecentListeningCodec.MAX_ARTISTS)
        require(artists.all { it.isBounded(RecentListeningCodec.MAX_ARTIST_BYTES) })
        require(album == null || album.isBounded(RecentListeningCodec.MAX_ALBUM_BYTES))
        // Artwork is display metadata only. Restricting it to HTTPS prevents local/content locators.
        require(artwork == null || artwork.isHttpsArtworkUrl())
        require(lastPlayedAtEpochMs >= 0)
    }
}

@Singleton
class RecentListeningRepository @Inject constructor(@ApplicationContext context: Context) {
    private val backing = File(context.filesDir, FILE_NAME)
    private val file = AtomicFile(backing)
    private val state = MutableStateFlow<List<RecentListeningEntry>>(emptyList())
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { load() }
    }

    fun observe(): StateFlow<List<RecentListeningEntry>> = state.asStateFlow()

    fun current(): List<RecentListeningEntry> = state.value

    suspend fun record(entry: RecentListeningEntry) {
        mutex.withLock {
            val updated = reduceRecentListening(state.value, entry)
            write(updated)
            state.value = updated
        }
    }

    private suspend fun load() {
        mutex.withLock {
            if (!backing.exists()) return
            val entries = runCatching {
                require(backing.length() in 1..RecentListeningCodec.MAX_FILE_BYTES.toLong())
                file.openRead().use { input ->
                    input.readBytes()
                }.let(RecentListeningCodec::decode)
            }.getOrElse {
                file.delete()
                emptyList()
            }
            state.value = reduceRecentListening(entries)
        }
    }

    private fun write(entries: List<RecentListeningEntry>) {
        val output = file.startWrite()
        try {
            output.write(RecentListeningCodec.encode(entries))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "recent-listening.bin"
    }
}

/** Dedupe by stable track identity and retain the newest twenty records. */
internal fun reduceRecentListening(
    entries: List<RecentListeningEntry>,
    recorded: RecentListeningEntry? = null,
): List<RecentListeningEntry> =
    (listOfNotNull(recorded) + entries)
        .groupBy { it.trackId }
        .values
        .map { sameTrack -> sameTrack.maxBy { it.lastPlayedAtEpochMs } }
        .sortedByDescending { it.lastPlayedAtEpochMs }
        .take(RecentListeningCodec.MAX_ENTRIES)

/** Strict, Android-independent binary codec with bounded allocation before every field read. */
internal object RecentListeningCodec {
    const val MAX_ENTRIES = 20
    const val MAX_FILE_BYTES = 32 * 1024
    const val MAX_TRACK_ID_BYTES = 512
    const val MAX_TITLE_BYTES = 1024
    const val MAX_ARTISTS = 16
    const val MAX_ARTIST_BYTES = 512
    const val MAX_ALBUM_BYTES = 1024
    const val MAX_ARTWORK_BYTES = 4096
    private const val MAGIC = 0x53485248 // SHRH
    private const val VERSION = 1

    fun encode(entries: List<RecentListeningEntry>): ByteArray {
        val normalized = reduceRecentListening(entries)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(normalized.size)
                normalized.forEach { entry ->
                    output.writeBoundedString(entry.trackId, MAX_TRACK_ID_BYTES)
                    output.writeInt(entry.realm.ordinal)
                    output.writeBoundedString(entry.title, MAX_TITLE_BYTES)
                    output.writeInt(entry.artists.size)
                    entry.artists.forEach { output.writeBoundedString(it, MAX_ARTIST_BYTES) }
                    output.writeNullableString(entry.album, MAX_ALBUM_BYTES)
                    output.writeNullableString(entry.artwork, MAX_ARTWORK_BYTES)
                    output.writeLong(entry.lastPlayedAtEpochMs)
                }
            }
            bytes.toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
        }
    }

    fun decode(bytes: ByteArray): List<RecentListeningEntry> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val count = input.readInt().also { require(it in 0..MAX_ENTRIES) }
            List(count) {
                val trackId = input.readBoundedString(MAX_TRACK_ID_BYTES)
                val realm = TrackRealm.entries.getOrNull(input.readInt()) ?: throw EOFException("Invalid realm")
                val title = input.readBoundedString(MAX_TITLE_BYTES)
                val artistCount = input.readInt().also { require(it in 0..MAX_ARTISTS) }
                val artists = List(artistCount) { input.readBoundedString(MAX_ARTIST_BYTES) }
                val album = input.readNullableString(MAX_ALBUM_BYTES)
                val artwork = input.readNullableString(MAX_ARTWORK_BYTES)
                RecentListeningEntry(trackId, realm, title, artists, album, artwork, input.readLong())
            }.also { require(input.read() == -1) }
        }.let(::reduceRecentListening)
    }

    private fun DataOutputStream.writeBoundedString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(value.isBounded(maxBytes))
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value, maxBytes)
    }

    private fun DataInputStream.readBoundedString(maxBytes: Int): String {
        val length = readInt()
        require(length in 1..maxBytes)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8).also { require(it.isBounded(maxBytes)) }
    }

    private fun DataInputStream.readNullableString(maxBytes: Int): String? =
        if (readBoolean()) readBoundedString(maxBytes) else null
}

private fun String.isBounded(maxBytes: Int) =
    isNotBlank() && none(Char::isISOControl) && toByteArray(Charsets.UTF_8).size <= maxBytes

private fun String.isHttpsArtworkUrl() =
    startsWith("https://") && isBounded(RecentListeningCodec.MAX_ARTWORK_BYTES)
