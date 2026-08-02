/*
 * Copyright (c) 2026 Auxio Project
 * CrewIdentityTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class CrewIdentityTest {
    @Test
    fun `derived public identity is deterministic and never leaks an opaque member value`() {
        val member = CrewMemberId("content://private/music/album-art.jpg", ProtocolVersion(3))

        val profile = CrewProfileId.fromMember(member)
        val avatar = CrewAvatarDescriptor.derived(member)

        assertEquals(profile, CrewProfileId.fromMember(member))
        assertEquals(avatar, CrewAvatarDescriptor.derived(member))
        assertFalse(profile.value.contains("content"))
        assertFalse(avatar.value.contains("content"))
        assertEquals(16, avatar.value.length)
    }

    @Test
    fun `avatar descriptors reject uri and image payloads`() {
        assertInvalid { CrewAvatarDescriptor("content://album-art") }
        assertInvalid { CrewAvatarDescriptor("https://example.com/image.png") }
        assertInvalid { CrewAvatarDescriptor("0123456789abcdef00") }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected validation to fail")
        } catch (_: IllegalArgumentException) {}
    }
}
