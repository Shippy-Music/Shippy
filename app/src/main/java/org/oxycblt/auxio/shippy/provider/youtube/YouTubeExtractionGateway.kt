/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeExtractionGateway.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.youtube

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class YouTubeSearchFilter(val extractorFilter: String) {
    VIDEOS(YoutubeSearchQueryHandlerFactory.VIDEOS),
    MUSIC_SONGS(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
}

data class YouTubeExtractedTrack(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationMs: Long?,
    val artwork: String?,
    val originalUrl: String,
)

data class YouTubeExtractedAudio(val url: String, val mimeType: String?, val bitrateBps: Int?)

/** Test seam around the extractor's process-global downloader and blocking Java API. */
interface YouTubeExtractionGateway {
    suspend fun search(query: String, filter: YouTubeSearchFilter): List<YouTubeExtractedTrack>

    suspend fun audioStreams(videoId: String): List<YouTubeExtractedAudio>
}

/**
 * One application-wide NewPipe gateway. Every extractor call is confined to IO: NewPipe's API is
 * synchronous and can both fetch and parse responses before returning.
 */
@Singleton
class NewPipeYouTubeExtractionGateway @Inject constructor() : YouTubeExtractionGateway {
    private val service: StreamingService by lazy {
        initialize()
        NewPipe.getService("YouTube")
    }

    override suspend fun search(
        query: String,
        filter: YouTubeSearchFilter,
    ): List<YouTubeExtractedTrack> =
        withContext(Dispatchers.IO) {
            val handler =
                service.searchQHFactory.fromQuery(query, listOf(filter.extractorFilter), "")
            SearchInfo.getInfo(service, handler).relatedItems.mapNotNull(::mapTrack)
        }

    override suspend fun audioStreams(videoId: String): List<YouTubeExtractedAudio> =
        withContext(Dispatchers.IO) {
            val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
            val audioOnly =
                info.audioStreams
                    .asSequence()
                    .mapNotNull { stream ->
                        stream.toExtractedAudio(
                            stream.bitrate.takeIf { bitrate -> bitrate > 0 }
                                ?: stream.averageBitrate
                                    .takeIf { bitrate -> bitrate > 0 }
                                    ?.times(1_000)
                        )
                    }
                    .toList()
            if (audioOnly.isNotEmpty()) return@withContext audioOnly

            // Some anonymous YouTube responses omit audio-only formats but retain progressive
            // muxed streams. Media3 can decode their audio track without a video surface.
            info.videoStreams
                .asSequence()
                .mapNotNull { stream ->
                    stream.toExtractedAudio(stream.bitrate.takeIf { bitrate -> bitrate > 0 })
                }
                .toList()
        }

    private fun mapTrack(item: InfoItem): YouTubeExtractedTrack? {
        val stream = item as? StreamInfoItem ?: return null
        val originalUrl = stream.url.takeIf { it.startsWith("https://") } ?: return null
        val videoId = originalUrl.videoId() ?: return null
        val title = stream.name.trim().takeIf(String::isNotBlank) ?: return null
        return YouTubeExtractedTrack(
            videoId = videoId,
            title = title,
            uploader = stream.uploaderName.trim().ifBlank { "YouTube" },
            durationMs = stream.duration.takeIf { it >= 0 }?.times(1_000),
            artwork = stream.thumbnails.bestUrl(),
            originalUrl = originalUrl,
        )
    }

    private fun initialize() {
        synchronized(initializationLock) {
            if (initialized) return
            NewPipe.init(OkHttpNewPipeDownloader(client))
            initialized = true
        }
    }

    private companion object {
        private val initializationLock = Any()
        private val client = OkHttpClient.Builder().build()
        @Volatile private var initialized = false
    }
}

private class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(request.httpMethod(), body ?: ByteArray(0).toRequestBody())
        }
        client.newCall(builder.build()).execute().use { response ->
            return ExtractorResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }
}

private fun List<Image>.bestUrl(): String? =
    maxByOrNull { it.height }?.url?.takeIf(String::isNotBlank)

private fun org.schabi.newpipe.extractor.stream.Stream.toExtractedAudio(
    bitrateBps: Int?
): YouTubeExtractedAudio? {
    val url = content.takeIf { isUrl && it.startsWith("https://") } ?: return null
    return YouTubeExtractedAudio(url, format?.mimeType, bitrateBps)
}

private fun String.videoId(): String? {
    val match = VIDEO_ID.find(this) ?: return null
    return match.groupValues[1].takeIf { it.length in 8..32 }
}

private val VIDEO_ID = Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]+)")
