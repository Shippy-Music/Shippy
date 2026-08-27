/*
 * Copyright (c) 2026 Auxio Project
 * LegacyV10Preflight.kt is part of Auxio.
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
package app.shippy.data.migration.preflight

import android.database.sqlite.SQLiteDatabase
import app.shippy.data.migration.LegacyDatabaseReader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/** The immutable evidence produced before an R15.3 database import can begin. */
internal data class LegacyV10PreflightEvidence(
    val databaseSchemaVersion: Int,
    val legacyFileName: String,
    val snapshotFile: File,
    val manifestFile: File,
    val byteCount: Long,
    val sha256: String,
    val rowCounts: Map<String, Long>,
    val presentTables: Set<String>,
)

/** Small seams keep filesystem failure paths deterministic and cheap to test. */
internal fun interface LegacyPreflightSnapshotCopier {
    fun copy(source: File, destination: File)
}

internal fun interface LegacyPreflightFreeSpaceProbe {
    fun availableBytes(directory: File): Long
}

/**
 * Creates a recovery snapshot of the legacy Shippy v10 database before migration.
 *
 * This is intentionally separate from the normalized R16 [ShippyBackupV1] format. It copies only
 * the closed legacy SQLite file and writes a deterministic evidence manifest beside it; it does not
 * delete, mutate, or activate any database.
 */
