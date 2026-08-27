/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackSpineTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackCheckpoint
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.data.listening.R16ListeningSessionPersistResult
import app.shippy.data.listening.R16ListeningSessionRecord
import app.shippy.data.listening.R16ListeningSessionRepository
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16PlaybackSourceOptions
import app.shippy.data.playback.R16PlaybackSourceRepository
import app.shippy.data.playback.R16RecordingPresentation
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.playback.service.PlaybackRequestHeaders
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PlaybackSpineTest {
    @Test
    fun `factory stays inert until a selector explicitly creates its one R16 authority`() =
        runBlocking {
            val engine = RecordingEngine(autoCommit = false)
            val factory =
                R16PlaybackSpineFactory(ProviderRegistry(emptySet()), PlaybackRequestHeaders())

            val spine =
                factory.create(
                    this,
                    engine,
                    RetainedCheckpointRepository(),
                    FixedPresentationRepository(),
                    EmptyPlaybackSourceRepository,
                )

            assertEquals(0, engine.releaseCount)
            assertTrue(engine.transactions.tryReceive().isFailure)
            spine.release()
            assertEquals(1, engine.releaseCount)
        }

    @Test
    fun `spine releases engine before draining finalized listening sessions`() = runBlocking {
        val engine = RecordingEngine(autoCommit = true)
        val repository = RecordingListeningSessionRepository { engine.releaseCount }
        var checkpointSaveReleaseCount: Int? = null
        val delivery = R16ListeningSessionDelivery(this, repository)
        val entry = entry(1, recordingId(1))
        val spine =
            R16PlaybackSpine(
                this,
                engine,
                RegistryPlaybackSourcePreparer(FixedLocatorResolver(), PlaybackLocatorRegistry()),
                RetainedCheckpointRepository { checkpointSaveReleaseCount = engine.releaseCount },
                FixedPresentationRepository(),
                checkpointDelayMs = 60_000,
                listeningSessionDelivery = delivery,
            )

        spine.attach()
        assertTrue(
            spine.dispatch(PlaybackCommand.PlayContext(listOf(entry), entry.id))
                is PlaybackCommandResult.Accepted
        )
        withTimeout(TEST_TIMEOUT_MS) {
            spine.snapshots.first { it.committedQueueEntryId == entry.id }
        }

        spine.release()

        assertEquals(1, engine.releaseCount)
        assertEquals(0, checkpointSaveReleaseCount)
        assertEquals(1, repository.records.size)
        assertEquals(1, repository.engineReleaseCounts.single())
    }

    @Test
    fun `one spine keeps selected occurrence authoritative through materialization system and restore`() =
        runBlocking {
            val checkpoints = RetainedCheckpointRepository()
            val firstEngine = RecordingEngine(autoCommit = false)
            val firstRegistry = PlaybackLocatorRegistry()
            val spine =
                R16PlaybackSpine(
                    this,
                    firstEngine,
                    RegistryPlaybackSourcePreparer(FixedLocatorResolver(), firstRegistry),
                    checkpoints,
                    FixedPresentationRepository(),
                    checkpointDelayMs = 60_000,
                )
            val recording = recordingId(1)
            val stale = entry(1, recording)
            val selected = entry(2, recording)

            spine.attach()
            assertTrue(
                spine.dispatch(PlaybackCommand.PlayContext(listOf(stale), stale.id))
                    is PlaybackCommandResult.Accepted
            )
            val staleTransaction =
                withTimeout(TEST_TIMEOUT_MS) { firstEngine.transactions.receive() }

            assertTrue(
                spine.dispatch(PlaybackCommand.PlayContext(listOf(stale, selected), selected.id))
                    is PlaybackCommandResult.Accepted
            )
            val selectedTransaction =
                withTimeout(TEST_TIMEOUT_MS) { firstEngine.transactions.receive() }
            val materialized =
                Media3TransactionProjector.materialize(
                    Media3TransactionProjector.project(selectedTransaction),
                    RegistryMedia3ItemFactory(firstRegistry, PlaybackRequestHeaders()),
                )
            val selectedIndex =
                selectedTransaction.window.indexOfFirst { it.queueEntryId == selected.id }

            assertEquals(selected.id.value, materialized[selectedIndex].mediaId)
            assertEquals(
                "content://r16/${recording.value}",
                materialized[selectedIndex].localConfiguration?.uri.toString(),
            )

            firstEngine.emit(PlayerObservation.CurrentItemCommitted(staleTransaction.tag))
            withTimeout(TEST_TIMEOUT_MS) {
                while (
                    spine.traceSnapshot().none {
                        it.detail == "ENGINE_COMMITTED" && it.queueEntryId == stale.id && it.ignored
                    }
                ) {
                    delay(1)
                }
            }
            assertNull(spine.snapshots.value.committedQueueEntryId)

            firstEngine.emit(PlayerObservation.CurrentItemCommitted(selectedTransaction.tag))
            withTimeout(TEST_TIMEOUT_MS) {
                spine.snapshots.first { it.committedQueueEntryId == selected.id }
            }
            assertEquals(selected.id, spine.snapshots.value.committedQueueEntryId)
            assertEquals(selected.id, spine.system.states.value.displayQueueEntryId)
            spine.release()

            val durableCheckpoint = checkNotNull(checkpoints.value)
            assertEquals(selected.id, durableCheckpoint.queue.currentQueueEntryId)
            assertEquals(
                listOf(stale.id, selected.id),
                durableCheckpoint.queue.baseQueue.map(QueueEntry::id),
            )

            val restoredEngine = RecordingEngine(autoCommit = false)
            val restoredRegistry = PlaybackLocatorRegistry()
            val restored =
                R16PlaybackSpine(
                    this,
                    restoredEngine,
                    RegistryPlaybackSourcePreparer(FixedLocatorResolver(), restoredRegistry),
                    checkpoints,
                    FixedPresentationRepository(),
                    checkpointDelayMs = 60_000,
                )

            restored.attach()
            val restoredTransaction =
                withTimeout(TEST_TIMEOUT_MS) { restoredEngine.transactions.receive() }
            restoredEngine.emit(PlayerObservation.CurrentItemCommitted(restoredTransaction.tag))
            restoredEngine.emit(
                PlayerObservation.PhaseChanged(
                    restoredTransaction.tag.generation,
                    restoredTransaction.expectedCurrentEntryId,
                    CommittedEnginePhase.READY,
                )
            )
            withTimeout(TEST_TIMEOUT_MS) {
                restored.snapshots.first { it.committedQueueEntryId == selected.id }
            }

            assertEquals(selected.id, restoredTransaction.expectedCurrentEntryId)
            assertEquals(false, restoredTransaction.playWhenReady)
            assertEquals(selected.id, restored.snapshots.value.committedQueueEntryId)
            restored.release()
        }

    @Test
    fun `spine passes authenticated listening identity to persisted record`() = runBlocking {
        val engine = RecordingEngine(autoCommit = true)
        val repository = RecordingListeningSessionRepository { engine.releaseCount }
        val delivery = R16ListeningSessionDelivery(this, repository)
        val entry = entry(1, recordingId(1))
        val identity =
            ListeningSessionIdentity(scrobbleAuthorized = true, accountId = "hashed-account-id")
        val spine =
            R16PlaybackSpine(
                this,
                engine,
                RegistryPlaybackSourcePreparer(FixedLocatorResolver(), PlaybackLocatorRegistry()),
                RetainedCheckpointRepository(),
                FixedPresentationRepository(),
                checkpointDelayMs = 60_000,
                listeningSessionDelivery = delivery,
                listeningSessionIdentityProvider = { identity },
            )

        spine.attach()
        assertTrue(
            spine.dispatch(PlaybackCommand.PlayContext(listOf(entry), entry.id))
                is PlaybackCommandResult.Accepted
        )
        withTimeout(TEST_TIMEOUT_MS) {
            spine.snapshots.first { it.committedQueueEntryId == entry.id }
        }

        spine.release()

        val record = repository.records.single()
        assertTrue(record.scrobbleAuthorized)
        assertEquals("hashed-account-id", record.accountId)
    }

    private class FixedLocatorResolver : PlaybackLocatorResolver {
        override suspend fun resolve(
            request: PlaybackPreparationRequest
        ): PlaybackLocatorResolution =
            PlaybackLocatorResolution.Ready(
                PlaybackLocator(
                    stableKey = "asset:${request.recordingId.value}",
                    sourceReferenceId =
                        SourceReferenceId(
                            request.recordingId.value.replace("00000000", "10000000")
                        ),
                    mediaAssetId =
                        MediaAssetId(request.recordingId.value.replace("00000000", "20000000")),
                    uri = "content://r16/${request.recordingId.value}",
                    mimeType = "audio/flac",
                )
            )
    }

    private class FixedPresentationRepository : R16PlaybackPresentationRepository {
        override fun observe(
            recordingIds: Set<RecordingId>
        ): Flow<Map<RecordingId, R16RecordingPresentation>> =
            flowOf(
                recordingIds.associateWith { recordingId ->
                    R16RecordingPresentation(
                        recordingId,
                        title = "Track ${recordingId.value}",
                        artist = "Artist",
                        releaseTitle = null,
                        artworkLocation = null,
                        durationMs = 120_000,
                    )
                }
            )
    }

    private class RetainedCheckpointRepository(private val onSave: (() -> Unit)? = null) :
        R16PlaybackCheckpointRepository {
        var value: PlaybackCheckpoint? = null

        override suspend fun load(): PlaybackCheckpoint? = value

        override suspend fun save(checkpoint: PlaybackCheckpoint) {
            onSave?.invoke()
            value = checkpoint
        }

        override suspend fun clear() {
            value = null
        }
    }

    private object EmptyPlaybackSourceRepository : R16PlaybackSourceRepository {
        override suspend fun options(recordingId: RecordingId) =
            R16PlaybackSourceOptions(emptyList(), emptyList())
    }

    private class RecordingEngine(private val autoCommit: Boolean) : PlayerEngine {
        private val mutableObservations =
            MutableSharedFlow<PlayerObservation>(extraBufferCapacity = 16)
        override val observations: Flow<PlayerObservation> = mutableObservations
        val transactions = Channel<PlayerTransaction>(Channel.UNLIMITED)
        var releaseCount = 0

        override suspend fun apply(transaction: PlayerTransaction) {
            transactions.send(transaction)
            if (autoCommit) {
                mutableObservations.emit(PlayerObservation.CurrentItemCommitted(transaction.tag))
                mutableObservations.emit(
                    PlayerObservation.PhaseChanged(
                        transaction.tag.generation,
                        transaction.expectedCurrentEntryId,
                        CommittedEnginePhase.READY,
                    )
                )
            }
        }

        suspend fun emit(observation: PlayerObservation) {
            mutableObservations.emit(observation)
        }

        override suspend fun setPlayWhenReady(value: Boolean) = Unit

        override suspend fun seek(queueEntryId: QueueEntryId, positionMs: Long) = Unit

        override suspend fun setRepeat(mode: RepeatMode) = Unit

        override suspend fun release() {
            releaseCount++
        }
    }

    private class RecordingListeningSessionRepository(private val engineReleaseCount: () -> Int) :
        R16ListeningSessionRepository {
        val records = mutableListOf<R16ListeningSessionRecord>()
        val engineReleaseCounts = mutableListOf<Int>()

        override suspend fun persist(
            session: R16ListeningSessionRecord
        ): R16ListeningSessionPersistResult {
            records += session
            engineReleaseCounts += engineReleaseCount()
            return R16ListeningSessionPersistResult(scrobbleQueued = false)
        }
    }

    private fun entry(value: Int, recordingId: RecordingId) =
        QueueEntry(
            id = QueueEntryId(id(value)),
            recordingId = recordingId,
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun recordingId(value: Int) = RecordingId(id(value + 100))

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
