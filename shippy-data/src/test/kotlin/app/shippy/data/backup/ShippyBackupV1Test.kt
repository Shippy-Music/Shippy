/*
 * Copyright (c) 2026 Auxio Project
 * ShippyBackupV1Test.kt is part of Auxio.
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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShippyBackupV1Test {
    @Test
    fun `backup round trip retains only allow-listed checksum-verified sections`() {
        val sections = completeSections(includeHistory = true)
        val output = ByteArrayOutputStream()
        ShippyBackupV1.write(output, 1, 123, true, sections)

        val restored = ShippyBackupV1.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(1, restored.manifest.databaseSchemaVersion)
        assertEquals(123, restored.manifest.createdAtEpochMs)
        assertTrue(restored.manifest.includesHistory)
        assertEquals(sections.keys, restored.sections.keys)
        sections.forEach { (section, bytes) ->
            assertTrue(bytes.contentEquals(restored.sections.getValue(section)))
        }
    }

    @Test
    fun `backup rejects missing sections and checksum tampering`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShippyBackupV1.write(ByteArrayOutputStream(), 1, 123, false, emptyMap())
        }

        val output = ByteArrayOutputStream()
        ShippyBackupV1.write(output, 1, 123, false, completeSections(includeHistory = false))
        val tampered =
            rewriteSection(output.toByteArray(), ShippyBackupSection.RECORDINGS.entryName)
        assertThrows(IllegalArgumentException::class.java) {
            ShippyBackupV1.read(ByteArrayInputStream(tampered))
        }
    }

    @Test
    fun `coverage payloads use JSON content type and require matching flags`() {
        val sections =
            completeSections(includeHistory = false) +
                mapOf(
                    ShippyBackupSection.PORTABLE_SETTINGS to
                        R16BackupCoverageCodec.encodePortableSettings(
                            R16PortableSettingsSnapshot(emptyMap())
                        ),
                    ShippyBackupSection.SANITIZED_LASTFM_CONFIG to
                        R16BackupCoverageCodec.encodeLastFmConfig(
                            R16SanitizedLastFmConfig(username = null)
                        ),
                )
        val output = ByteArrayOutputStream()
        ShippyBackupV1.write(
            output = output,
            databaseSchemaVersion = 1,
            createdAtEpochMs = 123,
            includesHistory = false,
            sections = sections,
            portableAppSettingsIncluded = true,
            sanitizedLastFmConfigIncluded = true,
        )

        val manifest =
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { input ->
                var found: String? = null
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    if (entry.name == "manifest.json") found = bytes.decodeToString()
                }
                checkNotNull(found)
            }
        val entries = JSONObject(manifest).getJSONArray("entries")
        val contentTypes =
            (0 until entries.length()).associate {
                val entry = entries.getJSONObject(it)
                entry.getString("name") to entry.getString("contentType")
            }
        assertEquals(
            "application/json; charset=utf-8",
            contentTypes.getValue(ShippyBackupSection.PORTABLE_SETTINGS.entryName),
        )
        assertEquals(
            "application/x-ndjson; charset=utf-8",
            contentTypes.getValue(ShippyBackupSection.RECORDINGS.entryName),
        )
        assertTrue(
            ShippyBackupV1.read(ByteArrayInputStream(output.toByteArray()))
                .manifest
                .portableAppSettingsIncluded
        )
        assertThrows(IllegalArgumentException::class.java) {
            ShippyBackupV1.write(
                output = ByteArrayOutputStream(),
                databaseSchemaVersion = 1,
                createdAtEpochMs = 123,
                includesHistory = false,
                sections = completeSections(includeHistory = false),
                portableAppSettingsIncluded = true,
                sanitizedLastFmConfigIncluded = true,
            )
        }
    }

    private fun completeSections(includeHistory: Boolean): Map<ShippyBackupSection, ByteArray> =
        buildMap {
            ShippyBackupSection.entries
                .filter { it.required }
                .forEach { section ->
                    put(section, "{\"section\":\"${section.name}\"}\n".toByteArray())
                }
            if (includeHistory)
                put(ShippyBackupSection.HISTORY, "{\"history\":true}\n".toByteArray())
        }

    private fun rewriteSection(archive: ByteArray, tamperedEntry: String): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name == tamperedEntry) "tampered".toByteArray() else bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
