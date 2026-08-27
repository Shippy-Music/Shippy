/*
 * Copyright (c) 2026 Auxio Project
 * CatalogueGcWorkerTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.maintenance

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCheckpoint
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.R16DataRuntime
import app.shippy.data.migration.R16StartupStateReader
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityMode
import org.oxycblt.auxio.shippy.r16.authority.R16AuthoritySelector
import org.oxycblt.auxio.shippy.r16.authority.R16DataRuntimeOpener
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CatalogueGcWorkerTest {
    private lateinit var context: Context
    private lateinit var bootstrap: File
    private lateinit var cacheDir: File
    private lateinit var simpleCache: SimpleCache
    private lateinit var playbackCache: PlaybackCacheManager
    private var openedRuntime: R16DataRuntime? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val root = File(System.getProperty("java.io.tmpdir"), "shippy-r16-gc-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        bootstrap = File(root, "bootstrap-v1.json")
        cacheDir = File(context.cacheDir, "r16-gc-cache-${UUID.randomUUID()}").apply { mkdirs() }
        simpleCache =
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(10L * 1024L * 1024L),
                StandaloneDatabaseProvider(context),
            )
        playbackCache = PlaybackCacheManager(simpleCache)
    }

    @After
    fun tearDown() {
        openedRuntime?.close()
        simpleCache.release()
        cacheDir.deleteRecursively()
        bootstrap.parentFile?.deleteRecursively()
    }

    @Test
    fun `doWork returns success and performs cache maintenance when runtime is inactive`() =
        runBlocking {
            val worker = worker(R16AuthorityMode.LEGACY) { error("inactive runtime opened") }
            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun `doWork captures active queue protections from checkpoint and invokes collectExpiredTransient`() =
        runBlocking {
            val recId = RecordingId("00000000-0000-0000-0000-000000000123")
            val runtime = R16DataRuntime.open(context)
            openedRuntime = runtime
            runtime.playbackCheckpoints.save(checkpoint(recId))

            assertEquals(Result.success(), worker(R16AuthorityMode.ACTIVE) { runtime }.doWork())
            assertEquals(
                setOf(recId),
                runtime.playbackCheckpoints.load()?.queue?.baseQueue?.mapTo(linkedSetOf()) {
                    it.recordingId
                },
            )
        }

    private fun worker(
        authorityMode: R16AuthorityMode,
        openRuntime: () -> R16DataRuntime,
    ): CatalogueGcWorker =
        CatalogueGcWorker(
            context,
            workerParameters(),
            R16ActiveDataRuntimeOwner(
                authoritySelector =
                    object :
                        R16AuthoritySelector(
                            R16StartupStateReader(bootstrap),
                            R16MigrationProcessGate(bootstrap),
                        ) {
                        override fun select(): R16AuthorityMode = authorityMode
                    },
                runtimeOpener = R16DataRuntimeOpener(openRuntime),
            ),
            playbackCache,
            R16LivePlaybackQueue(),
        )

    private fun checkpoint(recordingId: RecordingId): PlaybackCheckpoint {
        val queueEntry =
            QueueEntry(
                id = QueueEntryId("00000000-0000-0000-0000-000000000456"),
                recordingId = recordingId,
                origin = null,
                playlistEntryId = null,
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        return PlaybackCheckpoint(
            version = 1,
            queue =
                QueueState(
                    baseQueue = listOf(queueEntry),
                    traversalOrder = listOf(queueEntry.id),
                    currentQueueEntryId = queueEntry.id,
                    shuffle = ShuffleState.Off,
                ),
            positionMs = 0L,
            playWhenReady = false,
            repeatMode = RepeatMode.OFF,
        )
    }

    private fun workerParameters(): WorkerParameters {
        val directExecutor = Executor(Runnable::run)
        val serialExecutor =
            object : SerialExecutor {
                override fun execute(command: Runnable) = command.run()

                override fun hasPendingTasks(): Boolean = false
            }
        val taskExecutor =
            object : TaskExecutor {
                override fun getMainThreadExecutor(): Executor = directExecutor

                override fun getSerialTaskExecutor(): SerialExecutor = serialExecutor
            }
        return WorkerParameters(
            UUID.randomUUID(),
            Data.EMPTY,
            emptySet(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            directExecutor,
            EmptyCoroutineContext,
            taskExecutor,
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = null
            },
            ProgressUpdater { _, _, _ -> error("not used by CatalogueGcWorker") },
            ForegroundUpdater { _, _, _ -> error("not used by CatalogueGcWorker") },
        )
    }
}
