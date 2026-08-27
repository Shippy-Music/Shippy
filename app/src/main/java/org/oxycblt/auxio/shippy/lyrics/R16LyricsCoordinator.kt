/*
 * Copyright (c) 2026 Auxio Project
 * R16LyricsCoordinator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lyrics

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.data.playback.R16PlaybackPresentationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.TrackId

data class R16LyricsToken(
    val recordingId: RecordingId,
    val queueEntryId: QueueEntryId,
    val generation: Long,
)

sealed interface R16LyricsState {
    val token: R16LyricsToken?

    data object None : R16LyricsState {
        override val token: R16LyricsToken? = null
    }

    data class Loading(override val token: R16LyricsToken) : R16LyricsState

    data class Synced(
        override val token: R16LyricsToken,
        val lyrics: SyncedLyrics,
        val sourceId: String,
        val recordId: Long,
    ) : R16LyricsState

    data class Unsynced(
        override val token: R16LyricsToken,
        val lyrics: PlainLyrics,
        val sourceId: String,
        val recordId: Long,
    ) : R16LyricsState

    data class Unavailable(override val token: R16LyricsToken) : R16LyricsState

    data class Failed(
        override val token: R16LyricsToken,
        val kind: LyricsFailureKind,
        val retryable: Boolean,
        val message: String?,
    ) : R16LyricsState
}

class R16LyricsCoordinator(
    private val scope: CoroutineScope,
    private val lyricsRepository: LyricsRepository,
    private val presentations: R16PlaybackPresentationRepository? = null,
) {
    private val mutableState = MutableStateFlow<R16LyricsState>(R16LyricsState.None)
    val state: StateFlow<R16LyricsState> = mutableState.asStateFlow()

    private var currentJob: Job? = null
    private var lastToken: R16LyricsToken? = null
    private var lastExplicitMetadata: ExplicitMetadata? = null

    private data class ExplicitMetadata(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    fun observe(snapshots: kotlinx.coroutines.flow.Flow<PlaybackSnapshot>) {
        scope.launch {
            snapshots.collectLatest { snapshot ->
                val queueEntryId = snapshot.committedQueueEntryId
                if (queueEntryId == null) {
                    cancelCurrent()
                    lastToken = null
                    lastExplicitMetadata = null
                    mutableState.value = R16LyricsState.None
                    return@collectLatest
                }

                val entry = snapshot.queue.baseQueue.find { it.id == queueEntryId }
                if (entry == null) {
                    cancelCurrent()
                    lastToken = null
                    lastExplicitMetadata = null
                    mutableState.value = R16LyricsState.None
                    return@collectLatest
                }

                val token = R16LyricsToken(entry.recordingId, queueEntryId, snapshot.generation)
                if (token == lastToken && mutableState.value !is R16LyricsState.None)
                    return@collectLatest

                cancelCurrent()
                lastToken = token
                lastExplicitMetadata = null
                fetchLyrics(token)
            }
        }
    }

    fun loadFor(
        recordingId: RecordingId,
        queueEntryId: QueueEntryId,
        generation: Long = 0,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        durationMs: Long? = null,
    ) {
        val token = R16LyricsToken(recordingId, queueEntryId, generation)
        val explicit =
            if (
                !title.isNullOrBlank() &&
                    !artist.isNullOrBlank() &&
                    durationMs != null &&
                    durationMs > 0
            ) {
                ExplicitMetadata(title, artist, album, durationMs)
            } else {
                null
            }

        if (token == lastToken && mutableState.value !is R16LyricsState.None) {
            return
        }

        cancelCurrent()
        lastToken = token
        lastExplicitMetadata = explicit
        fetchLyrics(token, explicit)
    }

    fun retry() {
        val token = lastToken ?: return
        val currentState = mutableState.value
        if (currentState is R16LyricsState.Failed && currentState.retryable) {
            cancelCurrent()
            fetchLyrics(token, lastExplicitMetadata)
        }
    }

    fun refresh() {
        val token = lastToken ?: return
        cancelCurrent()
        fetchLyrics(token, lastExplicitMetadata)
    }

    private fun cancelCurrent() {
        currentJob?.cancel()
        currentJob = null
    }

    private fun fetchLyrics(token: R16LyricsToken, explicit: ExplicitMetadata? = null) {
        currentJob =
            scope.launch {
                mutableState.value = R16LyricsState.Loading(token)

                val effectiveTitle: String
                val effectiveArtist: String
                val effectiveAlbum: String?
                val effectiveDurationMs: Long?

                if (explicit != null) {
                    effectiveTitle = explicit.title
                    effectiveArtist = explicit.artist
                    effectiveAlbum = explicit.album
                    effectiveDurationMs = explicit.durationMs
                } else if (presentations != null) {
                    val presentationMap = presentations.observe(setOf(token.recordingId)).first()
                    val presentation = presentationMap[token.recordingId]
                    if (presentation == null) {
                        if (lastToken == token)
                            mutableState.value = R16LyricsState.Unavailable(token)
                        return@launch
                    }
                    effectiveTitle = presentation.title
                    effectiveArtist = presentation.artist
                    effectiveAlbum = presentation.releaseTitle
                    effectiveDurationMs = presentation.durationMs
                } else {
                    if (lastToken == token) mutableState.value = R16LyricsState.Unavailable(token)
                    return@launch
                }

                val request =
                    LyricsRequest(
                        trackId = TrackId(token.recordingId.value),
                        title = effectiveTitle,
                        artists = listOf(effectiveArtist),
                        album = effectiveAlbum,
                        durationMs = effectiveDurationMs,
                    )

                val result = lyricsRepository.lookup(request)
                if (lastToken != token) return@launch // Stale check

                mutableState.value =
                    when (result) {
                        is LyricsLookupResult.Found -> {
                            when (val parsed = result.lyrics) {
                                is SyncedLyrics ->
                                    R16LyricsState.Synced(
                                        token,
                                        parsed,
                                        result.record.sourceId,
                                        result.record.id,
                                    )
                                is PlainLyrics ->
                                    R16LyricsState.Unsynced(
                                        token,
                                        parsed,
                                        result.record.sourceId,
                                        result.record.id,
                                    )
                            }
                        }
                        LyricsLookupResult.NotFound -> R16LyricsState.Unavailable(token)
                        is LyricsLookupResult.Failure ->
                            R16LyricsState.Failed(
                                token,
                                result.kind,
                                result.retryable,
                                result.message,
                            )
                    }
            }
    }
}
