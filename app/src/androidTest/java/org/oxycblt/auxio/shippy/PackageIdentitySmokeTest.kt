/*
 * Copyright (c) 2026 Auxio Project
 * PackageIdentitySmokeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.image.CoverProvider

/** Guards package-scoped authorities when the public application ID changes. */
@RunWith(AndroidJUnit4::class)
class PackageIdentitySmokeTest {
    @Test
    fun coverProviderAuthorityMatchesInstalledApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedAuthority = "${context.packageName}.image.CoverProvider"
        val provider =
            context.packageManager.resolveContentProvider(
                expectedAuthority,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

        assertNotNull("CoverProvider is not registered under $expectedAuthority", provider)
        assertEquals(context.packageName, provider?.packageName)
        assertEquals(expectedAuthority, provider?.authority)
        assertEquals(expectedAuthority, CoverProvider.CONTENT_URI.authority)
    }
}
