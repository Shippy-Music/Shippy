/*
 * Copyright (c) 2026 Auxio Project
 * R16M14Cutover.kt is part of Auxio.
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

import androidx.room.withTransaction
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal enum class R16M14CutoverOutcome {
    ACTIVATED,
    ALREADY_ACTIVE,
}

internal data class R16M14CutoverResult(
    val outcome: R16M14CutoverOutcome,
    val migrationId: String,
    val bootstrapRevision: Long,
)

/**
 * Data-only M14 cutover writer. The caller must hold the process migration lock before invoking
 * [activate].
 *
 * The Room audit is completed first. The atomic bootstrap is switched to ACTIVE last, so process
 * death between those writes remains recoverable from READY_TO_SWITCH and retry is idempotent.
 */
internal class R16M14Cutover(
    bootstrapFile: File,
    private val database: ShippyR16Database,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val afterAuditCommitted: () -> Unit = {},
) {
    private val bootstrap = R16MigrationBootstrapStore(bootstrapFile)
    private val mutex = Mutex()

    suspend fun activate(): R16M14CutoverResult =
        mutex.withLock {
            val state = requireLoadedBootstrap()
            if (state.status == R16MigrationStatus.ACTIVE) {
                val audit = requireActiveAudit(checkNotNull(state.migrationId))
                return@withLock result(R16M14CutoverOutcome.ALREADY_ACTIVE, state, audit)
            }

            requireReady(state)
            smokeRead()
            val audit = completeAudit(state)
            afterAuditCommitted()

            val active =
                state.copy(
                    revision = state.revision + 1,
                    status = R16MigrationStatus.ACTIVE,
                    currentPhase = null,
                    completedPhases = state.completedPhases + LegacyImportPhase.CUTOVER,
                    lastStableKey = null,
                    updatedAtEpochMs = maxOf(state.updatedAtEpochMs, audit.completedAtEpochMs!!),
                )
            val committed = bootstrap.compareAndSet(state.revision, active)
            result(R16M14CutoverOutcome.ACTIVATED, committed, audit)
        }

    private fun requireLoadedBootstrap(): R16MigrationBootstrapState =
        when (val loaded = bootstrap.load()) {
            R16MigrationBootstrapLoadResult.Missing -> error("M14 requires migration bootstrap")
            is R16MigrationBootstrapLoadResult.Corrupt ->
                error("M14 refuses corrupt migration bootstrap: ${loaded.reason}")
            is R16MigrationBootstrapLoadResult.Loaded -> loaded.state
        }

    private fun requireReady(state: R16MigrationBootstrapState) {
        check(state.status == R16MigrationStatus.READY_TO_SWITCH) {
            "M14 requires READY_TO_SWITCH, found ${state.status}"
        }
        check(state.currentPhase == LegacyImportPhase.CUTOVER) {
            "M14 requires CUTOVER as the next phase"
        }
        check(state.completedPhases == LegacyImportPhase.entries.dropLast(1).toSet()) {
            "M14 requires exactly M0-M13 completed"
        }
        check(state.backupSatisfied) { "M14 requires the verified backup gate" }
        checkNotNull(state.legacyDatabaseSha256) { "M14 requires the verified legacy checksum" }
    }

    private suspend fun completeAudit(state: R16MigrationBootstrapState): MigrationAuditEntity =
        database.withTransaction {
            val migrationId = checkNotNull(state.migrationId)
            val current =
                requireNotNull(database.migrationAuditDao().get(migrationId)) {
                    "M14 migration audit is missing"
                }
            if (current.status == AUDIT_STATUS_ACTIVE) {
                return@withTransaction validateActiveAudit(current)
            }

            validateReadyAudit(current)
            val completedAt =
                maxOf(nowEpochMs(), state.updatedAtEpochMs, current.startedAtEpochMs).also {
                    require(it >= 0) { "M14 completion time cannot be negative" }
                }
            val target = JSONObject(checkNotNull(current.targetCountsJson))
            target.put(
                M14_EVIDENCE_KEY,
                JSONObject()
                    .put("status", AUDIT_STATUS_ACTIVE)
                    .put("completedAtEpochMs", completedAt)
                    .put("targetSchemaVersion", ShippyR16Database.SCHEMA_VERSION)
                    .put("smokeRead", true),
            )
            val targetCounts = target.toString()
            val checksum = auditChecksum(current, completedAt, targetCounts)
            database
                .migrationAuditDao()
                .complete(
                    migrationId = migrationId,
                    completedAtEpochMs = completedAt,
                    targetCountsJson = targetCounts,
                    warningsJson = current.warningsJson,
                    checksum = checksum,
                    status = AUDIT_STATUS_ACTIVE,
                )
            validateActiveAudit(checkNotNull(database.migrationAuditDao().get(migrationId)))
        }

    private fun validateReadyAudit(audit: MigrationAuditEntity) {
        check(audit.completedAtEpochMs == null) { "READY_TO_SWITCH audit is already completed" }
        check(audit.status == AUDIT_STATUS_READY) {
            "M14 audit must be READY_TO_SWITCH, found ${audit.status}"
        }
        check(audit.sourceVersion in SUPPORTED_LEGACY_SCHEMA_VERSIONS) {
            "M14 audit source schema is unsupported"
        }
        check(audit.targetVersion == ShippyR16Database.SCHEMA_VERSION) {
            "M14 audit target schema is unsupported"
        }
        JSONObject(audit.sourceCountsJson)
        JSONArray(audit.warningsJson)
        val target = JSONObject(checkNotNull(audit.targetCountsJson))
        check(target.getJSONObject(M13_ARCHIVE_KEY).getBoolean("verified")) {
            "M14 requires verified M13 recovery archive evidence"
        }
    }

    private fun validateActiveAudit(audit: MigrationAuditEntity): MigrationAuditEntity {
        val completedAt = checkNotNull(audit.completedAtEpochMs) { "ACTIVE audit is incomplete" }
        check(audit.status == AUDIT_STATUS_ACTIVE) { "ACTIVE bootstrap requires ACTIVE audit" }
        check(audit.sourceVersion in SUPPORTED_LEGACY_SCHEMA_VERSIONS)
        check(audit.targetVersion == ShippyR16Database.SCHEMA_VERSION)
        val targetCounts = checkNotNull(audit.targetCountsJson)
        val evidence = JSONObject(targetCounts).getJSONObject(M14_EVIDENCE_KEY)
        check(evidence.getString("status") == AUDIT_STATUS_ACTIVE)
        check(evidence.getLong("completedAtEpochMs") == completedAt)
        check(evidence.getInt("targetSchemaVersion") == ShippyR16Database.SCHEMA_VERSION)
        check(evidence.getBoolean("smokeRead"))
        val expected = auditChecksum(audit, completedAt, targetCounts)
        val actual = checkNotNull(audit.checksum) { "ACTIVE audit checksum is missing" }
        check(
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                actual.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            "ACTIVE audit checksum does not match"
        }
        return audit
    }

    private suspend fun requireActiveAudit(migrationId: String): MigrationAuditEntity =
        validateActiveAudit(
            requireNotNull(database.migrationAuditDao().get(migrationId)) {
                "ACTIVE bootstrap requires completed migration audit"
            }
        )

    private fun smokeRead() {
        val sql =
            listOf(
                "SELECT COUNT(*) FROM recording",
                "SELECT COUNT(*) FROM library_recording",
                "SELECT COUNT(*) FROM playlist",
                "SELECT COUNT(*) FROM playback_checkpoint",
                "SELECT COUNT(*) FROM migration_audit",
            )
        sql.forEach { query ->
            database.openHelper.writableDatabase.query(query).use { cursor ->
                check(cursor.moveToFirst()) { "M14 smoke read returned no row" }
                cursor.getLong(0)
            }
        }
    }

    private fun result(
        outcome: R16M14CutoverOutcome,
        state: R16MigrationBootstrapState,
        audit: MigrationAuditEntity,
    ): R16M14CutoverResult {
        check(audit.migrationId == state.migrationId) { "Bootstrap and audit migration IDs differ" }
        return R16M14CutoverResult(outcome, audit.migrationId, state.revision)
    }
}

private fun auditChecksum(
    audit: MigrationAuditEntity,
    completedAtEpochMs: Long,
    targetCountsJson: String,
): String {
    val payload =
        listOf(
                audit.migrationId,
                audit.sourceVersion.toString(),
                audit.targetVersion.toString(),
                audit.startedAtEpochMs.toString(),
                completedAtEpochMs.toString(),
                audit.sourceCountsJson,
                targetCountsJson,
                audit.warningsJson,
                AUDIT_STATUS_ACTIVE,
            )
            .joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private const val AUDIT_STATUS_READY = "READY_TO_SWITCH"
private const val AUDIT_STATUS_ACTIVE = "ACTIVE"
private const val M13_ARCHIVE_KEY = "m13RecoveryArchive"
private const val M14_EVIDENCE_KEY = "m14Cutover"
