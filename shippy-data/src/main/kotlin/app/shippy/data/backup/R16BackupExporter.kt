/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupExporter.kt is part of Auxio.
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

import android.database.Cursor
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import app.shippy.data.db.ShippyR16Database
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Creates the user-data portion of a [ShippyBackupV1] from one consistent R16 database snapshot.
 *
 * The exporter deliberately reads the normalized tables as rows instead of serializing Room
 * entities. This keeps the archive format independent of Kotlin implementation details and makes
 * every durable column available to a later restore implementation. Rows are emitted directly to
 * the ZIP entry in a single database transaction, so large libraries do not require a second copy
 * of the complete backup in memory. Derived FTS data, migration audit history, cache payloads, and
 * secrets are not user backup data and are therefore omitted. The data-only exporter deliberately
 * reports app-settings and sanitized Last.fm-config coverage as false until app adapters provide
 * those sections.
 */
internal class R16BackupExporter(
    private val database: ShippyR16Database,
    private val portableSettingsProvider: R16PortableSettingsProvider? = null,
    private val sanitizedLastFmConfigProvider: R16SanitizedLastFmConfigProvider? = null,
) {
    fun export(
        output: OutputStream,
        createdAtEpochMs: Long,
        includeHistory: Boolean = false,
    ): R16BackupExportReport {
        require(createdAtEpochMs >= 0) { "Backup timestamp cannot be negative" }

        val coverage = loadCoverage()

        val connection = database.openHelper.readableDatabase
        connection.beginTransaction()
        return try {
            val sectionManifests = linkedMapOf<ShippyBackupSection, R16BackupSectionManifest>()
            val rowCounts = linkedMapOf<String, Int>()
            var totalBytes = 0L
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                val sections =
                    buildList {
                            addAll(
                                R16BackupContract.tablesBySection.keys.filter {
                                    it != ShippyBackupSection.HISTORY || includeHistory
                                }
                            )
                            if (coverage != null) {
                                add(ShippyBackupSection.PORTABLE_SETTINGS)
                                add(ShippyBackupSection.SANITIZED_LASTFM_CONFIG)
                            }
                        }
                        .sortedBy(ShippyBackupSection::entryName)
                for (section in sections) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var sectionBytes = 0L
                    zip.putNextEntry(ZipEntry(section.entryName).apply { time = 0 })
                    val payload = coverage?.payloadFor(section)
                    if (payload != null) {
                        zip.write(payload)
                        digest.update(payload)
                        sectionBytes = payload.size.toLong()
                    } else {
                        for (table in R16BackupContract.tablesBySection.getValue(section)) {
                            val result =
                                connection.writeRows(
                                    table = table,
                                    output = zip,
                                    digest = digest,
                                    currentSectionBytes = sectionBytes,
                                )
                            sectionBytes += result.byteCount
                            rowCounts[table] = result.rowCount
                        }
                    }
                    zip.closeEntry()
                    require(sectionBytes <= MAX_SECTION_BYTES) {
                        "Backup section exceeds the size limit"
                    }
                    totalBytes += sectionBytes
                    require(totalBytes <= MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                        "Backup exceeds the uncompressed size limit"
                    }
                    sectionManifests[section] =
                        R16BackupSectionManifest(
                            byteCount = sectionBytes,
                            sha256 = digest.digest().toHex(),
                        )
                }
                val manifest =
                    manifestJson(
                        databaseSchemaVersion = ShippyR16Database.SCHEMA_VERSION,
                        createdAtEpochMs = createdAtEpochMs,
                        includesHistory = includeHistory,
                        portableAppSettingsIncluded = coverage != null,
                        sanitizedLastFmConfigIncluded = coverage != null,
                        sections = sectionManifests,
                    )
                require(manifest.size <= MAX_MANIFEST_BYTES) {
                    "Backup manifest exceeds the size limit"
                }
                require(totalBytes + manifest.size <= MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                    "Backup exceeds the uncompressed size limit"
                }
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = 0 })
                zip.write(manifest)
                zip.closeEntry()
            }
            connection.setTransactionSuccessful()
            R16BackupExportReport(
                databaseSchemaVersion = ShippyR16Database.SCHEMA_VERSION,
                createdAtEpochMs = createdAtEpochMs,
                includesHistory = includeHistory,
                rowCounts = rowCounts,
                sectionByteCounts = sectionManifests.mapValues { it.value.byteCount },
                sectionChecksums = sectionManifests.mapValues { it.value.sha256 },
                portableAppSettingsIncluded = coverage != null,
                sanitizedLastFmConfigIncluded = coverage != null,
            )
        } finally {
            connection.endTransaction()
        }
    }

    private fun loadCoverage(): CoveragePayloads? {
        if (portableSettingsProvider == null && sanitizedLastFmConfigProvider == null) return null
        require(portableSettingsProvider != null && sanitizedLastFmConfigProvider != null) {
            "Portable settings and sanitized Last.fm providers must be supplied together"
        }
        return CoveragePayloads(
            portableSettings =
                R16BackupCoverageCodec.encodePortableSettings(portableSettingsProvider.snapshot()),
            lastFmConfig =
                R16BackupCoverageCodec.encodeLastFmConfig(sanitizedLastFmConfigProvider.snapshot()),
        )
    }

    private fun SupportSQLiteDatabase.writeRows(
        table: String,
        output: OutputStream,
        digest: MessageDigest,
        currentSectionBytes: Long,
    ): R16BackupTableResult {
        val identifier = table.replace("\"", "\"\"")
        query("SELECT * FROM \"$identifier\" LIMIT 0").use { cursor ->
            val columns = cursor.columnNames.toList()
            val orderBy =
                columns.joinToString(", ") { column ->
                    "\"${column.replace("\"", "\"\"")}\" COLLATE BINARY"
                }
            query("SELECT * FROM \"$identifier\" ORDER BY $orderBy").use { orderedCursor ->
                var byteCount = 0L
                var rowCount = 0
                while (orderedCursor.moveToNext()) {
                    val values = JSONArray()
                    for (index in columns.indices) {
                        values.put(orderedCursor.jsonValue(table, columns[index], index))
                    }
                    val row =
                        JSONObject()
                            .put("table", table)
                            .put("columns", JSONArray().apply { columns.forEach { put(it) } })
                            .put("values", values)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    require(row.size + 1L <= R16BackupContract.MAX_NDJSON_ROW_BYTES) {
                        "Backup data row exceeds the size limit"
                    }
                    output.write(row)
                    output.write('\n'.code)
                    digest.update(row)
                    digest.update('\n'.code.toByte())
                    byteCount += row.size + 1L
                    require(currentSectionBytes + byteCount <= MAX_SECTION_BYTES) {
                        "Backup section exceeds the size limit"
                    }
                    rowCount++
                }
                return R16BackupTableResult(byteCount, rowCount)
            }
        }
    }

    private fun Cursor.jsonValue(table: String, column: String, index: Int): Any {
        if (
            table in R16BackupContract.redactedColumns &&
                column in R16BackupContract.redactedColumns.getValue(table)
        ) {
            return JSONObject.NULL
        }
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_STRING -> getString(index)
            Cursor.FIELD_TYPE_BLOB ->
                JSONObject()
                    .put("$BLOB_VALUE_KEY", Base64.encodeToString(getBlob(index), Base64.NO_WRAP))
            else -> error("Unsupported SQLite value type ${getType(index)}")
        }
    }

    private fun manifestJson(
        databaseSchemaVersion: Int,
        createdAtEpochMs: Long,
        includesHistory: Boolean,
        portableAppSettingsIncluded: Boolean,
        sanitizedLastFmConfigIncluded: Boolean,
        sections: Map<ShippyBackupSection, R16BackupSectionManifest>,
    ): ByteArray =
        JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", FORMAT_VERSION)
            .put("databaseSchemaVersion", databaseSchemaVersion)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("includesHistory", includesHistory)
            .put("portableAppSettingsIncluded", portableAppSettingsIncluded)
            .put("sanitizedLastFmConfigIncluded", sanitizedLastFmConfigIncluded)
            .put(
                "entries",
                JSONArray().apply {
                    sections.entries
                        .sortedBy { it.key.entryName }
                        .forEach { (section, entry) ->
                            put(
                                JSONObject()
                                    .put("name", section.entryName)
                                    .put("contentType", contentType(section))
                                    .put("byteCount", entry.byteCount)
                                    .put("sha256", entry.sha256)
                            )
                        }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

    private data class R16BackupTableResult(val byteCount: Long, val rowCount: Int)

    private data class R16BackupSectionManifest(val byteCount: Long, val sha256: String)

    private data class CoveragePayloads(
        val portableSettings: ByteArray,
        val lastFmConfig: ByteArray,
    ) {
        fun payloadFor(section: ShippyBackupSection): ByteArray? =
            when (section) {
                ShippyBackupSection.PORTABLE_SETTINGS -> portableSettings
                ShippyBackupSection.SANITIZED_LASTFM_CONFIG -> lastFmConfig
                else -> null
            }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun contentType(section: ShippyBackupSection): String =
        if (
            section == ShippyBackupSection.PORTABLE_SETTINGS ||
                section == ShippyBackupSection.SANITIZED_LASTFM_CONFIG
        ) {
            JSON_CONTENT_TYPE
        } else {
            NDJSON_CONTENT_TYPE
        }

    companion object {
        private const val FORMAT_NAME = "ShippyBackupV1"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val NDJSON_CONTENT_TYPE = "application/x-ndjson; charset=utf-8"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val MAX_MANIFEST_BYTES = 1 * 1_024 * 1_024
        private const val MAX_SECTION_BYTES = 64L * 1_024 * 1_024
        private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512L * 1_024 * 1_024
        private const val BLOB_VALUE_KEY = "blobBase64"
    }
}

internal data class R16BackupExportReport(
    val databaseSchemaVersion: Int,
    val createdAtEpochMs: Long,
    val includesHistory: Boolean,
    val rowCounts: Map<String, Int>,
    val sectionByteCounts: Map<ShippyBackupSection, Long>,
    val sectionChecksums: Map<ShippyBackupSection, String>,
    val portableAppSettingsIncluded: Boolean = false,
    val sanitizedLastFmConfigIncluded: Boolean = false,
)
