/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackSourceRepository.kt is part of Auxio.
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

import androidx.room.withTransaction
import app.shippy.core.asset.AssetState
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.SourceReference
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.source.toDomain
import java.time.Instant

data class R16PlayableAsset(
    val id: MediaAssetId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val kind: MediaAssetKind,
    val state: AssetState,
    val locationType: String,
    val location: String,
    val technical: AudioTechnicalMetadata,
    val lastVerifiedAt: Instant?,
) {
    init {
        require(locationType.isNotBlank()) { "Playable asset location type cannot be blank" }
        require(location.isNotBlank()) { "Playable asset location cannot be blank" }
    }
}

data class R16PlaybackSourceOptions(
    val assets: List<R16PlayableAsset>,
    val sources: List<SourceReference>,
)

interface R16PlaybackSourceRepository {
    suspend fun options(recordingId: RecordingId): R16PlaybackSourceOptions
}

internal class RoomR16PlaybackSourceRepository(private val database: ShippyR16Database) :
    R16PlaybackSourceRepository {
    override suspend fun options(recordingId: RecordingId): R16PlaybackSourceOptions =
        database.withTransaction {
            R16PlaybackSourceOptions(
                assets =
                    database
                        .assetDao()
                        .verifiedPlayable(recordingId.value)
                        .map(MediaAssetEntity::toPlayableAsset),
                sources = database.sourceDao().forRecording(recordingId.value).map { it.toDomain() },
            )
        }
}

private fun MediaAssetEntity.toPlayableAsset() =
    R16PlayableAsset(
        id = MediaAssetId(assetId),
        recordingId = RecordingId(recordingId),
        sourceReferenceId = sourceReferenceId?.let(::SourceReferenceId),
        kind = MediaAssetKind.valueOf(assetKind),
        state = AssetState.valueOf(assetState),
        locationType = locationType,
        location = location,
        technical =
            AudioTechnicalMetadata(
                mimeType = mimeType,
                codec = codec,
                bitrateBps = bitrateBps?.toPlaybackBitrate(),
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                contentLength = contentLength,
            ),
        lastVerifiedAt = lastVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    )

private fun Long.toPlaybackBitrate(): Int {
    require(this in 1..Int.MAX_VALUE.toLong()) { "Playable asset bitrate is out of range" }
    return toInt()
}
