/*
 * Copyright (c) 2026 Auxio Project
 * MigrationAuditDao.kt is part of Auxio.
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
import app.shippy.data.db.entity.MigrationAuditEntity

@Dao
internal abstract class MigrationAuditDao {
    @Query("SELECT * FROM migration_audit WHERE migration_id = :migrationId")
    abstract suspend fun get(migrationId: String): MigrationAuditEntity?

    @Query(
        """
        SELECT * FROM migration_audit
        WHERE source_version = :sourceVersion
          AND target_version = :targetVersion
          AND completed_at_epoch_ms IS NULL
        ORDER BY started_at_epoch_ms DESC, migration_id DESC
        LIMIT 1
        """
    )
    abstract suspend fun latestIncomplete(
        sourceVersion: Int,
        targetVersion: Int,
    ): MigrationAuditEntity?

    @Query(
        """
        SELECT * FROM migration_audit
        ORDER BY started_at_epoch_ms DESC, migration_id DESC
        LIMIT 1
        """
    )
    abstract suspend fun latest(): MigrationAuditEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(entity: MigrationAuditEntity)

    @Query(
        """
        UPDATE migration_audit SET
            target_counts_json = :targetCountsJson,
            warnings_json = :warningsJson,
            status = :status
        WHERE migration_id = :migrationId AND completed_at_epoch_ms IS NULL
        """
    )
    protected abstract suspend fun updateProgressInternal(
        migrationId: String,
        targetCountsJson: String?,
        warningsJson: String,
        status: String,
    ): Int

    @Query(
        """
        UPDATE migration_audit SET
            completed_at_epoch_ms = :completedAtEpochMs,
            target_counts_json = :targetCountsJson,
            warnings_json = :warningsJson,
            checksum = :checksum,
            status = :status
        WHERE migration_id = :migrationId AND completed_at_epoch_ms IS NULL
        """
    )
    protected abstract suspend fun completeInternal(
        migrationId: String,
        completedAtEpochMs: Long,
        targetCountsJson: String,
        warningsJson: String,
        checksum: String,
        status: String,
    ): Int

    open suspend fun start(entity: MigrationAuditEntity) {
        require(entity.completedAtEpochMs == null) { "New migration audit cannot be completed" }
        require(entity.targetCountsJson == null) { "New migration audit cannot have target counts" }
        require(entity.checksum == null) { "New migration audit cannot have a checksum" }
        require(entity.status.isNotBlank()) { "Migration status must not be blank" }
        insert(entity)
    }

    open suspend fun updateProgress(
        migrationId: String,
        targetCountsJson: String?,
        warningsJson: String,
        status: String,
    ) {
        require(status.isNotBlank()) { "Migration status must not be blank" }
        check(updateProgressInternal(migrationId, targetCountsJson, warningsJson, status) == 1) {
            "Active migration audit is missing"
        }
    }

    open suspend fun complete(
        migrationId: String,
        completedAtEpochMs: Long,
        targetCountsJson: String,
        warningsJson: String,
        checksum: String,
        status: String,
    ) {
        require(checksum.isNotBlank()) { "Completed migration audit requires a checksum" }
        require(status.isNotBlank()) { "Migration status must not be blank" }
        check(
            completeInternal(
                migrationId,
                completedAtEpochMs,
                targetCountsJson,
                warningsJson,
                checksum,
                status,
            ) == 1
        ) {
            "Active migration audit is missing"
        }
    }
}
