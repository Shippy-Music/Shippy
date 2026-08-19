/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationBootstrapStore.kt is part of Auxio.
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

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal enum class R16MigrationStatus {
    NOT_STARTED,
    PREPARING,
    IMPORTING,
    VERIFYING,
    READY_TO_SWITCH,
    ACTIVE,
    FAILED_RECOVERABLE,
}

internal data class R16MigrationBootstrapState(
    val revision: Long,
    val status: R16MigrationStatus,
    val migrationId: String?,
    val currentPhase: LegacyImportPhase?,
    val completedPhases: Set<LegacyImportPhase>,
    val lastStableKey: String?,
    val backupSatisfied: Boolean,
    val legacyDatabaseSha256: String?,
    val updatedAtEpochMs: Long,
) {
    init {
        require(revision >= 0) { "Migration bootstrap revision cannot be negative" }
        require(updatedAtEpochMs >= 0) { "Migration bootstrap time cannot be negative" }
        require(migrationId == null || migrationId.isNotBlank()) {
            "Migration ID must be null or non-blank"
        }
        require(lastStableKey == null || lastStableKey.isNotBlank()) {
            "Migration checkpoint key must be null or non-blank"
        }
        require(legacyDatabaseSha256 == null || legacyDatabaseSha256.matches(SHA256_PATTERN)) {
            "Legacy database checksum must be lowercase SHA-256"
        }
        val ordered = completedPhases.sortedBy(LegacyImportPhase::ordinal)
        require(ordered == LegacyImportPhase.entries.take(ordered.size)) {
            "Completed migration phases must form a contiguous prefix"
        }
        if (status == R16MigrationStatus.NOT_STARTED) {
            require(
                migrationId == null &&
                    currentPhase == null &&
                    completedPhases.isEmpty() &&
                    lastStableKey == null &&
                    !backupSatisfied &&
                    legacyDatabaseSha256 == null
            ) {
                "Not-started migration state cannot contain active migration data"
            }
        } else {
            require(!migrationId.isNullOrBlank()) { "Active migration state requires an ID" }
        }
        if (
            status.ordinal >= R16MigrationStatus.IMPORTING.ordinal &&
                status != R16MigrationStatus.FAILED_RECOVERABLE
        ) {
            require(legacyDatabaseSha256 != null) {
                "Importing migration state requires the legacy database checksum"
            }
        }
        if (LegacyImportPhase.PREFLIGHT in completedPhases) {
            require(backupSatisfied) { "Completed M0 requires a verified backup" }
        }
        if (status == R16MigrationStatus.VERIFYING) {
            require(
                LegacyImportPhase.entries
                    .filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }
                    .all(completedPhases::contains)
            ) {
                "Verification cannot start before M0-M12 complete"
            }
        }
        if (status == R16MigrationStatus.READY_TO_SWITCH) {
            require(LegacyImportPhase.VERIFY in completedPhases && backupSatisfied) {
                "Ready-to-switch state requires verified M13 and backup"
            }
        }
        if (status == R16MigrationStatus.ACTIVE) {
            require(LegacyImportPhase.CUTOVER in completedPhases) {
                "Active R16 state requires completed M14 cutover"
            }
        }
    }

    companion object {
        fun notStarted(nowEpochMs: Long) =
            R16MigrationBootstrapState(
                revision = 0,
                status = R16MigrationStatus.NOT_STARTED,
                migrationId = null,
                currentPhase = null,
                completedPhases = emptySet(),
                lastStableKey = null,
                backupSatisfied = false,
                legacyDatabaseSha256 = null,
                updatedAtEpochMs = nowEpochMs,
            )
    }
}

internal sealed interface R16MigrationBootstrapLoadResult {
    data object Missing : R16MigrationBootstrapLoadResult

    data class Loaded(val state: R16MigrationBootstrapState) : R16MigrationBootstrapLoadResult

    data class Corrupt(val reason: String) : R16MigrationBootstrapLoadResult
}

internal class R16MigrationBootstrapStore(file: File) {
    private val atomicFile = AtomicFile(file)

