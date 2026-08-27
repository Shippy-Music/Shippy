/*
 * Copyright (c) 2026 Auxio Project
 * R16ActiveDataRuntimeOwnerTest.kt is part of Auxio.
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

import app.shippy.data.R16DataRuntime
import app.shippy.data.migration.R16StartupStateReader
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class R16ActiveDataRuntimeOwnerTest {
    private lateinit var root: File
    private lateinit var bootstrap: File
    private var openedRuntime: R16DataRuntime? = null

    @Before
    fun setUp() {
        root =
            File(
                System.getProperty("java.io.tmpdir"),
                "shippy-r16-active-owner-${UUID.randomUUID()}",
            )
        assertTrue(root.mkdirs())
        bootstrap = File(root, "bootstrap-v1.json")
    }

    @After
    fun tearDown() {
        openedRuntime?.close()
        root.deleteRecursively()
    }

    @Test
    fun `inactive authority modes never invoke the runtime opener`() {
        val inactiveStates = listOf(null, "PREPARING", "READY_TO_SWITCH", "corrupt")

        inactiveStates.forEach { status ->
            bootstrap.delete()
            if (status == "corrupt") {
                bootstrap.writeText("not-json")
            } else if (status != null) {
                writeBootstrap(
                    status,
                    completedPhases =
                        if (status == "READY_TO_SWITCH") (0..13).map { "M$it" } else emptyList(),
                    backupSatisfied = status == "READY_TO_SWITCH",
                )
            }

            val owner = owner { error("runtime opener invoked for $status") }

            assertNull(owner.activeReadModelsOrNull())
        }
    }

    @Test
    fun `valid durable active opens and retains the R16 runtime`() {
        writeBootstrap("ACTIVE", completedPhases = (0..14).map { "M$it" }, backupSatisfied = true)
        var opens = 0
        val runtime = R16DataRuntime.open(RuntimeEnvironment.getApplication())
        openedRuntime = runtime
        val owner = owner {
            opens++
            runtime
        }

        assertSame(runtime, owner.activeRuntimeOrNull())
        assertSame(runtime, owner.activeRuntimeOrNull())
        assertEquals(1, opens)
    }

    private fun owner(open: () -> R16DataRuntime): R16ActiveDataRuntimeOwner =
        R16ActiveDataRuntimeOwner(
            authoritySelector =
                R16AuthoritySelector(
                    R16StartupStateReader(bootstrap),
                    R16MigrationProcessGate(bootstrap),
                ),
            runtimeOpener = R16DataRuntimeOpener(open),
        )

    private fun writeBootstrap(
        status: String,
        completedPhases: List<String> = emptyList(),
        backupSatisfied: Boolean = false,
    ) {
        val payload =
            JSONObject()
                .put("revision", 1)
                .put("status", status)
                .put("migrationId", "active-owner-test")
                .put("currentPhase", JSONObject.NULL)
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
