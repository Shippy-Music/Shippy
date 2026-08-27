/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPreCutoverRuntime.kt is part of Auxio.
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
package app.shippy.data.migration.precutover

import app.shippy.data.R16DataRuntime
import app.shippy.data.backup.R16PortableSettingsProvider
import app.shippy.data.backup.R16SanitizedLastFmConfigProvider
import app.shippy.data.migration.LegacyAssetVerifier
import app.shippy.data.migration.LegacyDownloadArtifactVerifier
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.R16M14Cutover
import app.shippy.data.migration.R16MigrationBootstrapLoadResult
import app.shippy.data.migration.R16MigrationBootstrapState
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.R16MigrationStatus
import app.shippy.data.migration.VerifiedLegacyAsset
import app.shippy.data.migration.orchestration.R16MigrationOrchestrationResult
import app.shippy.data.migration.orchestration.R16MigrationOrchestrator
import app.shippy.data.migration.pipeline.R16MigrationPipelineFactory
import app.shippy.data.migration.pipeline.R16MusikrMigrationBridge
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** M0-M13 progress visible through the migration UI; M14 is an explicit final action. */
public enum class R16MigrationPreCutoverPhase(val code: String) {
    PREFLIGHT("M0"),
    CANONICAL_TRACKS("M1"),
    CANDIDATES("M2"),
    LIBRARY_RELATIONSHIPS("M3"),
    USER_PLAYLISTS("M4"),
    DEVICE_PLAYLISTS("M5"),
    DOWNLOADS("M6"),
    LYRICS("M7"),
    LASTFM_OUTBOX("M8"),
    PLAYBACK_CHECKPOINT("M9"),
    SAVED_PROVIDER_ENTITIES("M10"),
    CREW("M11"),
    LOCAL_REINDEX("M12"),
    VERIFY("M13"),
}

public enum class R16MigrationPreCutoverStatus {
    NOT_STARTED,
    PREPARING,
    IMPORTING,
    VERIFYING,
    READY_TO_SWITCH,
    FAILED_RECOVERABLE,
}

public enum class R16MigrationPreCutoverOutcome {
    IMPORTING,
    READY_TO_SWITCH,
    CANCELLED,
    FAILED_RECOVERABLE,
}

/** Sanitized durable state for UI/process-resume code; Room and legacy locators stay private. */
public data class R16MigrationPreCutoverState(
    val status: R16MigrationPreCutoverStatus,
    val currentPhase: R16MigrationPreCutoverPhase?,
    val completedPhases: List<R16MigrationPreCutoverPhase>,
    val checkpointPresent: Boolean,
    val backupSatisfied: Boolean,
    val revision: Long,
) {
    val totalPhaseCount: Int
        get() = R16MigrationPreCutoverPhase.entries.size

    val completedPhaseCount: Int
        get() = completedPhases.size
}

/** Bounded, non-sensitive failure evidence suitable for a UI or a local report export. */
public data class R16MigrationPreCutoverFailureReport(
    val code: String,
    val status: R16MigrationPreCutoverStatus,
    val phase: R16MigrationPreCutoverPhase?,
    val revision: Long,
    val message: String,
)

public data class R16MigrationPreCutoverResult(
    val outcome: R16MigrationPreCutoverOutcome,
    val state: R16MigrationPreCutoverState,
    val failure: R16MigrationPreCutoverFailureReport? = null,
)

/** App-owned paths for one migration attempt. They are configuration, never returned as state. */
public class R16MigrationPreCutoverStorage(
    internal val bootstrapFile: File,
    internal val legacyDatabase: File,
    internal val snapshotDirectory: File,
    internal val recoveryArchive: File,
) {
    init {
        require(bootstrapFile.path.isNotBlank()) { "Migration bootstrap file is required" }
        require(legacyDatabase.path.isNotBlank()) { "Legacy database file is required" }
        require(snapshotDirectory.path.isNotBlank()) { "Migration snapshot directory is required" }
        require(recoveryArchive.path.isNotBlank()) { "Migration recovery archive is required" }
    }
}

