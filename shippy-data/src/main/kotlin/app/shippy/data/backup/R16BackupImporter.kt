/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupImporter.kt is part of Auxio.
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
package app.shippy.data.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import app.shippy.data.db.ShippyR16Database
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Restores a verified ShippyBackupV1 into a newly-created, empty R16 database. */
internal class R16BackupImporter(private val database: ShippyR16Database) {
    fun restore(input: InputStream): R16BackupRestoreReport =
        ShippyBackupV1.stage(input).use { staged ->
            require(staged.manifest.databaseSchemaVersion == ShippyR16Database.SCHEMA_VERSION) {
                "Backup database schema version is unsupported"
            }
            val portableSettings =
                staged.sectionFiles[ShippyBackupSection.PORTABLE_SETTINGS]?.let {
                    R16BackupCoverageCodec.decodePortableSettings(readCoveragePayload(it))
                }
            val sanitizedLastFmConfig =
                staged.sectionFiles[ShippyBackupSection.SANITIZED_LASTFM_CONFIG]?.let {
                    R16BackupCoverageCodec.decodeLastFmConfig(readCoveragePayload(it))
                }
            val connection = database.openHelper.writableDatabase
            val schemas = loadSchemas(connection)
            connection.beginTransaction()
            try {
                validateTargetIsEmpty(connection)
                val rowCounts = linkedMapOf<String, Int>()
                for (section in R16BackupContract.tablesBySection.keys) {
                    if (
                        section == ShippyBackupSection.HISTORY && !staged.manifest.includesHistory
                    ) {
                        continue
                    }
                    val file = staged.sectionFiles[section] ?: continue
                    restoreSection(file, section, connection, schemas, rowCounts)
                }
                database.libraryMembershipDao().rebuild()
                rebuildAndVerifyFts(connection)
                verifyForeignKeys(connection)
                connection.setTransactionSuccessful()
                R16BackupRestoreReport(
                    databaseSchemaVersion = staged.manifest.databaseSchemaVersion,
                    createdAtEpochMs = staged.manifest.createdAtEpochMs,
                    includesHistory = staged.manifest.includesHistory,
                    rowCounts = rowCounts,
                    portableSettings = portableSettings,
                    sanitizedLastFmConfig = sanitizedLastFmConfig,
                )
            } finally {
                connection.endTransaction()
            }
        }

    private fun readCoveragePayload(file: File): ByteArray {
        require(file.length() <= MAX_COVERAGE_BYTES) { "Backup coverage payload is too large" }
        return file.readBytes()
    }

