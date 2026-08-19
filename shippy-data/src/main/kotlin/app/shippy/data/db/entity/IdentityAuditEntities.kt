/*
 * Copyright (c) 2026 Auxio Project
 * IdentityAuditEntities.kt is part of Auxio.
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
package app.shippy.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "identity_decision",
    indices =
        [
            Index(
                value = ["subject_type", "subject_id", "created_at_epoch_ms"],
                name = "index_identity_decision_subject",
            )
        ],
)
data class IdentityDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "decision_id") val decisionId: String,
    @ColumnInfo(name = "subject_type") val subjectType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "target_recording_id") val targetRecordingId: String?,
    @ColumnInfo(name = "decision_kind") val decisionKind: String,
    val confidence: Double?,
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
    @ColumnInfo(name = "user_confirmed") val userConfirmed: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "identity_rejection",
    primaryKeys = ["subject_type", "subject_id", "rejected_recording_id"],
)
data class IdentityRejectionEntity(
    @ColumnInfo(name = "subject_type") val subjectType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "rejected_recording_id") val rejectedRecordingId: String,
    val reason: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "entity_redirect",
    indices = [Index(value = ["canonical_recording_id"], name = "index_entity_redirect_target")],
)
data class EntityRedirectEntity(
    @PrimaryKey @ColumnInfo(name = "old_recording_id") val oldRecordingId: String,
    @ColumnInfo(name = "canonical_recording_id") val canonicalRecordingId: String,
    @ColumnInfo(name = "merge_audit_id") val mergeAuditId: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(tableName = "merge_audit")
data class MergeAuditEntity(
    @PrimaryKey @ColumnInfo(name = "merge_audit_id") val mergeAuditId: String,
    @ColumnInfo(name = "survivor_recording_id") val survivorRecordingId: String,
    @ColumnInfo(name = "merged_recording_id") val mergedRecordingId: String,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "user_confirmed") val userConfirmed: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "reversed_at_epoch_ms") val reversedAtEpochMs: Long?,
)

@Entity(tableName = "migration_audit")
data class MigrationAuditEntity(
    @PrimaryKey @ColumnInfo(name = "migration_id") val migrationId: String,
    @ColumnInfo(name = "source_version") val sourceVersion: Int,
    @ColumnInfo(name = "target_version") val targetVersion: Int,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long?,
    @ColumnInfo(name = "source_counts_json") val sourceCountsJson: String,
    @ColumnInfo(name = "target_counts_json") val targetCountsJson: String?,
    @ColumnInfo(name = "warnings_json") val warningsJson: String,
    val checksum: String?,
    val status: String,
)
