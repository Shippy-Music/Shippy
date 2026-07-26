package org.oxycblt.auxio.shippy.crew.runtime

import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceServer

/** Official WebRTC sample STUN endpoint; no anonymous TURN service is configured. */
val DEFAULT_CREW_REMOTE_ICE_SERVERS = listOf(CrewIceServer(listOf("stun:stun.l.google.com:19302")))
