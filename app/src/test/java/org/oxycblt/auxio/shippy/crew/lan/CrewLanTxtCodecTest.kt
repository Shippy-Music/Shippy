/*
 * Copyright (c) 2026 Auxio Project
 * CrewLanTxtCodecTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.lan

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

class CrewLanTxtCodecTest {
    @Test
    fun `short lived LAN identity round trips without a secret`() {
        val identity =
            CrewLanIdentity(
                ProtocolVersion(1),
                CrewSessionLocator("session_locator_123"),
                CrewInviteId("invite_12345678"),
            )
        val encoded = CrewLanTxtCodec.encode(identity)
        val wire = encoded.mapValues { it.value.toByteArray(Charsets.UTF_8) }

        assertEquals(identity, CrewLanTxtCodec.decode(wire))
        assertFalse(encoded.values.any { it.contains("secret") })
    }

    @Test
    fun `missing malformed and oversized TXT values are ignored`() {
        val valid =
            CrewLanTxtCodec.encode(
                    CrewLanIdentity(
                        ProtocolVersion(1),
                        CrewSessionLocator("session_locator_123"),
                        CrewInviteId("invite_12345678"),
                    )
                )
                .mapValues { it.value.toByteArray(Charsets.UTF_8) }

        assertEquals(null, CrewLanTxtCodec.decode(valid - "iid"))
        assertEquals(null, CrewLanTxtCodec.decode(valid + ("pv" to "zero".toByteArray())))
        assertEquals(
            null,
            CrewLanTxtCodec.decode(valid + ("sid" to ByteArray(129) { 'a'.code.toByte() })),
        )
        assertEquals(
            null,
            CrewLanTxtCodec.decode(valid + ("sid" to byteArrayOf(0xC3.toByte(), 0x28.toByte()))),
        )
    }

    @Test
    fun `resolved endpoint diagnostics redact addresses and network`() {
        val rendezvous =
            CrewLanRendezvous(
                CrewLanIdentity(
                    ProtocolVersion(1),
                    CrewSessionLocator("session_locator_123"),
                    CrewInviteId("invite_12345678"),
                ),
                "shippy-invite",
                listOf(InetAddress.getByName("192.0.2.1")),
                12_345,
                null,
            )

        assertFalse(rendezvous.toString().contains("192.0.2.1"))
        assertFalse(rendezvous.toString().contains("null"))
    }
}
