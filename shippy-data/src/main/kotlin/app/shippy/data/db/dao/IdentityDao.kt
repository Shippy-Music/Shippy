/*
 * Copyright (c) 2026 Auxio Project
 * IdentityDao.kt is part of Auxio.
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
package app.shippy.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.shippy.data.db.entity.EntityRedirectEntity
import app.shippy.data.db.entity.IdentityDecisionEntity
import app.shippy.data.db.entity.IdentityRejectionEntity
import app.shippy.data.db.entity.MergeAuditEntity

@Dao
internal abstract class IdentityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertDecision(entity: IdentityDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDecisionIfAbsent(entity: IdentityDecisionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRejection(entity: IdentityRejectionEntity)

    @Query(
        """
        SELECT * FROM identity_decision
        WHERE subject_type = :subjectType AND subject_id = :subjectId
        ORDER BY created_at_epoch_ms DESC, decision_id DESC
        """
    )
    abstract suspend fun decisionsFor(
        subjectType: String,
        subjectId: String,
    ): List<IdentityDecisionEntity>

    @Query(
        """
        SELECT * FROM identity_rejection
        WHERE subject_type = :subjectType AND subject_id = :subjectId
        ORDER BY created_at_epoch_ms DESC, rejected_recording_id
        """
    )
    abstract suspend fun rejectionsFor(
        subjectType: String,
        subjectId: String,
    ): List<IdentityRejectionEntity>

    @Query("SELECT * FROM entity_redirect WHERE old_recording_id = :recordingId")
    protected abstract suspend fun directRedirect(recordingId: String): EntityRedirectEntity?

    @Query("SELECT * FROM entity_redirect WHERE old_recording_id = :recordingId")
    abstract suspend fun redirectFrom(recordingId: String): EntityRedirectEntity?

    @Query("SELECT * FROM merge_audit WHERE merge_audit_id = :mergeAuditId")
    abstract suspend fun mergeAudit(mergeAuditId: String): MergeAuditEntity?

    @Query(
        """
        SELECT * FROM merge_audit
        WHERE survivor_recording_id = :recordingId AND reversed_at_epoch_ms IS NULL
        ORDER BY created_at_epoch_ms DESC
        """
    )
    abstract suspend fun activeMergeAuditsForSurvivor(recordingId: String): List<MergeAuditEntity>

    @Query(
        """
        SELECT * FROM merge_audit
        WHERE (survivor_recording_id = :recordingId OR merged_recording_id = :recordingId)
          AND reversed_at_epoch_ms IS NULL
        ORDER BY created_at_epoch_ms DESC
        """
    )
    abstract suspend fun activeMergeAuditsForRecording(recordingId: String): List<MergeAuditEntity>

    @Query(
        """
        DELETE FROM identity_decision
        WHERE subject_type = :subjectType AND subject_id = :subjectId
        """
    )
    abstract suspend fun deleteDecisionsForSubject(subjectType: String, subjectId: String): Int

    @Query("DELETE FROM identity_decision WHERE decision_id = :decisionId")
    abstract suspend fun deleteDecision(decisionId: String): Int

    @Query("SELECT COUNT(*) FROM recording WHERE recording_id = :recordingId")
    protected abstract suspend fun recordingCount(recordingId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRedirect(entity: EntityRedirectEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMergeAudit(entity: MergeAuditEntity)

    @Query(
        """
        DELETE FROM entity_redirect
        WHERE old_recording_id = :oldRecordingId AND merge_audit_id = :mergeAuditId
        """
    )
    protected abstract suspend fun deleteRedirect(oldRecordingId: String, mergeAuditId: String): Int

    @Query(
        """
        UPDATE merge_audit SET reversed_at_epoch_ms = :reversedAtEpochMs
        WHERE merge_audit_id = :mergeAuditId AND reversed_at_epoch_ms IS NULL
        """
    )
    protected abstract suspend fun markReversed(mergeAuditId: String, reversedAtEpochMs: Long): Int

    @Transaction
    open suspend fun resolveRecordingId(recordingId: String): String {
        require(recordingId.isNotBlank()) { "Recording ID must not be blank" }
        val visited = linkedSetOf<String>()
        var current = recordingId
        repeat(MAX_REDIRECT_HOPS) {
            check(visited.add(current)) { "Recording redirect cycle detected" }
            val redirect = directRedirect(current) ?: return current
            current = redirect.canonicalRecordingId
        }
        error("Recording redirect chain exceeds $MAX_REDIRECT_HOPS hops")
    }

    @Transaction
    open suspend fun recordRedirect(
        redirect: EntityRedirectEntity,
        audit: MergeAuditEntity,
    ): Boolean {
        require(redirect.oldRecordingId != redirect.canonicalRecordingId) {
            "A recording cannot redirect to itself"
        }
        require(audit.reversedAtEpochMs == null) { "A new merge audit cannot already be reversed" }
        require(audit.mergeAuditId == redirect.mergeAuditId) { "Redirect and audit IDs must match" }
        require(audit.mergedRecordingId == redirect.oldRecordingId) {
            "Audit merged recording must match the redirect source"
        }
        require(audit.survivorRecordingId == redirect.canonicalRecordingId) {
            "Audit survivor must match the redirect target"
        }

        directRedirect(redirect.oldRecordingId)?.let { existing ->
            check(existing == redirect) { "Recording already has a conflicting redirect" }
            return false
        }
        check(recordingCount(redirect.oldRecordingId) == 1) {
            "Redirect source recording is missing"
        }
        check(recordingCount(redirect.canonicalRecordingId) == 1) {
            "Redirect target recording is missing"
        }
        check(resolveRecordingId(redirect.canonicalRecordingId) == redirect.canonicalRecordingId) {
            "Redirect target must already be canonical"
        }

        insertMergeAudit(audit)
        insertRedirect(redirect)
        return true
    }

    @Transaction
    open suspend fun reverseRedirect(mergeAuditId: String, reversedAtEpochMs: Long): Boolean {
        val audit = mergeAudit(mergeAuditId) ?: error("Merge audit is missing")
        if (audit.reversedAtEpochMs != null) return false
        val redirect = directRedirect(audit.mergedRecordingId) ?: error("Merge redirect is missing")
        check(
            redirect.mergeAuditId == mergeAuditId &&
                redirect.canonicalRecordingId == audit.survivorRecordingId
        ) {
            "Merge redirect no longer matches its audit"
        }
        check(deleteRedirect(audit.mergedRecordingId, mergeAuditId) == 1) {
            "Merge redirect disappeared during reversal"
        }
        check(markReversed(mergeAuditId, reversedAtEpochMs) == 1) {
            "Merge audit disappeared during reversal"
        }
        return true
    }

    private companion object {
        const val MAX_REDIRECT_HOPS = 64
    }
}