    @Synchronized
    fun load(): R16MigrationBootstrapLoadResult {
        if (
            !atomicFile.baseFile.exists() &&
                !File(atomicFile.baseFile.path + ATOMIC_BACKUP_SUFFIX).exists()
        ) {
            return R16MigrationBootstrapLoadResult.Missing
        }
        return runCatching {
                val bytes =
                    atomicFile.openRead().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= MAX_BOOTSTRAP_BYTES) {
                                "Migration bootstrap file exceeds size limit"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                require(bytes.isNotEmpty()) { "Migration bootstrap file is empty" }
                decode(bytes)
            }
            .fold(
                onSuccess = R16MigrationBootstrapLoadResult::Loaded,
                onFailure = { error ->
                    R16MigrationBootstrapLoadResult.Corrupt(
                        error.message ?: "Migration bootstrap file is unreadable"
                    )
                },
            )
    }

    @Synchronized
    fun initialize(nowEpochMs: Long): R16MigrationBootstrapState {
        return when (val loaded = load()) {
            R16MigrationBootstrapLoadResult.Missing ->
                R16MigrationBootstrapState.notStarted(nowEpochMs).also(::write)
            is R16MigrationBootstrapLoadResult.Loaded -> loaded.state
            is R16MigrationBootstrapLoadResult.Corrupt ->
                error("Refusing to replace corrupt migration bootstrap: ${loaded.reason}")
        }
    }

    @Synchronized
    fun compareAndSet(
        expectedRevision: Long,
        next: R16MigrationBootstrapState,
    ): R16MigrationBootstrapState {
        val current =
            when (val loaded = load()) {
                R16MigrationBootstrapLoadResult.Missing -> error("Migration bootstrap is missing")
                is R16MigrationBootstrapLoadResult.Loaded -> loaded.state
                is R16MigrationBootstrapLoadResult.Corrupt ->
                    error("Migration bootstrap is corrupt: ${loaded.reason}")
            }
        check(current.revision == expectedRevision) { "Migration bootstrap revision is stale" }
        require(next.revision == expectedRevision + 1) {
            "Migration bootstrap revision must advance exactly once"
        }
        require(current.status.canTransitionTo(next.status)) {
            "Illegal migration status transition ${current.status} -> ${next.status}"
        }
        require(
            current.migrationId == null ||
                next.migrationId == current.migrationId ||
                (current.status == R16MigrationStatus.NOT_STARTED &&
                    next.status == R16MigrationStatus.PREPARING)
        ) {
            "Migration ID cannot change during an active migration"
        }
        require(next.completedPhases.containsAll(current.completedPhases)) {
            "Completed migration phases cannot be removed"
        }
        require(!current.backupSatisfied || next.backupSatisfied) {
            "Verified backup state cannot be revoked"
        }
        require(
            current.legacyDatabaseSha256 == null ||
                next.legacyDatabaseSha256 == current.legacyDatabaseSha256
        ) {
            "Legacy database checksum cannot change during migration"
        }
        require(next.updatedAtEpochMs >= current.updatedAtEpochMs) {
            "Migration bootstrap time cannot move backward"
        }
        write(next)
        return next
    }

    private fun write(state: R16MigrationBootstrapState) {
        val bytes = encode(state)
        require(bytes.size <= MAX_BOOTSTRAP_BYTES) { "Migration bootstrap exceeds size limit" }
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }
}

private fun R16MigrationStatus.canTransitionTo(next: R16MigrationStatus): Boolean =
    next == this ||
        when (this) {
            R16MigrationStatus.NOT_STARTED -> next == R16MigrationStatus.PREPARING
            R16MigrationStatus.PREPARING ->
                next == R16MigrationStatus.IMPORTING ||
                    next == R16MigrationStatus.FAILED_RECOVERABLE
            R16MigrationStatus.IMPORTING ->
                next == R16MigrationStatus.VERIFYING ||
                    next == R16MigrationStatus.FAILED_RECOVERABLE
            R16MigrationStatus.VERIFYING ->
                next == R16MigrationStatus.IMPORTING ||
                    next == R16MigrationStatus.READY_TO_SWITCH ||
                    next == R16MigrationStatus.FAILED_RECOVERABLE
            R16MigrationStatus.READY_TO_SWITCH ->
                next == R16MigrationStatus.VERIFYING ||
                    next == R16MigrationStatus.ACTIVE ||
                    next == R16MigrationStatus.FAILED_RECOVERABLE
            R16MigrationStatus.ACTIVE -> false
            R16MigrationStatus.FAILED_RECOVERABLE ->
                next == R16MigrationStatus.PREPARING ||
                    next == R16MigrationStatus.IMPORTING ||
                    next == R16MigrationStatus.VERIFYING
        }