internal class LegacyV10Preflight(
    private val freeSpaceProbe: LegacyPreflightFreeSpaceProbe =
        LegacyPreflightFreeSpaceProbe { directory ->
            directory.usableSpace
        },
    private val snapshotCopier: LegacyPreflightSnapshotCopier =
        LegacyPreflightSnapshotCopier(::streamCopy),
) {
    fun capture(
        legacyDatabase: File,
        destinationDirectory: File,
        snapshotFileName: String = DEFAULT_SNAPSHOT_FILE_NAME,
    ): LegacyV10PreflightEvidence {
        require(legacyDatabase.isFile) { "Legacy database file is missing" }
        require(legacyDatabase.length() > 0L) { "Legacy database file is empty" }
        require(destinationDirectory.isDirectory) { "Preflight destination directory is missing" }
        require(SAFE_FILE_NAME.matches(snapshotFileName)) {
            "Preflight snapshot file name is unsafe"
        }

        requireStableLegacySidecars(legacyDatabase)
        val snapshotFile = File(destinationDirectory, snapshotFileName)
        val manifestFile = File(destinationDirectory, "$snapshotFileName.manifest.json")
        require(!snapshotFile.exists()) { "Preflight snapshot already exists" }
        require(!manifestFile.exists()) { "Preflight manifest already exists" }

        val sourceLength = legacyDatabase.length()
        val sourceModifiedAt = legacyDatabase.lastModified()
        val sourceSha256Before = sha256(legacyDatabase)
        val schema =
            LegacyDatabaseReader.openReadOnly(legacyDatabase).use { reader ->
                val snapshot = reader.schemaSnapshot()
                require(snapshot.compatible) {
                    "Legacy database schema is incompatible: version=${snapshot.version}, " +
                        "missing=${snapshot.missingRequiredTables.sorted()}"
                }
                verifyIntegrity(legacyDatabase)
                snapshot
            }

        requireStableSource(legacyDatabase, sourceLength, sourceModifiedAt)
        val requiredBytes = sourceLength + MANIFEST_SPACE_RESERVE_BYTES
        check(requiredBytes >= sourceLength) { "Legacy database size overflowed" }
        check(freeSpaceProbe.availableBytes(destinationDirectory) >= requiredBytes) {
            "Insufficient free space for the legacy recovery snapshot"
        }

        var snapshotTemp: File? = null
        var manifestTemp: File? = null
        var snapshotPublished = false
        var manifestPublished = false
        try {
            snapshotTemp =
                File.createTempFile(".${snapshotFile.name}.", ".tmp", destinationDirectory)
            snapshotCopier.copy(legacyDatabase, snapshotTemp)

            val copySha256 = sha256(snapshotTemp)
            require(copySha256 == sourceSha256Before) {
                "Legacy recovery snapshot checksum does not match its source"
            }
            requireStableSource(legacyDatabase, sourceLength, sourceModifiedAt)
            require(snapshotTemp.length() == sourceLength) {
                "Legacy recovery snapshot length does not match its source"
            }

            requireStableLegacySidecars(legacyDatabase)
            moveAtomically(snapshotTemp, snapshotFile)
            snapshotPublished = true

            val evidence =
                LegacyV10PreflightEvidence(
                    databaseSchemaVersion = schema.version,
                    legacyFileName = legacyDatabase.name,
                    snapshotFile = snapshotFile,
                    manifestFile = manifestFile,
                    byteCount = sourceLength,
                    sha256 = copySha256,
                    rowCounts = schema.rowCounts.toSortedMap(),
                    presentTables = schema.presentTables.toSortedSet(),
                )
            manifestTemp =
                File.createTempFile(".${manifestFile.name}.", ".tmp", destinationDirectory)
            writeManifest(manifestTemp, evidence)
            moveAtomically(manifestTemp, manifestFile)
            manifestPublished = true
            return evidence
        } catch (failure: Throwable) {
            snapshotTemp?.delete()
            manifestTemp?.delete()
            if (snapshotPublished) snapshotFile.delete()
            if (manifestPublished) manifestFile.delete()
            throw failure
        }
    }

    private fun requireStableLegacySidecars(legacyDatabase: File) {
        for (suffix in arrayOf("-wal", "-journal")) {
            val sidecar = File(legacyDatabase.path + suffix)
            check(!sidecar.exists() || (sidecar.isFile && sidecar.length() == 0L)) {
                "Legacy database has a non-empty unsafe sidecar: ${sidecar.name}"
            }
        }
    }

    private fun requireStableSource(source: File, expectedLength: Long, expectedModifiedAt: Long) {
        check(source.length() == expectedLength) {
            "Legacy database length changed during preflight"
        }
        check(source.lastModified() == expectedModifiedAt) {
            "Legacy database timestamp changed during preflight"
        }
    }

    private fun verifyIntegrity(file: File) {
        SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            .use { database ->
                database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    var resultCount = 0
                    while (cursor.moveToNext()) {
                        resultCount++
                        check(cursor.getString(0) == "ok") {
                            "Legacy SQLite integrity check failed: ${cursor.getString(0)}"
                        }
                    }
                    check(resultCount == 1) { "Legacy SQLite integrity check returned no result" }
                }
            }
    }

    private fun writeManifest(file: File, evidence: LegacyV10PreflightEvidence) {
        val tableCounts =
            evidence.rowCounts.entries.joinToString(",") { (table, count) ->
                "${JSONObject.quote(table)}:$count"
            }
        val presentTables = evidence.presentTables.joinToString(",") { JSONObject.quote(it) }
        val manifest =
            "{" +
                "\"format\":\"$FORMAT_NAME\"," +
                "\"formatVersion\":$FORMAT_VERSION," +
                "\"databaseSchemaVersion\":${evidence.databaseSchemaVersion}," +
                "\"legacyFileName\":${JSONObject.quote(evidence.legacyFileName)}," +
                "\"snapshotFileName\":${JSONObject.quote(evidence.snapshotFile.name)}," +
                "\"byteCount\":${evidence.byteCount}," +
                "\"sha256\":${JSONObject.quote(evidence.sha256)}," +
                "\"integrityCheck\":\"ok\"," +
                "\"presentTables\":[$presentTables]," +
                "\"rowCounts\":{$tableCounts}" +
                "}\n"
        FileOutputStream(file).use { output ->
            output.write(manifest.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    companion object {
        internal const val DEFAULT_SNAPSHOT_FILE_NAME = "shippy-r15.3-preflight-v10.db"
        private const val FORMAT_NAME = "ShippyLegacyPreflightV1"
        private const val FORMAT_VERSION = 1
        internal const val BUFFER_BYTES = 1 * 1_024 * 1_024
        private const val MANIFEST_SPACE_RESERVE_BYTES = 64 * 1_024L
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

private fun streamCopy(source: File, destination: File) {
    BufferedInputStream(FileInputStream(source), LegacyV10Preflight.BUFFER_BYTES).use { input ->
        FileOutputStream(destination).use { output ->
            input.copyTo(output, LegacyV10Preflight.BUFFER_BYTES)
            output.flush()
            output.fd.sync()
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(FileInputStream(file), LegacyV10Preflight.BUFFER_BYTES).use { input ->
        val buffer = ByteArray(LegacyV10Preflight.BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun moveAtomically(source: File, destination: File) {
    check(source.parentFile == destination.parentFile) {
        "Atomic preflight publication requires a shared destination directory"
    }
    check(!destination.exists()) { "Refusing to replace existing preflight output" }
    check(source.renameTo(destination)) { "Unable to atomically publish ${destination.name}" }
}