public class R16MigrationPreCutoverFolderCheck(
    val label: String,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val required: Boolean,
    val requiresWritable: Boolean = false,
)

public class R16MigrationPreCutoverFolderInspection(
    internal val checks: List<R16MigrationPreCutoverFolderCheck>,
    internal val warningCodes: List<String> = emptyList(),
)

public fun interface R16MigrationPreCutoverFolderInspectionProvider {
    suspend fun inspect(): R16MigrationPreCutoverFolderInspection
}

public enum class R16MigrationAssetKind {
    CANDIDATE,
    DOWNLOAD_ARTIFACT,
}

/**
 * The only legacy asset data exposed to an app verifier. The locator is supplied as an input to
 * verification, never returned in public migration state or failure reports.
 */
public data class R16MigrationAssetVerificationRequest(
    val kind: R16MigrationAssetKind,
    val locator: String?,
    val expectedLength: Long?,
    val mimeType: String?,
)

/** Exact durable evidence needed by the normalized asset table after the app verifies a locator. */
public data class R16MigrationVerifiedAsset(
    val locationType: String,
    val location: String,
    val documentId: String? = null,
    val mediaStoreId: Long? = null,
    val displayName: String? = null,
    val contentLength: Long,
    val contentChecksum: String? = null,
) {
    init {
        require(locationType.isNotBlank()) { "Verified asset location type must not be blank" }
        require(location.isNotBlank()) { "Verified asset location must not be blank" }
        require(contentLength >= 0) { "Verified asset length cannot be negative" }
        require(contentChecksum == null || contentChecksum.isNotBlank()) {
            "Verified asset checksum must not be blank"
        }
    }
}

public fun interface R16MigrationAssetVerifier {
    suspend fun verify(request: R16MigrationAssetVerificationRequest): R16MigrationVerifiedAsset?
}

public class R16MigrationPreCutoverOptions(
    internal val storage: R16MigrationPreCutoverStorage,
    internal val folderInspectionProvider: R16MigrationPreCutoverFolderInspectionProvider,
    internal val musikrBridge: R16MusikrMigrationBridge,
    internal val assetVerifier: R16MigrationAssetVerifier,
    val pageSize: Int = 500,
    val includeHistory: Boolean = false,
    internal val portableSettingsProvider: R16PortableSettingsProvider? = null,
    internal val sanitizedLastFmConfigProvider: R16SanitizedLastFmConfigProvider? = null,
) {
    init {
        require(pageSize in 1..R16MigrationPipelineFactory.MAX_PAGE_SIZE) {
            "Migration page size is outside the supported bound"
        }
    }
}

/** Raised when the durable bootstrap cannot be trusted or is outside this facade's scope. */
public class R16MigrationPreCutoverBootstrapException internal constructor() :
    IllegalStateException("R16 migration bootstrap is corrupt or unreadable")

/** Raised if a pre-cutover caller observes M14/ACTIVE state. */
public class R16MigrationPreCutoverCutoverStateException internal constructor() :
    IllegalStateException("R16 pre-cutover runtime cannot observe an active or cutover state")

/**
 * Public app boundary around the reviewed M0-M13 composition and the guarded M14 writer.
 *
 * The supplied [R16DataRuntime] remains owned by the caller. This facade only closes the migration
 * composition and any lazily-opened legacy snapshot reader.
 */
