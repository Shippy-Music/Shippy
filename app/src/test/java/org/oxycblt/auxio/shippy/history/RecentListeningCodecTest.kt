/*
 * Copyright (c) 2026 Shippy contributors
 * RecentListeningCodecTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.history

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.TrackRealm

class RecentListeningCodecTest {
    private val first = RecentListeningEntry("track-a", TrackRealm.PROVIDER, "First", listOf("Artist"), "Album", "https://image.example/a.jpg", 10)
    private val second = RecentListeningEntry("track-b", TrackRealm.LOCAL, "Second", listOf("Other"), lastPlayedAtEpochMs = 20)

    @Test fun codecRoundTripsBoundedMetadataOnly() {
        assertEquals(listOf(second, first), RecentListeningCodec.decode(RecentListeningCodec.encode(listOf(first, second))))
    }

    @Test fun reducerDedupesByTrackAndKeepsNewestFirst() {
        val updated = first.copy(title = "Updated", lastPlayedAtEpochMs = 30)
        assertEquals(listOf(updated, second), reduceRecentListening(listOf(first, second), updated))
    }

    @Test fun decoderRejectsOversizedEntryCount() {
        val invalid = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x53485248)
                output.writeInt(1)
                output.writeInt(RecentListeningCodec.MAX_ENTRIES + 1)
            }
            bytes.toByteArray()
        }
        assertFalse(runCatching { RecentListeningCodec.decode(invalid) }.isSuccess)
    }
}
