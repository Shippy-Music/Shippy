/*
 * Copyright (c) 2026 Auxio Project
 * R16AuthoritySelectorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.authority

import app.shippy.data.migration.R16StartupStateReader
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16AuthoritySelectorTest {
    private lateinit var root: File
    private lateinit var bootstrap: File

    @Before
    fun setUp() {
        root =
            File(System.getProperty("java.io.tmpdir"), "shippy-r16-authority-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        bootstrap = File(root, "bootstrap-v1.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `missing state keeps R15 as the sole authority`() {
        val mode = selector().select()

        assertEquals(R16AuthorityMode.LEGACY, mode)
        assertTrue(mode.allowsR15Authority)
        assertFalse(bootstrap.exists())
    }

    @Test
    fun `migration state selects recovery and leaves marker untouched`() {
        writeBootstrap(status = "PREPARING", currentPhase = "M0")
        val before = bootstrap.readText()

        val mode = selector().select()

        assertEquals(R16AuthorityMode.MIGRATION_RECOVERY, mode)
        assertFalse(mode.allowsR15Authority)
        assertEquals(before, bootstrap.readText())
        assertTrue(R16MigrationProcessGate(bootstrap).isBlocked())
    }

    @Test
    fun `ready state selects ready-to-switch without activating R16`() {
        writeBootstrap(
            status = "READY_TO_SWITCH",
            currentPhase = "M14",
            completedPhases = (0..13).map { "M$it" },
            backupSatisfied = true,
        )
        val before = bootstrap.readText()

        val mode = selector().select()

        assertEquals(R16AuthorityMode.READY_TO_SWITCH, mode)
        assertFalse(mode.allowsR15Authority)
        assertEquals(before, bootstrap.readText())
    }

    @Test
    fun `corrupt state fails closed into recovery`() {
        bootstrap.writeText("not-json")
        val before = bootstrap.readText()

        val mode = selector().select()

        assertEquals(R16AuthorityMode.CORRUPT_RECOVERY, mode)
        assertFalse(mode.allowsR15Authority)
        assertEquals(before, bootstrap.readText())
        assertTrue(R16MigrationProcessGate(bootstrap).isBlocked())
    }

    @Test
    fun `valid durable active state enables only the R16 host`() {
        writeBootstrap(
            status = "ACTIVE",
            completedPhases = (0..14).map { "M$it" },
            backupSatisfied = true,
        )
        val before = bootstrap.readText()

        val mode = selector().select()

        assertEquals(R16AuthorityMode.ACTIVE, mode)
        assertFalse(mode.allowsR15Authority)
        assertTrue(mode.allowsR16Authority)
        assertEquals(before, bootstrap.readText())
        assertTrue(R16MigrationProcessGate(bootstrap).isBlocked())
    }

    @Test
    fun `invalid active marker fails closed into recovery`() {
        writeBootstrap(
            status = "ACTIVE",
            completedPhases = (0..13).map { "M$it" },
            backupSatisfied = true,
        )

        val mode = selector().select()

        assertEquals(R16AuthorityMode.CORRUPT_RECOVERY, mode)
        assertFalse(mode.allowsR16Authority)
        assertTrue(R16MigrationProcessGate(bootstrap).isBlocked())
    }

    @Test
    fun `process-local migration lock fails closed even without a durable marker`() {
        val gate = R16MigrationProcessGate(bootstrap)
        val lease = checkNotNull(gate.tryAcquireMigrationLock())
        try {
            val mode = R16AuthoritySelector(R16StartupStateReader(bootstrap), gate).select()

            assertEquals(R16AuthorityMode.MIGRATION_RECOVERY, mode)
            assertFalse(mode.allowsR15Authority)
        } finally {
            lease.close()
        }
    }

    private fun selector(): R16AuthoritySelector =
        R16AuthoritySelector(R16StartupStateReader(bootstrap), R16MigrationProcessGate(bootstrap))

    private fun writeBootstrap(
        status: String,
        currentPhase: String? = null,
        completedPhases: List<String> = emptyList(),
        backupSatisfied: Boolean = false,
    ) {
        val payload =
            JSONObject()
                .put("revision", 1)
                .put("status", status)
                .put("migrationId", "authority-test")
                .put("currentPhase", currentPhase ?: JSONObject.NULL)
                .put("completedPhases", JSONArray(completedPhases))
                .put("lastStableKey", JSONObject.NULL)
                .put("backupSatisfied", backupSatisfied)
                .put("legacyDatabaseSha256", if (backupSatisfied) HASH else JSONObject.NULL)
                .put("updatedAtEpochMs", 1)
        val payloadText = payload.toString()
        bootstrap.writeText(
            JSONObject()
                .put("format", "ShippyR16MigrationBootstrap")
                .put("formatVersion", 1)
                .put("payload", payload)
                .put("sha256", payloadText.sha256())
                .toString()
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        val HASH = "a".repeat(64)
    }
}