public class R16MigrationPreCutoverRuntime
private constructor(
    private val bootstrap: R16MigrationBootstrapStore,
    private val orchestrator: R16MigrationOrchestrator,
    private val cutoverAction: (suspend () -> Unit)?,
    private val composition: Closeable?,
    private val nowEpochMs: () -> Long,
) : Closeable {
    @Volatile private var closed = false

    /** Load durable progress without creating or mutating the bootstrap file. */
    public fun loadState(): R16MigrationPreCutoverState {
        ensureOpen()
        return publicState(loadInternalState())
    }

    /**
     * Run or resume the existing bounded-page orchestrator. A later call resumes the persisted
     * phase/checkpoint after process death or a recoverable failure.
     */
    public suspend fun run(
        migrationId: String,
        cancellationRequested: suspend () -> Boolean = { false },
    ): R16MigrationPreCutoverResult {
        ensureOpen()
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        rejectUnsupportedState(loadInternalState())
        val result = orchestrator.run(migrationId, cancellationRequested)
        return publicResult(result)
    }

    /** Explicit retry name for hosts that distinguish a failed attempt from first-run work. */
    public suspend fun retry(
        migrationId: String,
        cancellationRequested: suspend () -> Boolean = { false },
    ): R16MigrationPreCutoverResult = run(migrationId, cancellationRequested)

    /**
     * Performs the one-way M14 transition only after M0-M13 reached READY_TO_SWITCH. The M14 writer
     * revalidates all durable evidence before atomically marking the bootstrap ACTIVE.
     */
    public suspend fun activateCutover() {
        ensureOpen()
        check(loadInternalState().status == R16MigrationStatus.READY_TO_SWITCH) {
            "R16 cutover requires READY_TO_SWITCH"
        }
        checkNotNull(cutoverAction) { "R16 cutover is unavailable in this runtime" }.invoke()
    }

    /** Cancel only the bounded M0-M13 work; an active cutover is never cancellable. */
    public suspend fun cancel(
        migrationId: String,
        reason: String = "Migration cancelled by request",
    ): R16MigrationPreCutoverResult {
        ensureOpen()
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(reason.isNotBlank()) { "Migration cancellation reason must not be blank" }
        rejectUnsupportedState(loadInternalState())
        return publicResult(orchestrator.cancel(migrationId, reason))
    }

    /** Export only a bounded, sanitized failure result; the caller retains ownership of output. */
    public fun exportFailureReport(result: R16MigrationPreCutoverResult, output: OutputStream) {
        ensureOpen()
        val failure =
            requireNotNull(result.failure) { "Only failed or cancelled results have reports" }
        val bytes =
            JSONObject()
                .put("format", FAILURE_REPORT_FORMAT)
                .put("formatVersion", FAILURE_REPORT_VERSION)
                .put("outcome", result.outcome.name)
                .put("status", failure.status.name)
                .put("phase", failure.phase?.code ?: JSONObject.NULL)
                .put("revision", failure.revision)
                .put("code", failure.code)
                .put("message", failure.message)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FAILURE_REPORT_BYTES) { "Failure report exceeds its bound" }
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        composition?.close()
    }

    private fun loadInternalState(): R16MigrationBootstrapState {
        return when (val loaded = bootstrap.load()) {
            R16MigrationBootstrapLoadResult.Missing ->
                R16MigrationBootstrapState.notStarted(nowEpochMs())
            is R16MigrationBootstrapLoadResult.Loaded -> loaded.state
            is R16MigrationBootstrapLoadResult.Corrupt ->
                throw R16MigrationPreCutoverBootstrapException()
        }
    }

    private fun publicResult(
        result: R16MigrationOrchestrationResult
    ): R16MigrationPreCutoverResult {
        rejectUnsupportedState(result.state)
        val state = publicState(result.state)
        val outcome =
            when (result.outcome.name) {
                "IMPORTING" -> R16MigrationPreCutoverOutcome.IMPORTING
                "READY_TO_SWITCH" -> R16MigrationPreCutoverOutcome.READY_TO_SWITCH
                "CANCELLED" -> R16MigrationPreCutoverOutcome.CANCELLED
                "FAILED_RECOVERABLE" -> R16MigrationPreCutoverOutcome.FAILED_RECOVERABLE
                else -> throw R16MigrationPreCutoverCutoverStateException()
            }
        val failure =
            if (
                outcome == R16MigrationPreCutoverOutcome.CANCELLED ||
                    outcome == R16MigrationPreCutoverOutcome.FAILED_RECOVERABLE
            ) {
                failureReport(outcome, state, result.failureMessage)
            } else {
                null
            }
        return R16MigrationPreCutoverResult(outcome, state, failure)
    }

    private fun publicState(state: R16MigrationBootstrapState): R16MigrationPreCutoverState {
        rejectUnsupportedState(state)
        return R16MigrationPreCutoverState(
            status = state.status.toPublic(),
            currentPhase = state.currentPhase?.toPublicOrNull(),
            completedPhases =
                state.completedPhases.sortedBy(LegacyImportPhase::ordinal).map { it.toPublic() },
            checkpointPresent = state.lastStableKey != null,
            backupSatisfied = state.backupSatisfied,
            revision = state.revision,
        )
    }

    private fun rejectUnsupportedState(state: R16MigrationBootstrapState) {
        if (
            state.status == R16MigrationStatus.ACTIVE ||
                LegacyImportPhase.CUTOVER in state.completedPhases ||
                (state.currentPhase == LegacyImportPhase.CUTOVER &&
                    state.status != R16MigrationStatus.READY_TO_SWITCH)
        ) {
            throw R16MigrationPreCutoverCutoverStateException()
        }
    }

    private fun ensureOpen() {
        check(!closed) { "R16 migration pre-cutover runtime is closed" }
    }

    public companion object {
        private const val FAILURE_REPORT_FORMAT = "ShippyR16MigrationFailureReportV1"
        private const val FAILURE_REPORT_VERSION = 1
        private const val MAX_FAILURE_REPORT_BYTES = 16 * 1_024

        private fun failureReport(
            outcome: R16MigrationPreCutoverOutcome,
            state: R16MigrationPreCutoverState,
            rawMessage: String?,
        ): R16MigrationPreCutoverFailureReport {
            val code =
                if (outcome == R16MigrationPreCutoverOutcome.CANCELLED) {
                    "CANCELLED"
                } else {
                    classifyFailure(rawMessage)
                }
            return R16MigrationPreCutoverFailureReport(
                code = code,
                status = state.status,
                phase = state.currentPhase,
                revision = state.revision,
                message = publicFailureMessage(code),
            )
        }

        private fun classifyFailure(rawMessage: String?): String {
            val message = rawMessage.orEmpty().uppercase()
            return when {
                "BOOTSTRAP" in message || "CORRUPT" in message -> "BOOTSTRAP_INVALID"
                "INSUFFICIENT FREE SPACE" in message || "LOW STORAGE" in message -> "LOW_STORAGE"
                "CHECKSUM" in message ||
                    "SOURCE CHANGED" in message ||
                    "LEGACY SOURCE" in message -> "SOURCE_CHANGED"
                "BACKUP" in message -> "BACKUP_REQUIRED"
                else -> "MIGRATION_FAILED"
            }
        }

        private fun publicFailureMessage(code: String): String =
            when (code) {
                "CANCELLED" -> "Migration cancelled before cutover."
                "BOOTSTRAP_INVALID" -> "Durable migration state is invalid."
                "LOW_STORAGE" -> "Not enough free storage is available for a safe migration."
                "SOURCE_CHANGED" -> "The legacy source changed during migration."
                "BACKUP_REQUIRED" -> "The verified recovery backup gate was not satisfied."
                else -> "Migration stopped with a recoverable failure."
            }

        /** Open a facade over the exact Room instance already owned by [dataRuntime]. */
        public fun open(
            dataRuntime: R16DataRuntime,
            options: R16MigrationPreCutoverOptions,
        ): R16MigrationPreCutoverRuntime {
            val bootstrap = R16MigrationBootstrapStore(options.storage.bootstrapFile)
            val composition =
                R16MigrationPreCutoverComposition(
                    bootstrap = bootstrap,
                    database = dataRuntime.roomDatabase,
                    legacyDatabase = options.storage.legacyDatabase,
                    snapshotDirectory = options.storage.snapshotDirectory,
                    recoveryArchive = options.storage.recoveryArchive,
                    folderInspectionProvider =
                        R16MigrationFolderInspectionProvider {
                            options.folderInspectionProvider.inspect().toInternal()
                        },
                    assetVerifier =
                        LegacyAssetVerifier { row ->
                            options.assetVerifier
                                .verify(
                                    R16MigrationAssetVerificationRequest(
                                        kind = R16MigrationAssetKind.CANDIDATE,
                                        locator = row.locator,
                                        expectedLength = row.contentLength,
                                        mimeType = row.mimeType,
                                    )
                                )
                                ?.toInternal()
                        },
                    artifactVerifier =
                        LegacyDownloadArtifactVerifier { row ->
                            options.assetVerifier
                                .verify(
                                    R16MigrationAssetVerificationRequest(
                                        kind = R16MigrationAssetKind.DOWNLOAD_ARTIFACT,
                                        locator = row.artifactUri,
                                        expectedLength = row.artifactLength ?: row.expectedBytes,
                                        mimeType = row.artifactMimeType ?: row.pendingMimeType,
                                    )
                                )
                                ?.toInternal()
                        },
                    musikrBridge = options.musikrBridge,
                    pageSize = options.pageSize,
                    includeHistory = options.includeHistory,
                    portableSettingsProvider = options.portableSettingsProvider,
                    sanitizedLastFmConfigProvider = options.sanitizedLastFmConfigProvider,
                )
            return try {
                R16MigrationPreCutoverRuntime(
                    bootstrap = bootstrap,
                    orchestrator = composition.orchestrator(),
                    cutoverAction = {
                        R16M14Cutover(options.storage.bootstrapFile, dataRuntime.roomDatabase)
                            .activate()
                        Unit
                    },
                    composition = composition,
                    nowEpochMs = System::currentTimeMillis,
                )
            } catch (error: Throwable) {
                composition.close()
                throw error
            }
        }

        /** Internal seam for focused facade tests; production uses [open]. */
        internal fun forTesting(
            bootstrapFile: File,
            orchestrator: R16MigrationOrchestrator,
            cutoverAction: (suspend () -> Unit)? = null,
            nowEpochMs: () -> Long = System::currentTimeMillis,
            composition: Closeable? = null,
        ): R16MigrationPreCutoverRuntime =
            R16MigrationPreCutoverRuntime(
                bootstrap = R16MigrationBootstrapStore(bootstrapFile),
                orchestrator = orchestrator,
                cutoverAction = cutoverAction,
                composition = composition,
                nowEpochMs = nowEpochMs,
            )
    }
}

