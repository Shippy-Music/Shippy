/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCheckpointIntegrity.kt is part of Auxio.
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
package app.shippy.data.playback

import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object PlaybackCheckpointIntegrity {
    fun checksum(
        checkpoint: PlaybackCheckpointEntity,
        entries: List<PlaybackCheckpointEntryEntity>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            val bytes = value?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(checkpoint.slot)
        add(checkpoint.checkpointVersion.toString())
        add(checkpoint.sessionId)
        add(checkpoint.currentQueueEntryId)
        add(checkpoint.positionMs.toString())
        add(checkpoint.playingIntent.toString())
        add(checkpoint.repeatMode)
        add(checkpoint.shuffleEnabled.toString())
        add(checkpoint.shuffleSeed?.toString())
        add(checkpoint.baseOrderJson)
        add(checkpoint.traversalOrderJson)
        add(checkpoint.updatedAtEpochMs.toString())
        for (entry in entries.sortedBy(PlaybackCheckpointEntryEntity::position)) {
            add(entry.slot)
            add(entry.queueEntryId)
            add(entry.position.toString())
            add(entry.recordingId)
            add(entry.originJson)
            add(entry.contributorId)
            add(entry.presentationFallbackJson)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
