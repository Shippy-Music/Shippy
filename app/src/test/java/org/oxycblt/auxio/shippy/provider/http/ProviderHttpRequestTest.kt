/*
 * Copyright (c) 2026 Shippy contributors
 * ProviderHttpRequestTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
            ProviderHttpRequest(
                url = "https://example.test",
                body = byteArrayOf(1),
            )
        }
    }
}
