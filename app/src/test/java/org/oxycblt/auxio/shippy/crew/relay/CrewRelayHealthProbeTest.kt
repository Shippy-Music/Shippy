/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRelayHealthProbeTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.relay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator

class CrewRelayHealthProbeTest {
    @Test
    fun `health URL keeps relay origin and discards endpoint path`() {
        assertEquals(
            "https://relay.example.com:8443/healthz",
            CrewRelayHealthUrl.derive(CrewRelayLocator("https://relay.example.com:8443/v1/crew")),
        )
    }

    @Test
    fun `health parser accepts bounded nonnegative counts`() {
        assertEquals(
            CrewRelayHealth.Healthy(sessions = 2, routes = 3, connections = 4),
            CrewRelayHealthResponseParser.parse(200, "{\"ok\":true,\"sessions\":2,\"routes\":3,\"connections\":4}"),
        )
    }

    @Test
    fun `health parser rejects malformed or unbounded fields`() {
        assertEquals(
            CrewRelayHealth.InvalidResponse,
            CrewRelayHealthResponseParser.parse(200, "{\"ok\":true,\"sessions\":-1,\"routes\":3,\"connections\":4}"),
        )
        assertEquals(
            CrewRelayHealth.InvalidResponse,
            CrewRelayHealthResponseParser.parse(200, "{\"ok\":true,\"sessions\":2.0,\"routes\":3,\"connections\":4}"),
        )
        assertEquals(
            CrewRelayHealth.InvalidResponse,
            CrewRelayHealthResponseParser.parse(503, "{\"ok\":true,\"sessions\":0,\"routes\":0,\"connections\":0}"),
        )
        assertEquals(
            CrewRelayHealth.InvalidResponse,
            CrewRelayHealthResponseParser.parse(200, "{\"ok\":false,\"sessions\":0,\"routes\":0,\"connections\":0}"),
        )
    }
}
