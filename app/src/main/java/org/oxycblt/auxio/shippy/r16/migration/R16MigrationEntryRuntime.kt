/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationEntryRuntime.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import android.content.Context
import app.shippy.data.R16DataRuntime
import app.shippy.data.backup.R16BackupArchiveMetadata
import app.shippy.data.migration.precutover.R16MigrationPreCutoverOptions
import app.shippy.data.migration.precutover.R16MigrationPreCutoverResult
import app.shippy.data.migration.precutover.R16MigrationPreCutoverRuntime
import app.shippy.data.migration.precutover.R16MigrationPreCutoverState
import app.shippy.data.migration.precutover.R16MigrationPreCutoverStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.shippy.download.DownloadDestinationSettings
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.r16.source.MusikrLocalMediaEngine
import org.oxycblt.auxio.shippy.r16.source.R16MusikrMigrationBridgeImpl

/**
 * Application-owned migration runtime. It owns one process-scoped R16 Room instance for the
 * isolated migration host and exposes the guarded M14 transition without exposing playback.
 */
@Singleton
public class R16MigrationEntryRuntime
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val processGate: R16MigrationProcessGate,
    private val musicRepository: MusicRepository,
    private val localMediaEngine: MusikrLocalMediaEngine,
    private val musicSettings: MusicSettings,
    private val downloadDestinationSettings: DownloadDestinationSettings,
    private val lastFmCredentials: LastFmCredentialRepository,
) : Closeable {
    private val lock = Any()
    private val runtimeMutex = Mutex()
    private val cancellationRequested = AtomicBoolean(false)
    private var owner: RuntimeOwner? = null
    private var lastFailure: R16MigrationPreCutoverResult? = null
    private var closed = false

    /** Reads durable state without starting an import or creating the bootstrap marker. */
    public suspend fun loadState(): R16MigrationPreCutoverState =
        withContext(Dispatchers.IO) { runtime().migration.loadState() }

    /** Starts one visible run/resume session; cancellation stays requested across every page. */
    public fun beginOrResume() {
        cancellationRequested.set(false)
    }

    /** Runs exactly one bounded M0-M13 orchestration step; the host decides when to continue. */
    public suspend fun runNext(): R16MigrationPreCutoverResult =
        withContext(Dispatchers.IO) {
            val lease =
                checkNotNull(processGate.tryAcquireMigrationLock()) {
                    "Legacy work is still draining; migration can be retried safely."
                }
            try {
                runtime()
                    .migration
                    .run(MIGRATION_ID) { cancellationRequested.get() }
                    .also { if (it.failure != null) lastFailure = it }
            } finally {
                lease.close()
            }
        }

    /** Runs the existing M14 writer while legacy access remains process-locked. */
    public suspend fun activateCutover() {
        withContext(Dispatchers.IO) {
            val lease =
                checkNotNull(processGate.tryAcquireMigrationLock()) {
                    "Legacy work is still draining; R16 activation can be retried safely."
                }
            try {
                runtime().migration.activateCutover()
                // M14 is durable. Release the isolated migration Room owner before the activity
                // recreates into the process-owned ACTIVE runtime.
                close()
            } finally {
                lease.close()
            }
        }
    }

    /** The next bounded page observes this request and records a recoverable cancellation. */
    public fun requestCancellation() {
        cancellationRequested.set(true)
    }

    /**
     * Exports the last sanitized failure result without exposing locators, Room rows, or secrets.
     */
    public suspend fun exportFailureReport(output: OutputStream) {
        withContext(Dispatchers.IO) {
            val result = requireNotNull(lastFailure) { "No migration failure report is available" }
            runtime().migration.exportFailureReport(result, output)
        }
    }

    /**
     * Copies M13's already-produced recovery archive, then verifies the destination with the
     * normalized backup runtime. This is owner export evidence only; it cannot activate R16.
     */
    public suspend fun exportReadyBackup(
        openOutput: () -> OutputStream,
        reopen: () -> InputStream,
    ): R16BackupArchiveMetadata =
        withContext(Dispatchers.IO) {
            val current = runtime()
            check(
                current.migration.loadState().status == R16MigrationPreCutoverStatus.READY_TO_SWITCH
            ) {
                "A verified migration backup is available only at READY_TO_SWITCH"
            }
            val source = current.recoveryArchive
            check(source.isFile) { "M13 recovery archive is missing" }
            val sourceMetadata = FileInputStream(source).use(current.dataRuntime.backups::verify)
            openOutput().use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
            val destinationMetadata = reopen().use(current.dataRuntime.backups::verify)
            check(sourceMetadata == destinationMetadata) {
                "The exported backup verification does not match M13's recovery archive"
            }
            destinationMetadata
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            owner?.close()
            owner = null
        }
    }

    private suspend fun runtime(): RuntimeOwner =
        runtimeMutex.withLock {
            synchronized(lock) {
                check(!closed) { "R16 migration entry runtime is closed" }
                owner
            }
                ?: createRuntime().let { created ->
                    synchronized(lock) {
                        if (closed) {
                            created.close()
                            error("R16 migration entry runtime is closed")
                        }
                        owner = created
                        created
                    }
                }
        }

    private suspend fun createRuntime(): RuntimeOwner {
        val storage = R16MigrationStorageAdapter(context)
        val dataRuntime = R16DataRuntime.open(context)
        return try {
            val backupCoverage =
                R16MigrationBackupCoverageAdapter(context, lastFmCredentials).create()
            val migration =
                R16MigrationPreCutoverRuntime.open(
                    dataRuntime = dataRuntime,
                    options =
                        R16MigrationPreCutoverOptions(
                            storage = storage.storage(),
                            folderInspectionProvider =
                                R16MigrationFolderInspectionAdapter(
                                    context = context,
                                    musicSettings = musicSettings,
                                    downloadDestinationSettings = downloadDestinationSettings,
                                ),
                            musikrBridge =
                                R16MusikrMigrationBridgeImpl.create(
                                    musicRepository = musicRepository,
                                    localMediaEngine = localMediaEngine,
                                    dataRuntime = dataRuntime,
                                ),
                            assetVerifier = R16MigrationAssetVerifierAdapter(context),
                            portableSettingsProvider = backupCoverage.portableSettingsProvider,
                            sanitizedLastFmConfigProvider =
                                backupCoverage.sanitizedLastFmConfigProvider,
                        ),
                )
            RuntimeOwner(dataRuntime, migration, storage.recoveryArchivePath)
        } catch (error: Throwable) {
            dataRuntime.close()
            throw error
        }
    }

    private class RuntimeOwner(
        val dataRuntime: R16DataRuntime,
        val migration: R16MigrationPreCutoverRuntime,
        val recoveryArchive: java.io.File,
    ) : Closeable {
        override fun close() {
            migration.close()
            dataRuntime.close()
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1_024
        // The bootstrap state is scoped to this device's R16 database, so a stable app migration
        // identifier safely survives process death without carrying user data in UI state.
        const val MIGRATION_ID = "r16-precutover-v1"
    }
}
