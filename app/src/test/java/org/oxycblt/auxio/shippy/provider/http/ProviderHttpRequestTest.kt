/*
 * Copyright (c) 2026 Auxio Project
 * ProviderHttpRequestTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.http

import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderHttpRequestTest {
    @Test
    fun `provider requests reject cleartext URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpRequest("http://example.test")
        }
    }

    @Test
    fun `GET requests reject bodies`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpRequest(url = "https://example.test", body = byteArrayOf(1))
        }
    }
}
