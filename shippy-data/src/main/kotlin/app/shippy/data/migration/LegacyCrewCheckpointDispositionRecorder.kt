/*
 * Copyright (c) 2026 Auxio Project
 * LegacyCrewCheckpointDispositionRecorder.kt is part of Auxio.
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
import org.json.JSONArray
import org.json.JSONObject

internal enum class LegacyCrewCheckpointDisposition {
    NONE,
    EXPIRE_CORRUPT,
    EXPIRE_INCOMPATIBLE,
}

internal data class LegacyCrewCheckpointDispositionResult(
    val disposition: LegacyCrewCheckpointDisposition,
    val legacyProtocolVersion: Int?,
    val requiresLegacyLeaseExpiry: Boolean,
    val warning: String?,
)

/**
 * Records the M11 decision without copying a v3 Crew payload or credential into R16 storage.
 *
 * R16's portable recording/QueueEntry protocol is intentionally a later authority cutover. The
 * existing checkpoint embeds legacy Track IDs, so treating the payload as compatible would create a
 * second queue identity authority. The untouched legacy database remains available for rollback.
 */
internal class LegacyCrewCheckpointDispositionRecorder(private val database: ShippyR16Database) {
    suspend fun record(
        migrationId: String,
        row: LegacyCrewCheckpointRow?,
    ): LegacyCrewCheckpointDispositionResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        val result = row.toDisposition()
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put(
                                "crewCheckpoint",
                                JSONObject()
                                    .put("present", row != null)
                                    .put("disposition", result.disposition.name)
                                    .put("legacyProtocolVersion", result.legacyProtocolVersion)
                                    .put(
                                        "requiresLegacyLeaseExpiry",
                                        result.requiresLegacyLeaseExpiry,
                                    ),
                            )
                            .put(
                                "checkpoint",
                                JSONObject().put("phase", LegacyImportPhase.CREW.code),
                            )
                            .toString(),
                    warningsJson =
                        result.warning?.let { appendCrewWarning(audit.warningsJson, it) }
                            ?: audit.warningsJson,
                    status = "IMPORTING",
                )
        }
        return result
    }
}

private fun LegacyCrewCheckpointRow?.toDisposition(): LegacyCrewCheckpointDispositionResult {
    if (this == null) {
        return LegacyCrewCheckpointDispositionResult(
            disposition = LegacyCrewCheckpointDisposition.NONE,
            legacyProtocolVersion = null,
            requiresLegacyLeaseExpiry = false,
            warning = null,
        )
    }
    val corrupt =
        slot != "active" ||
            sessionId.isBlank() ||
            protocolVersion <= 0 ||
            coordinatorTerm <= 0 ||
            eventSequence < 0 ||
            payloadLengthBytes <= 0 ||
            !payloadChecksumValid ||
            updatedAtEpochMs < 0
    return if (corrupt) {
        LegacyCrewCheckpointDispositionResult(
            disposition = LegacyCrewCheckpointDisposition.EXPIRE_CORRUPT,
            legacyProtocolVersion = protocolVersion,
            requiresLegacyLeaseExpiry = true,
            warning =
                "M11: corrupt legacy Crew checkpoint will expire locally; its payload was not copied",
        )
    } else {
        LegacyCrewCheckpointDispositionResult(
            disposition = LegacyCrewCheckpointDisposition.EXPIRE_INCOMPATIBLE,
            legacyProtocolVersion = protocolVersion,
            requiresLegacyLeaseExpiry = true,
            warning =
                "M11: legacy Crew protocol $protocolVersion uses pre-R16 recording identity; " +
                    "the active session will expire locally and must be rejoined",
        )
    }
}

private fun appendCrewWarning(existingJson: String, warning: String): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    if (
        result.length() < MAX_CREW_WARNINGS &&
            (0 until result.length()).none { result.optString(it) == warning }
    ) {
        result.put(warning)
    }
    return result.toString()
}

private const val MAX_CREW_WARNINGS = 1_000
