/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationProcessGateTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class R16MigrationProcessGateTest {
    private lateinit var root: File
    private lateinit var bootstrap: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "shippy-r16-gate-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        bootstrap = File(root, "bootstrap-v1.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `missing marker allows legacy access`() {
        val gate = R16MigrationProcessGate(bootstrap)

        assertTrue(gate.allowsLegacyAccess())
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `legacy database alone keeps normal R15 access active`() {
        val legacyDatabase = File(root, "shippy.db").apply { writeText("legacy") }
        val gate = R16MigrationProcessGate(bootstrap)

        assertTrue(legacyDatabase.exists())
        assertFalse(gate.requiresMigrationEntry())
        assertFalse(gate.isBlocked())
        checkNotNull(gate.tryAcquireLegacyAccess()).close()
    }

    @Test
    fun `empty or corrupt marker blocks legacy access`() {
        val gate = R16MigrationProcessGate(bootstrap)

        bootstrap.writeText("")
        assertTrue(gate.isBlocked())

        bootstrap.writeText("not-json")
        assertTrue(gate.isBlocked())

        assertTrue(bootstrap.delete())
        assertTrue(bootstrap.mkdir())
        assertTrue(gate.isBlocked())
    }

    @Test
    fun `backup-only marker blocks legacy access during atomic recovery`() {
        val backup = File(bootstrap.path + ".bak")
        backup.writeText("incomplete atomic replacement")
        val gate = R16MigrationProcessGate(bootstrap)

        assertFalse(bootstrap.exists())
        assertTrue(backup.exists())
        assertTrue(gate.isBlocked())
        assertNull(gate.tryAcquireLegacyAccess())
    }

    @Test
    fun `process lock blocks and releases legacy access`() {
        val gate = R16MigrationProcessGate(bootstrap)

        val lease = gate.tryAcquireMigrationLock()
        assertNotNull(lease)
        assertTrue(gate.isBlocked())
        assertNull(gate.tryAcquireMigrationLock())

        lease!!.close()
        assertTrue(gate.allowsLegacyAccess())
    }

    @Test
    fun `legacy lease can close on another coroutine dispatcher`() = runBlocking {
        val gate = R16MigrationProcessGate(bootstrap)
        val lease = checkNotNull(gate.tryAcquireLegacyAccess())

        withContext(Dispatchers.IO) { lease.close() }

        assertTrue(gate.allowsLegacyAccess())
    }

    @Test
    fun `migration lock waits for active legacy lease`() {
        val gate = R16MigrationProcessGate(bootstrap)
        val legacyLease = checkNotNull(gate.tryAcquireLegacyAccess())

        assertNull(gate.tryAcquireMigrationLock())

        legacyLease.close()
        val migrationLease = checkNotNull(gate.tryAcquireMigrationLock())
        assertTrue(gate.isBlocked())
        migrationLease.close()
        assertTrue(gate.allowsLegacyAccess())
    }
}
