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
