/*
 * Copyright (c) 2026 Auxio Project
 * LegacyIdMapper.kt is part of Auxio.
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
package app.shippy.data.migration

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.MetadataObservationId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object LegacyIdMapper {
    // Persistence contract: changing this namespace would remap every retried legacy import.
    private val namespace = UUID.fromString("76f30d44-263f-4bb5-b66d-b799f6b8b7b1")

    fun recording(oldTrackId: String): RecordingId = RecordingId(mapped("track", oldTrackId))

    fun source(oldTrackId: String, oldCandidateId: String): SourceReferenceId =
        SourceReferenceId(mapped("source", oldTrackId, oldCandidateId))

    fun asset(oldTrackId: String, oldCandidateId: String): MediaAssetId =
        MediaAssetId(mapped("asset", oldTrackId, oldCandidateId))

    fun observation(oldTrackId: String): MetadataObservationId =
        MetadataObservationId(mapped("observation", oldTrackId))

    fun playlist(oldPlaylistId: String): PlaylistId = PlaylistId(mapped("playlist", oldPlaylistId))

    fun playlistEntry(oldPlaylistId: String, oldTrackId: String): PlaylistEntryId =
        PlaylistEntryId(mapped("playlist-entry", oldPlaylistId, oldTrackId))

    fun queueEntry(oldQueueItemId: String): QueueEntryId =
        QueueEntryId(mapped("queue-entry", oldQueueItemId))

    fun stableValue(kind: String, vararg legacyParts: String): String = mapped(kind, *legacyParts)

    private fun mapped(kind: String, vararg legacyParts: String): String {
        require(kind.isNotBlank() && legacyParts.all(String::isNotBlank)) {
            "Legacy mapping inputs must not be blank"
        }
        val name = (listOf(kind) + legacyParts).joinToString(UNIT_SEPARATOR)
        return uuidV5(namespace, name).toString()
    }

    private fun uuidV5(namespace: UUID, name: String): UUID {
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
        return UUID(bytes.long, bytes.long)
    }

    private const val UNIT_SEPARATOR = "\u001f"
}