private fun R16MigrationStatus.toPublic(): R16MigrationPreCutoverStatus =
    when (this) {
        R16MigrationStatus.NOT_STARTED -> R16MigrationPreCutoverStatus.NOT_STARTED
        R16MigrationStatus.PREPARING -> R16MigrationPreCutoverStatus.PREPARING
        R16MigrationStatus.IMPORTING -> R16MigrationPreCutoverStatus.IMPORTING
        R16MigrationStatus.VERIFYING -> R16MigrationPreCutoverStatus.VERIFYING
        R16MigrationStatus.READY_TO_SWITCH -> R16MigrationPreCutoverStatus.READY_TO_SWITCH
        R16MigrationStatus.FAILED_RECOVERABLE -> R16MigrationPreCutoverStatus.FAILED_RECOVERABLE
        R16MigrationStatus.ACTIVE -> throw R16MigrationPreCutoverCutoverStateException()
    }

private fun LegacyImportPhase.toPublic(): R16MigrationPreCutoverPhase {
    require(this != LegacyImportPhase.CUTOVER) { "M14 is outside the pre-cutover runtime" }
    return R16MigrationPreCutoverPhase.entries[ordinal]
}

private fun LegacyImportPhase.toPublicOrNull(): R16MigrationPreCutoverPhase? =
    takeUnless { it == LegacyImportPhase.CUTOVER }?.toPublic()

private fun R16MigrationPreCutoverFolderInspection.toInternal(): R16MigrationFolderInspection =
    R16MigrationFolderInspection(
        checks =
            checks.map { check ->
                R16MigrationFolderCheck(
                    label = check.label,
                    exists = check.exists,
                    readable = check.readable,
                    writable = check.writable,
                    required = check.required,
                    requiresWritable = check.requiresWritable,
                )
            },
        warningCodes = warningCodes,
    )

private fun R16MigrationVerifiedAsset.toInternal(): VerifiedLegacyAsset =
    VerifiedLegacyAsset(
        locationType = locationType,
        location = location,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        displayName = displayName,
        contentLength = contentLength,
        contentChecksum = contentChecksum,
    )