    private fun validateTargetIsEmpty(connection: SupportSQLiteDatabase) {
        for (table in R16BackupContract.allR16Tables) {
            val count =
                connection.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
                    check(cursor.moveToFirst()) { "Unable to inspect target table $table" }
                    cursor.getLong(0)
                }
            require(count == 0L) { "R16 restore target is not empty: $table has $count rows" }
        }
    }

    private fun loadSchemas(connection: SupportSQLiteDatabase): Map<String, TableSchema> =
        R16BackupContract.sectionForTable.keys.associateWith { table ->
            val columns =
                connection.query("PRAGMA table_info(\"$table\")").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    val typeIndex = cursor.getColumnIndex("type")
                    val notNullIndex = cursor.getColumnIndex("notnull")
                    require(nameIndex >= 0 && typeIndex >= 0 && notNullIndex >= 0) {
                        "R16 target schema cannot describe $table"
                    }
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                TableColumn(
                                    name = cursor.getString(nameIndex),
                                    affinity = cursor.getString(typeIndex).uppercase(),
                                    notNull = cursor.getInt(notNullIndex) != 0,
                                )
                            )
                        }
                    }
                }
            require(columns.isNotEmpty()) { "R16 target schema is missing table $table" }
            TableSchema(columns)
        }

    private fun restoreSection(
        file: File,
        section: ShippyBackupSection,
        connection: SupportSQLiteDatabase,
        schemas: Map<String, TableSchema>,
        rowCounts: MutableMap<String, Int>,
    ) {
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use {
            reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(line.isNotBlank()) { "Backup contains a blank data row" }
                require(
                    line.toByteArray(StandardCharsets.UTF_8).size <=
                        R16BackupContract.MAX_NDJSON_ROW_BYTES
                ) {
                    "Backup data row exceeds the size limit"
                }
                restoreRow(JSONObject(line), section, connection, schemas, rowCounts)
            }
        }
    }

    private fun restoreRow(
        row: JSONObject,
        section: ShippyBackupSection,
        connection: SupportSQLiteDatabase,
        schemas: Map<String, TableSchema>,
        rowCounts: MutableMap<String, Int>,
    ) {
        require(row.keys().asSequence().toSet() == ROW_KEYS) {
            "Backup row contains unknown fields"
        }
        val table = row.getString("table")
        require(R16BackupContract.sectionForTable[table] == section) {
            "Backup row table does not belong to its section: $table"
        }
        val schema = schemas[table] ?: error("Backup contains an unknown table: $table")
        val columns = row.getJSONArray("columns").strings()
        val values = row.getJSONArray("values")
        require(columns.size == values.length()) { "Backup row column/value count differs" }
        require(columns.toSet().size == columns.size) { "Backup row contains duplicate columns" }
        require(columns.toSet() == schema.columns.map(TableColumn::name).toSet()) {
            "Backup row columns do not match the R16 schema for $table"
        }

        val arguments = ArrayList<Any?>(columns.size)
        columns.forEachIndexed { index, column ->
            val columnSchema = schema.columns.single { it.name == column }
            val rawValue = values.get(index)
            if (
                table in R16BackupContract.redactedColumns &&
                    column in R16BackupContract.redactedColumns.getValue(table)
            ) {
                require(rawValue === JSONObject.NULL) {
                    "Backup contains a non-null redacted field: $table.$column"
                }
            }
            val value = bindValue(rawValue, table, column, columnSchema)
            require(!columnSchema.notNull || value != null) {
                "Backup contains null for required field: $table.$column"
            }
            arguments += value
        }

        val quotedTable = table.quoteIdentifier()
        val quotedColumns = columns.joinToString(", ") { it.quoteIdentifier() }
        val placeholders = columns.joinToString(", ") { "?" }
        connection.execSQL(
            "INSERT INTO $quotedTable ($quotedColumns) VALUES ($placeholders)",
            arguments.toTypedArray(),
        )
        rowCounts[table] = (rowCounts[table] ?: 0) + 1
    }

    private fun bindValue(rawValue: Any, table: String, column: String, schema: TableColumn): Any? {
        if (rawValue === JSONObject.NULL) return null
        if (rawValue is JSONObject) {
            require(rawValue.keys().asSequence().toSet() == setOf(BLOB_VALUE_KEY)) {
                "Backup contains an unknown structured value: $table.$column"
            }
            require(schema.affinity.contains("BLOB")) {
                "Backup contains a blob for a non-blob field: $table.$column"
            }
            return try {
                Base64.getDecoder().decode(rawValue.getString(BLOB_VALUE_KEY))
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Backup contains invalid blob data: $table.$column",
                    failure,
                )
            }
        }
        return when {
            schema.affinity.contains("BLOB") ->
                error("Backup contains a non-blob value for a blob field: $table.$column")
            schema.affinity.contains("INT") -> {
                require(rawValue is Number) {
                    "Backup contains a non-integer value: $table.$column"
                }
                rawValue.toString().toLongOrNull()
                    ?: error("Backup contains a non-integer value: $table.$column")
            }
            schema.affinity.contains("REAL") ||
                schema.affinity.contains("FLOA") ||
                schema.affinity.contains("DOUB") -> {
                require(rawValue is Number) { "Backup contains a non-number value: $table.$column" }
                rawValue.toDouble().also {
                    require(it.isFinite()) { "Backup contains a non-finite number: $table.$column" }
                }
            }
            schema.affinity.contains("CHAR") ||
                schema.affinity.contains("CLOB") ||
                schema.affinity.contains("TEXT") -> {
                require(rawValue is String) { "Backup contains a non-text value: $table.$column" }
                rawValue
            }
            else -> error("Backup target column has unsupported affinity: $table.$column")
        }
    }

    private fun rebuildAndVerifyFts(connection: SupportSQLiteDatabase) {
        connection.execSQL("DELETE FROM recording_fts")
        connection.execSQL(
            """
            INSERT INTO recording_fts
                (recording_id, title, artist_names, release_title, aliases, source_titles,
                 user_override_text)
            SELECT
                r.recording_id,
                r.canonical_title,
                COALESCE((
                    SELECT GROUP_CONCAT(credited_name, ' ')
                    FROM recording_artist_credit credit
                    WHERE credit.recording_id = r.recording_id
                ), ''),
                COALESCE(rel.canonical_title, ''),
                COALESCE((
                    SELECT GROUP_CONCAT(identifier.value, ' ')
                    FROM external_identifier identifier
                    WHERE identifier.owner_type = 'RECORDING'
                      AND identifier.owner_id = r.recording_id
                ), ''),
                COALESCE((
                    SELECT GROUP_CONCAT(observation.title, ' ')
                    FROM source_reference source
                    JOIN metadata_observation observation
                      ON observation.observation_id = source.raw_metadata_observation_id
                    WHERE source.recording_id = r.recording_id
                ), ''),
                COALESCE((
                    SELECT GROUP_CONCAT(metadata.value_json, ' ')
                    FROM user_metadata_override metadata
                    WHERE metadata.recording_id = r.recording_id
                ), '')
            FROM recording r
            LEFT JOIN release rel ON rel.release_id = r.preferred_release_id
            """
                .trimIndent()
        )
        val recordingCount = connection.scalarCount("recording")
        val ftsCount =
            connection.query("SELECT COUNT(*) FROM recording_fts").use { cursor ->
                check(cursor.moveToFirst()) { "Unable to verify recording search index" }
                cursor.getLong(0)
            }
        require(recordingCount == ftsCount) {
            "Restored recording search index is incomplete: $ftsCount/$recordingCount"
        }
        connection.execSQL("DELETE FROM playlist_fts")
        connection.execSQL(
            """
            INSERT INTO playlist_fts (playlist_id, name)
            SELECT playlist_id, name FROM playlist
            """
                .trimIndent()
        )
        val playlistCount = connection.scalarCount("playlist")
        val playlistFtsCount =
            connection.query("SELECT COUNT(*) FROM playlist_fts").use { cursor ->
                check(cursor.moveToFirst()) { "Unable to verify playlist search index" }
                cursor.getLong(0)
            }
        require(playlistCount == playlistFtsCount) {
            "Restored playlist search index is incomplete: $playlistFtsCount/$playlistCount"
        }
    }

    private fun verifyForeignKeys(connection: SupportSQLiteDatabase) {
        connection.query("PRAGMA foreign_key_check").use { cursor ->
            require(!cursor.moveToFirst()) {
                "Restored R16 database contains an orphan foreign key"
            }
        }
    }

    private fun JSONArray.strings(): List<String> =
        List(length()) { index ->
            get(index)
                .also { require(it is String) { "Backup column names must be strings" } }
                .toString()
        }

    private fun String.quoteIdentifier(): String = "\"${replace("\"", "\"\"")}\""

    private fun SupportSQLiteDatabase.scalarCount(table: String): Long =
        query("SELECT COUNT(*) FROM ${table.quoteIdentifier()}").use { cursor ->
            check(cursor.moveToFirst()) { "Unable to count $table" }
            cursor.getLong(0)
        }

    private data class TableSchema(val columns: List<TableColumn>)

    private data class TableColumn(val name: String, val affinity: String, val notNull: Boolean)

    companion object {
        private const val BLOB_VALUE_KEY = "blobBase64"
        private const val MAX_COVERAGE_BYTES = 256 * 1024L
        private val ROW_KEYS = setOf("table", "columns", "values")
    }
}

internal data class R16BackupRestoreReport(
    val databaseSchemaVersion: Int,
    val createdAtEpochMs: Long,
    val includesHistory: Boolean,
    val rowCounts: Map<String, Int>,
    val portableSettings: R16PortableSettingsSnapshot? = null,
    val sanitizedLastFmConfig: R16SanitizedLastFmConfig? = null,
)
