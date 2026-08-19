/*
 * Copyright (c) 2026 Auxio Project
 * ShippyBackupV1.kt is part of Auxio.
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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal enum class ShippyBackupSection(val entryName: String, val required: Boolean) {
    RECORDINGS("data/recordings.ndjson", true),
    SOURCES("data/sources.ndjson", true),
    LIBRARY("data/library.ndjson", true),
    PLAYLISTS("data/playlists.ndjson", true),
    OVERRIDES("data/overrides.ndjson", true),
    IDENTITY_DECISIONS("data/identity-decisions.ndjson", true),
    DOWNLOADS("data/downloads.ndjson", true),
    SETTINGS("data/settings-sanitized.ndjson", true),
    LASTFM_CONFIG("data/lastfm-config-sanitized.ndjson", true),
    HISTORY("data/history.ndjson", false),
    ASSET_MANIFESTS("data/asset-manifests.ndjson", false),
}

internal data class ShippyBackupEntryManifest(
    val section: ShippyBackupSection,
    val byteCount: Long,
    val sha256: String,
)

internal data class ShippyBackupManifest(
    val databaseSchemaVersion: Int,
    val createdAtEpochMs: Long,
    val includesHistory: Boolean,
    val entries: List<ShippyBackupEntryManifest>,
)

internal data class ShippyBackupV1Archive(
    val manifest: ShippyBackupManifest,
    val sections: Map<ShippyBackupSection, ByteArray>,
)

internal object ShippyBackupV1 {
    fun write(
        output: OutputStream,
        databaseSchemaVersion: Int,
        createdAtEpochMs: Long,
        includesHistory: Boolean,
        sections: Map<ShippyBackupSection, ByteArray>,
    ) {
        require(databaseSchemaVersion > 0) { "Backup database schema version must be positive" }
        require(createdAtEpochMs >= 0) { "Backup timestamp cannot be negative" }
        validateSectionSet(sections.keys, includesHistory)
        validateSectionSizes(sections)
        val entryManifests =
            sections.entries
                .sortedBy { it.key.entryName }
                .map { (section, bytes) ->
                    ShippyBackupEntryManifest(section, bytes.size.toLong(), sha256(bytes))
                }
        val manifest =
            manifestJson(
                    ShippyBackupManifest(
                        databaseSchemaVersion,
                        createdAtEpochMs,
                        includesHistory,
                        entryManifests,
                    )
                )
                .toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Backup manifest exceeds the size limit" }
        require(
            manifest.size.toLong() + sections.values.sumOf { it.size.toLong() } <=
                MAX_ARCHIVE_UNCOMPRESSED_BYTES
        ) {
            "Backup exceeds the uncompressed size limit"
        }

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.writeEntry(MANIFEST_ENTRY, manifest)
            for (entry in entryManifests) {
                zip.writeEntry(entry.section.entryName, sections.getValue(entry.section))
            }
        }
    }

    fun read(input: InputStream): ShippyBackupV1Archive {
        val rawEntries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Backup directories are not allowed" }
                require(entry.name == MANIFEST_ENTRY || entry.name in SECTION_BY_ENTRY) {
                    "Backup contains an unexpected entry"
                }
                require(entry.name !in rawEntries) { "Backup contains a duplicate entry" }
                val limit =
                    if (entry.name == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_SECTION_BYTES
                val bytes = zip.readBounded(limit)
                totalBytes += bytes.size
                require(totalBytes <= MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                    "Backup exceeds the uncompressed size limit"
                }
                rawEntries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = rawEntries.remove(MANIFEST_ENTRY) ?: error("Backup manifest is missing")
        val manifest = parseManifest(manifestBytes)
        val sections =
            rawEntries.mapKeys { (entryName, _) ->
                requireNotNull(SECTION_BY_ENTRY[entryName]) { "Backup section is not recognized" }
            }
        validateSectionSet(sections.keys, manifest.includesHistory)
        require(manifest.entries.map { it.section }.toSet() == sections.keys) {
            "Backup manifest section set does not match archive entries"
        }
        require(manifest.entries.size == sections.size) {
            "Backup manifest contains duplicate section declarations"
        }
        for (entry in manifest.entries) {
            val bytes = sections.getValue(entry.section)
            require(entry.byteCount == bytes.size.toLong()) {
                "Backup section length does not match its manifest"
            }
            require(entry.sha256 == sha256(bytes)) {
                "Backup section checksum does not match its manifest"
            }
        }
        return ShippyBackupV1Archive(manifest, sections)
    }

    private fun validateSectionSet(sections: Set<ShippyBackupSection>, includesHistory: Boolean) {
        val missing = ShippyBackupSection.entries.filter { it.required && it !in sections }
        require(missing.isEmpty()) { "Backup is missing required sections" }
        require((ShippyBackupSection.HISTORY in sections) == includesHistory) {
            "Backup history section does not match its manifest policy"
        }
    }

    private fun validateSectionSizes(sections: Map<ShippyBackupSection, ByteArray>) {
        require(sections.values.all { it.size <= MAX_SECTION_BYTES }) {
            "Backup section exceeds the size limit"
        }
        require(sections.values.sumOf { it.size.toLong() } <= MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
            "Backup exceeds the uncompressed size limit"
        }
    }

    private fun manifestJson(manifest: ShippyBackupManifest): String =
        JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", FORMAT_VERSION)
            .put("databaseSchemaVersion", manifest.databaseSchemaVersion)
            .put("createdAtEpochMs", manifest.createdAtEpochMs)
            .put("includesHistory", manifest.includesHistory)
            .put(
                "entries",
                JSONArray().apply {
                    manifest.entries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("name", entry.section.entryName)
                                .put("contentType", NDJSON_CONTENT_TYPE)
                                .put("byteCount", entry.byteCount)
                                .put("sha256", entry.sha256)
                        )
                    }
                },
            )
            .toString()

    private fun parseManifest(bytes: ByteArray): ShippyBackupManifest {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getString("format") == FORMAT_NAME) { "Backup format is not ShippyBackupV1" }
        require(json.getInt("formatVersion") == FORMAT_VERSION) {
            "Backup format version is unsupported"
        }
        val schemaVersion = json.getInt("databaseSchemaVersion")
        val createdAt = json.getLong("createdAtEpochMs")
        require(schemaVersion > 0 && createdAt >= 0) { "Backup manifest metadata is invalid" }
        val entriesJson = json.getJSONArray("entries")
        val entries =
            List(entriesJson.length()) { index ->
                val entry = entriesJson.getJSONObject(index)
                require(entry.getString("contentType") == NDJSON_CONTENT_TYPE) {
                    "Backup section content type is unsupported"
                }
                val section =
                    SECTION_BY_ENTRY[entry.getString("name")]
                        ?: error("Backup manifest contains an unknown section")
                val byteCount = entry.getLong("byteCount")
                val checksum = entry.getString("sha256")
                require(byteCount in 0..MAX_SECTION_BYTES.toLong()) {
                    "Backup manifest section length is invalid"
                }
                require(checksum.matches(SHA256_PATTERN)) { "Backup manifest checksum is invalid" }
                ShippyBackupEntryManifest(section, byteCount, checksum)
            }
        return ShippyBackupManifest(
            databaseSchemaVersion = schemaVersion,
            createdAtEpochMs = createdAt,
            includesHistory = json.getBoolean("includesHistory"),
            entries = entries,
        )
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0 }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Backup entry exceeds its size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private const val FORMAT_NAME = "ShippyBackupV1"
    private const val FORMAT_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val NDJSON_CONTENT_TYPE = "application/x-ndjson; charset=utf-8"
    private const val MAX_MANIFEST_BYTES = 1 * 1_024 * 1_024
    private const val MAX_SECTION_BYTES = 64 * 1_024 * 1_024
    private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512L * 1_024 * 1_024
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    private val SECTION_BY_ENTRY = ShippyBackupSection.entries.associateBy { it.entryName }
}
