/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryReadModelsActivationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import app.shippy.data.library.R16LibraryReadRepository
import java.lang.reflect.Proxy
import java.util.Optional
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LibraryReadModelsActivationTest {
    @Test
    fun `R16 Library remains inactive without an authority binding`() {
        val activation = R16LibraryReadModelsActivation(Optional.empty())

        assertFalse(activation.isActive)
        assertNull(activation.readModelsOrNull())
    }

    @Test
    fun `R16 Library exposes only an authority-selected shared runtime`() {
        val readModels = fakeReadModels()
        val authority = R16LibraryReadModelsAuthority { readModels }
        val activation = R16LibraryReadModelsActivation(Optional.of(authority))

        assertTrue(activation.isActive)
        assertSame(readModels, activation.readModelsOrNull())
    }

    @Test
    fun `bound authority keeps R16 Library inactive until selection`() {
        val authority = R16LibraryReadModelsAuthority { null }
        val activation = R16LibraryReadModelsActivation(Optional.of(authority))

        assertFalse(activation.isActive)
        assertNull(activation.readModelsOrNull())
    }

    private fun fakeReadModels(): R16LibraryReadRepository =
        Proxy.newProxyInstance(
            R16LibraryReadRepository::class.java.classLoader,
            arrayOf(R16LibraryReadRepository::class.java),
        ) { _, _, _ ->
            error("The activation test must not open a Paging source")
        } as R16LibraryReadRepository
}