private fun encode(state: R16MigrationBootstrapState): ByteArray {
    val payload = state.payloadJson().toString()
    return JSONObject()
        .put("format", BOOTSTRAP_FORMAT)
        .put("formatVersion", BOOTSTRAP_FORMAT_VERSION)
        .put("payload", JSONObject(payload))
        .put("sha256", payload.sha256())
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
}

private fun decode(bytes: ByteArray): R16MigrationBootstrapState {
    val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
    require(
        root.keys().asSequence().toSet() == setOf("format", "formatVersion", "payload", "sha256")
    ) {
        "Migration bootstrap contains unexpected fields"
    }
    require(root.getString("format") == BOOTSTRAP_FORMAT) {
        "Migration bootstrap format is unsupported"
    }
    require(root.getInt("formatVersion") == BOOTSTRAP_FORMAT_VERSION) {
        "Migration bootstrap version is unsupported"
    }
    val payload = root.getJSONObject("payload")
    require(
        payload.keys().asSequence().toSet() ==
            setOf(
                "revision",
                "status",
                "migrationId",
                "currentPhase",
                "completedPhases",
                "lastStableKey",
                "backupSatisfied",
                "legacyDatabaseSha256",
                "updatedAtEpochMs",
            )
    ) {
        "Migration bootstrap payload contains unexpected fields"
    }
    val completedJson = payload.getJSONArray("completedPhases")
    val completed =
        List(completedJson.length()) { index ->
                LegacyImportPhase.entries.single { it.code == completedJson.getString(index) }
            }
            .toSet()
    val state =
        R16MigrationBootstrapState(
            revision = payload.getLong("revision"),
            status = R16MigrationStatus.valueOf(payload.getString("status")),
            migrationId = payload.nullableString("migrationId"),
            currentPhase =
                payload.nullableString("currentPhase")?.let { code ->
                    LegacyImportPhase.entries.single { it.code == code }
                },
            completedPhases = completed,
            lastStableKey = payload.nullableString("lastStableKey"),
            backupSatisfied = payload.getBoolean("backupSatisfied"),
            legacyDatabaseSha256 = payload.nullableString("legacyDatabaseSha256"),
            updatedAtEpochMs = payload.getLong("updatedAtEpochMs"),
        )
    val canonicalPayload = state.payloadJson().toString()
    require(root.getString("sha256").matches(SHA256_PATTERN)) {
        "Migration bootstrap checksum is malformed"
    }
    require(
        MessageDigest.isEqual(
            root.getString("sha256").toByteArray(StandardCharsets.UTF_8),
            canonicalPayload.sha256().toByteArray(StandardCharsets.UTF_8),
        )
    ) {
        "Migration bootstrap checksum does not match"
    }
    return state
}

private fun R16MigrationBootstrapState.payloadJson(): JSONObject =
    JSONObject()
        .put("revision", revision)
        .put("status", status.name)
        .put("migrationId", migrationId ?: JSONObject.NULL)
        .put("currentPhase", currentPhase?.code ?: JSONObject.NULL)
        .put(
            "completedPhases",
            JSONArray(completedPhases.sortedBy(LegacyImportPhase::ordinal).map { it.code }),
        )
        .put("lastStableKey", lastStableKey ?: JSONObject.NULL)
        .put("backupSatisfied", backupSatisfied)
        .put("legacyDatabaseSha256", legacyDatabaseSha256 ?: JSONObject.NULL)
        .put("updatedAtEpochMs", updatedAtEpochMs)

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8)).joinToString(
        ""
    ) { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private const val BOOTSTRAP_FORMAT = "ShippyR16MigrationBootstrap"
private const val BOOTSTRAP_FORMAT_VERSION = 1
private const val MAX_BOOTSTRAP_BYTES = 64 * 1024
private const val ATOMIC_BACKUP_SUFFIX = ".bak"
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
